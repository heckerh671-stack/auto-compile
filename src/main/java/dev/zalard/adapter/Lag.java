package dev.zalard.adapter;

import dev.zalard.adapter.mixin.ConnectionAccessor;
import dev.zalard.nat.Z;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;

import java.util.ArrayDeque;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * "Lag" module: holds inbound (clientbound) packets on the connection's netty pipeline. The native
 * side (z::lag) owns the toggle + settings and hands them here once per tick via {@link Z#lagConfig()};
 * all the queuing lives here because packets never reach the native logic.
 *
 * Only other entities' position packets are held (see {@link #holdable}); your own position
 * corrections, world/chunk data and keep-alives always pass through, so the connection can't time
 * out and you never rubberband yourself — the only thing that lags is the enemy hitbox.
 *
 * Modes:
 *   Backtrack - release held position packets on a rolling {@code hold}-ms delay, so other players'
 *               hitboxes trail behind where they really are (you hit the ghost).
 *   Blink     - hold them while on, release the whole queue the instant the module turns off, so
 *               nearby players freeze in place then snap forward.
 *
 * Anything still queued is flushed if the handler is torn down (e.g. switching servers).
 */
public final class Lag {
    private Lag() {}

    private static final String NAME = "mako_lag";
    private static final int QUEUE_CAP = 4096;   // hard ceiling so a long/stuck hold can't grow unbounded

    // Pushed from native each client tick (main thread); read on the netty event loop.
    private static volatile boolean active;
    private static volatile int mode;            // 0 backtrack, 1 blink, 2 burst
    private static volatile int holdMs = 150;
    private static volatile int burstMs = 120;
    private static volatile boolean releaseOnHit = true;
    private static volatile long flushPulse;     // bumped on every attack; backtrack flushes when it moves

    private static Connection installed;

    /** Every client tick (main thread): refresh config from native and keep the handler installed. */
    public static void drive(Minecraft mc) {
        long cfg;
        try { cfg = Z.lagConfig(); } catch (Throwable t) { cfg = 0; }
        active = (cfg & 1L) != 0;
        mode = (int) ((cfg >> 1) & 3L);          // 0 backtrack, 1 blink, 2 burst
        releaseOnHit = (cfg & 8L) != 0;
        holdMs = (int) ((cfg >> 16) & 0xFFFFL);
        burstMs = (int) ((cfg >> 32) & 0xFFFFL);

        var listener = mc.getConnection();
        Connection con = listener != null ? listener.getConnection() : null;
        if (con == null) { installed = null; return; }
        if (con == installed) return;            // already handled this connection
        installed = con;
        try {
            Channel ch = ((ConnectionAccessor) con).zalard$channel();
            if (ch == null || ch.pipeline().get(NAME) != null) return;
            Handler h = new Handler();
            // intercept inbound packets before vanilla's handler processes them
            if (ch.pipeline().get("packet_handler") != null) ch.pipeline().addBefore("packet_handler", NAME, h);
            else ch.pipeline().addLast(NAME, h);
        } catch (Throwable ignored) {}
    }

    /** Called from the attack hook: in backtrack mode this flushes the held window so your hit lands. */
    public static void onAttack() { flushPulse++; }

    private static boolean holdable(Object msg) {
        if (!(msg instanceof net.minecraft.network.protocol.Packet)) return false;
        // Quiet mode: hold ONLY other entities' position updates, so their hitbox lags behind where
        // they really are. Everything else — your own position corrections, chunks, knockback
        // (ClientboundSetEntityMotion, which Velocity hooks), keep-alives — passes straight through,
        // so the server-side desync footprint is just the entities you're fighting, not the whole feed.
        // getName() (not getSimpleName()) so the nested ClientboundMoveEntityPacket$Pos/$PosRot/$Rot
        // match via their enclosing class.
        String n = msg.getClass().getName();
        return n.contains("MoveEntity")           // ClientboundMoveEntityPacket (+ $Pos / $PosRot / $Rot)
            || n.contains("TeleportEntity")        // ClientboundTeleportEntityPacket (absolute)
            || n.contains("EntityPositionSync");   // ClientboundEntityPositionSyncPacket (1.21.2+)
    }

    private static final class Node {
        final Object msg; final long t;
        Node(Object msg, long t) { this.msg = msg; this.t = t; }
    }

    /** All state below is touched only on the channel's event loop thread. */
    private static final class Handler extends ChannelInboundHandlerAdapter {
        private final ArrayDeque<Node> held = new ArrayDeque<>();
        private ScheduledFuture<?> ticker;
        private long seenPulse;
        private long burstStart;

        @Override public void handlerAdded(ChannelHandlerContext ctx) {
            // time-based release (backtrack window) and toggle-off flush need to fire even when no new
            // packets are arriving, so run a light pump on the event loop
            ticker = ctx.channel().eventLoop().scheduleAtFixedRate(() -> {
                try { pump(ctx); } catch (Throwable ignored) {}
            }, 10, 10, TimeUnit.MILLISECONDS);
        }

        @Override public void handlerRemoved(ChannelHandlerContext ctx) {
            if (ticker != null) { ticker.cancel(false); ticker = null; }
            flushAll(ctx);   // never drop packets on teardown
        }

        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!active || !holdable(msg)) { ctx.fireChannelRead(msg); return; }
            held.addLast(new Node(msg, System.nanoTime()));
            pump(ctx);
        }

        private void flushAll(ChannelHandlerContext ctx) {
            while (!held.isEmpty()) ctx.fireChannelRead(held.pollFirst().msg);
        }

        private void pump(ChannelHandlerContext ctx) {
            if (held.isEmpty()) return;
            if (!active) { flushAll(ctx); burstStart = 0; return; }   // module off -> release the whole queue

            long pulse = flushPulse;
            if (pulse != seenPulse) {
                seenPulse = pulse;
                if (releaseOnHit && mode == 0) { flushAll(ctx); return; }   // backtrack: land the hit
            }

            if (mode == 1) {                                 // blink: hold until off, just cap memory
                while (held.size() > QUEUE_CAP) ctx.fireChannelRead(held.pollFirst().msg);
                return;
            }

            if (mode == 2) {                                 // burst: freeze for burstMs, snap-release, repeat
                long now = System.nanoTime();
                if (burstStart == 0) burstStart = now;
                if (now - burstStart >= (long) Math.max(20, burstMs) * 1_000_000L) { flushAll(ctx); burstStart = now; }
                while (held.size() > QUEUE_CAP) ctx.fireChannelRead(held.pollFirst().msg);
                return;
            }

            // backtrack: release everything older than the window
            long cutoff = System.nanoTime() - (long) holdMs * 1_000_000L;
            while (!held.isEmpty() && held.peekFirst().t <= cutoff) ctx.fireChannelRead(held.pollFirst().msg);
            while (held.size() > QUEUE_CAP) ctx.fireChannelRead(held.pollFirst().msg);
        }
    }
}
