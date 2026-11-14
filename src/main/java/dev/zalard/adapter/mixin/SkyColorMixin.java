// Zalard - Copyright (c) 2026 weksom. All rights reserved. Proprietary; see LICENSE.
package dev.zalard.adapter.mixin;

import dev.zalard.nat.Z;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Visuals (sky color): overrides the sky RGB with the color picked in the Visuals module. Z.q(Q_SKY_COLOR)
 * returns packed ARGB, or 0 to leave the vanilla sky. Hooking getSkyColor (a plain Vec3 return) is the
 * clean, version-stable way to retint the sky without touching the fog pipeline.
 *
 * VERIFY TARGET per mappings: 1.21.11 Mojang = ClientLevel.getSkyColor(Vec3, float):Vec3. require = 0
 * keeps it harmless if the signature differs. 26.2: re-check getSkyColor's args.
 */
@Mixin(net.minecraft.client.multiplayer.ClientLevel.class)
public abstract class SkyColorMixin {

    @Inject(method = "getSkyColor(Lnet/minecraft/world/phys/Vec3;F)Lnet/minecraft/world/phys/Vec3;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private void zalard$skyColor(Vec3 pos, float partial, CallbackInfoReturnable<Vec3> cir) {
        int argb = Z.qi(Z.Q_SKY_COLOR);
        if (argb == 0) return;
        double r = ((argb >> 16) & 0xFF) / 255.0, g = ((argb >> 8) & 0xFF) / 255.0, b = (argb & 0xFF) / 255.0;
        cir.setReturnValue(new Vec3(r, g, b));
    }
}
