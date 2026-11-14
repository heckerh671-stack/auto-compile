package dev.zalard.adapter.mixin;

import dev.zalard.platform.Platforms;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = net.minecraft.client.multiplayer.ClientPacketListener.class, remap = true)
public abstract class ExplosionPacketMixin {

    @Inject(method = "handleExplosion", at = @At("TAIL"), require = 0)
    void mako_logExplosion(ClientboundExplodePacket pkt, CallbackInfo ci) {
        try { dev.zalard.nat.Z.onExplosion(System.currentTimeMillis()); } catch (Throwable ignored) {}   // Jump Reset ignores explosion knockback
    }
}
