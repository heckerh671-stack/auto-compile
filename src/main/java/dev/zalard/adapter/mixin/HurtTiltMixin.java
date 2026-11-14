// Zalard - Copyright (c) 2026 weksom. All rights reserved. Proprietary; see LICENSE.
package dev.zalard.adapter.mixin;

import dev.zalard.nat.Z;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Overlay (hurt tilt): cancels the camera tilt vanilla applies when you take damage, so your view
 * stays steady in a fight. Bit Z.NO_HURT of Q_NO_OVERLAY.
 *
 * VERIFY TARGET per mappings: 1.21.11 Mojang = GameRenderer.bobHurt(PoseStack, float). Newer snapshots
 * renamed/retyped the camera-shake path (DeltaTracker); require = 0 keeps it harmless if the descriptor
 * is off. 26.2: re-point at the current hurt-shake method.
 */
@Mixin(net.minecraft.client.renderer.GameRenderer.class)
public abstract class HurtTiltMixin {

    @Inject(method = "bobHurt(Lcom/mojang/blaze3d/vertex/PoseStack;F)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void zalard$noHurtTilt(PoseStack pose, float partial, CallbackInfo ci) {
        if ((Z.qi(Z.Q_NO_OVERLAY) & Z.NO_HURT) != 0) ci.cancel();
    }
}
