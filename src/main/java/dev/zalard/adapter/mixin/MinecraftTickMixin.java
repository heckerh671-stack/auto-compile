// Zalard - Copyright (c) 2026 weksom. All rights reserved. Proprietary; see LICENSE.
package dev.zalard.adapter.mixin;

import dev.zalard.adapter.ZalardMod;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives Zalard's per-tick logic from the start of Minecraft.tick() — the same point Fabric API's
 * START_CLIENT_TICK fired. Replacing that event with a mixin removes the Fabric API dependency, so the
 * mod loads in any Fabric-based environment. Minecraft.tick() is a core, stable target.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftTickMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void zalard$tick(CallbackInfo ci) {
        ZalardMod.tick((Minecraft) (Object) this);
    }
}
