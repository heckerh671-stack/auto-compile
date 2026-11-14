// Zalard - Copyright (c) 2026 weksom. All rights reserved. Proprietary; see LICENSE.
package dev.zalard.adapter.mixin;

import dev.zalard.nat.Z;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Camera Clip: lets the third-person (F5) camera pass through blocks. Vanilla raycasts from your head
 * to the desired camera distance and pulls the camera in to the nearest block hit (getMaxZoom). When
 * Q_CAM_NOCLIP is set we just return the full requested distance, so the camera ignores walls.
 *
 * VERIFY TARGET per mappings: 1.21.11 Mojang = Camera.getMaxZoom(double):double. require = 0 keeps it a
 * no-op if the signature differs. 26.2: re-check getMaxZoom's name/args.
 */
@Mixin(net.minecraft.client.Camera.class)
public abstract class CameraMixin {

    @Inject(method = "getMaxZoom(D)D", at = @At("HEAD"), cancellable = true, require = 0)
    private void zalard$camNoClip(double startingDistance, CallbackInfoReturnable<Double> cir) {
        if (Z.is(Z.Q_CAM_NOCLIP)) cir.setReturnValue(startingDistance);   // no collision pull-in
    }
}
