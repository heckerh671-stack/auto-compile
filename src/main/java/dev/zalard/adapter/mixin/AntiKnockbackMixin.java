package dev.zalard.adapter.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Anti-knockback. Vanilla handles the packet first (TAIL injections only run on
// the main-thread pass, after ensureRunningOnSameThread), then we replace the
// velocity it applied with Velocity's result (native logic).
@Mixin(ClientPacketListener.class)
public abstract class AntiKnockbackMixin {

    @Inject(method = "handleSetEntityMotion", at = @At("TAIL"))
    private void mako_setMotion(ClientboundSetEntityMotionPacket pkt, CallbackInfo ci) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.player.getId() != pkt.getId()) return;
        Vec3 v = pkt.getMovement();
        double[] out;
        try {
            dev.zalard.nat.Z.onKnockback(v.x, v.y, v.z, System.currentTimeMillis());   // Jump Reset listens for this
            out = dev.zalard.nat.Z.filterKnockback(v.x, v.y, v.z, false, System.currentTimeMillis());
        } catch (Throwable t) { return; }
        if (out == null) return;
        mc.player.setDeltaMovement(out[0], out[1], out[2]);
    }

    @Inject(method = "handleExplosion", at = @At("TAIL"))
    private void mako_explode(ClientboundExplodePacket pkt, CallbackInfo ci) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || pkt.playerKnockback().isEmpty()) return;
        Vec3 kb = pkt.playerKnockback().get();
        if (kb.x == 0 && kb.y == 0 && kb.z == 0) return;
        double[] out;
        try { out = dev.zalard.nat.Z.filterKnockback(kb.x, kb.y, kb.z, true, System.currentTimeMillis()); } catch (Throwable t) { return; }
        if (out == null) return;
        Vec3 cur = mc.player.getDeltaMovement();
        // vanilla already added kb; swap it for the filtered amount
        mc.player.setDeltaMovement(cur.x - kb.x + out[0], cur.y - kb.y + out[1], cur.z - kb.z + out[2]);
    }
}
