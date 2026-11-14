package dev.zalard.adapter.mixin;


import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hooks ClientCommonPacketListenerImpl#handleKeepAlive (moved there in 1.20.2+). The vanilla path immediately
// echoes the keep-alive back to the server, which is what the server times
// for its ping measurement.
//
// If PingSpoof is active, we cancel the vanilla handler and schedule the
// echo on a daemon thread with the configured delay. The server receives
// the echo late → ping measurement = (delay + real round-trip) → that number
// propagates to everyone's tablist via UPDATE_LATENCY.
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class PingSpoofMixin {
    private static final java.util.concurrent.ScheduledExecutorService DELAY =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mako-pingspoof"); t.setDaemon(true); return t; });

    @Inject(method = "handleKeepAlive", at = @At("HEAD"), cancellable = true)
    private void mako_keepalive(ClientboundKeepAlivePacket pkt, CallbackInfo ci) {
        if (!dev.zalard.nat.Z.is(dev.zalard.nat.Z.Q_PING_SPOOF)) return;

        long delay = 0L;
        try {
            delay = dev.zalard.nat.Z.pingDelay(dev.zalard.platform.Platforms.get().selfTabPing());
        } catch (Throwable ignored) {}
        if (delay <= 0L) return;   // let vanilla echo instantly

        ci.cancel();
        final long id = pkt.getId();
        final long wait = delay;   // use the delay we just computed (it was re-rolled before)
        DELAY.schedule(() -> {
            try {
                var mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.getConnection() == null) return;
                mc.getConnection().send(new net.minecraft.network.protocol.common.ServerboundKeepAlivePacket(id));
            } catch (Throwable ignored) {}
        }, Math.max(1, wait), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

}
