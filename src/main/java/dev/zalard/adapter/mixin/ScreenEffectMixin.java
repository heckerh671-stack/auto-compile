// Zalard - Copyright (c) 2026 weksom. All rights reserved. Proprietary; see LICENSE.
package dev.zalard.adapter.mixin;

import dev.zalard.nat.Z;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Overlay (screen gunk): cancels the full-screen fire / water / in-wall(pumpkin+suffocation) overlays
 * vanilla paints over your view, per the bits in Z.q(Q_NO_OVERLAY).
 *
 * VERIFY TARGET per mappings: 1.21.11 Mojang = net.minecraft.client.renderer.ScreenEffectRenderer with
 * private renderFire / renderWater / renderTex. If method names differ, require = 0 makes each inject a
 * no-op (no crash). 26.2: the screen-effect renderer moved under the new render pipeline — re-point there.
 */
@Mixin(net.minecraft.client.renderer.ScreenEffectRenderer.class)
public abstract class ScreenEffectMixin {

    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true, require = 0)
    private static void zalard$noFire(CallbackInfo ci) {
        if ((Z.qi(Z.Q_NO_OVERLAY) & Z.NO_FIRE) != 0) ci.cancel();
    }

    @Inject(method = "renderWater", at = @At("HEAD"), cancellable = true, require = 0)
    private void zalard$noWater(CallbackInfo ci) {
        if ((Z.qi(Z.Q_NO_OVERLAY) & Z.NO_WATER) != 0) ci.cancel();
    }

    // renderTex draws the in-wall / pumpkin-blur texture overlay.
    @Inject(method = "renderTex", at = @At("HEAD"), cancellable = true, require = 0)
    private static void zalard$noPumpkin(CallbackInfo ci) {
        if ((Z.qi(Z.Q_NO_OVERLAY) & Z.NO_PUMPKIN) != 0) ci.cancel();
    }
}
