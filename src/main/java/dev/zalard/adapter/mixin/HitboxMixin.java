// Zalard - Copyright (c) 2026 weksom. All rights reserved. Proprietary; see LICENSE.
package dev.zalard.adapter.mixin;

import dev.zalard.nat.Z;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hitboxes: forces entity hitbox outlines on (no F3+B needed) and optionally inflates them.
 * Z.q(Q_HITBOX): 0 = off, else 1 + expand*1000.
 *
 * VERIFY TARGET per mappings: 1.21.11 Mojang = EntityRenderDispatcher.shouldRenderHitBoxes():boolean and
 * the static renderHitbox(...) which calls Entity.getBoundingBox(). require = 0 → no-op if names differ.
 */
@Mixin(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class)
public abstract class HitboxMixin {

    @Inject(method = "shouldRenderHitBoxes", at = @At("HEAD"), cancellable = true, require = 0)
    private void zalard$force(CallbackInfoReturnable<Boolean> cir) {
        if (Z.qi(Z.Q_HITBOX) != 0) cir.setReturnValue(true);
    }

    @Redirect(method = "renderHitbox",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"),
              require = 0)
    private static AABB zalard$expand(Entity e) {
        AABB box = e.getBoundingBox();
        int v = Z.qi(Z.Q_HITBOX);
        return v > 1 ? box.inflate((v - 1) / 1000.0) : box;
    }
}
