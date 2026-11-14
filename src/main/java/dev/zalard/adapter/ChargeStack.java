// Zalard - Copyright (c) 2026 weksom. All rights reserved. Proprietary; see LICENSE.
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
 * Charge Stack (2b2t wind launch). Holds the inbound explosion-knockback packets so your client stays
 * grounded while the native module spams wind charges straight down — each charge's knockback queues
 * instead of pushing you off the spot. When native flips {@link Z#chargeHold()} back to false, the whole
 * queue is released at once: the client applies every stacked knockback together and you rocket up.
 *
 * This holds YOUR explode/velocity packets only (ClientboundExplodePacket + ClientboundSetEntityMotion),
 * which is the opposite of {@link Lag} (that holds other entities' position packets). Chunks, keep-alives,
 * your own position syncs, etc. always pass through, so the connection never times out.
 *
 * The hold window is short (the length of the spam), so not filtering SetEntityMotion by entity id is
 * fine — the only motion that matters in that ~1s is your own launch.
 */
public final class ChargeStack {
    private ChargeStack() {}

    private static final String NAME = "mako_charge";
    private static final int QUEUE_CAP = 2048;

    private static volatile boolean active;        // pushed from native each tick
    private static Connection installed;

    /** Every client tick: refresh the hold flag from native and keep the handler installed. */
    public static void drive(Minecraft mc) {
        try { active = Z.chargeHold(); } catch (Throwable t) { active = false; }

        var listener = mc.getConnection();
        Connection con = listener != null ? listener.getConnection() : null;
        if (con == null) { installed = null; return; }
        if (con == installed) return;
        installed = con;
        try {
            Channel ch = ((ConnectionAccessor) con).zalard$channel();
            if (ch == null || ch.pipeline().get(NAME) != null) return;
            Handler h = new Handler();
            if (ch.pipeline().get("packet_handler") != null) ch.pipeline().addBefore("packet_handler", NAME, h);
            else ch.pipeline().addLast(NAME, h);
        } catch (Throwable ignored) {}
    }

    private static boolean holdable(Object msg) {
        if (!(msg instanceof net.minecraft.network.protocol.Packet)) return false;
        String n = msg.getClass().getName();
        // ClientboundExplodePacket carries the wind-charge knockback the client ADDS to your velocity —
        // that's what stacks. SetEntityMotion is held too in case the server sends motion directly.
        return n.contains("ExplodePacket") || n.contains("Explode")
            || n.contains("SetEntityMotion") || n.contains("EntityVelocity");
    }

    private static final class Handler extends ChannelInboundHandlerAdapter {
        private final ArrayDeque<Object> held = new ArrayDeque<>();
        private ScheduledFuture<?> ticker;

        @Override public void handlerAdded(ChannelHandlerContext ctx) {
            // release promptly when native drops the hold, even if no new packet is arriving
            ticker = ctx.channel().eventLoop().scheduleAtFixedRate(() -> {
                try { if (!active && !held.isEmpty()) flushAll(ctx); } catch (Throwable ignored) {}
            }, 10, 10, TimeUnit.MILLISECONDS);
        }

        @Override public void handlerRemoved(ChannelHandlerContext ctx) {
            if (ticker != null) { ticker.cancel(false); ticker = null; }
            flushAll(ctx);
        }

        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (active && holdable(msg)) {
                held.addLast(msg);
                while (held.size() > QUEUE_CAP) ctx.fireChannelRead(held.pollFirst());   // never grow unbounded
                return;
            }
            // not holding (or not a knockback packet): if a queue is pending, release it now -> launch
            if (!held.isEmpty()) flushAll(ctx);
            ctx.fireChannelRead(msg);
        }

        private void flushAll(ChannelHandlerContext ctx) {
            while (!held.isEmpty()) ctx.fireChannelRead(held.pollFirst());
        }
    }
}
