// Zalard - Copyright (c) 2026 weksom. All rights reserved. Proprietary; see LICENSE.
package dev.zalard.adapter.mixin;

import dev.zalard.nat.Z;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Visuals (fullbright): forces the light-texture brightness to full so caves/night are lit. Flag
 * Z.VF_FULLBRIGHT of Q_VISUAL_FLAGS.
 *
 * VERIFY TARGET per mappings: 1.21.11 Mojang = LightTexture.getBrightness(DimensionType, int):float.
 * require = 0 keeps it harmless if the descriptor is off. 26.2: re-check the brightness accessor.
 */
@Mixin(net.minecraft.client.renderer.LightTexture.class)
public abstract class LightTextureMixin {

    @Inject(method = "getBrightness(Lnet/minecraft/world/level/dimension/DimensionType;I)F",
            at = @At("RETURN"), cancellable = true, require = 0)
    private static void zalard$fullbright(net.minecraft.world.level.dimension.DimensionType dim, int light,
                                          CallbackInfoReturnable<Float> cir) {
        if ((Z.qi(Z.Q_VISUAL_FLAGS) & Z.VF_FULLBRIGHT) != 0) cir.setReturnValue(1.0f);
    }
}
