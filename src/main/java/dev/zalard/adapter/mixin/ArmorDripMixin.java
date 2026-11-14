// Zalard - Copyright (c) 2026 weksom. All rights reserved. Proprietary; see LICENSE.
package dev.zalard.adapter.mixin;

import dev.zalard.adapter.DripService;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Drip (armor): swaps the ItemStack the armor layer renders for YOUR own model only. Redirecting the
 * getItemBySlot call *inside the layer's render* means only the visuals change — game logic still sees
 * your real armor (hitboxes, protection, the server are untouched). Only you see the fake armor.
 *
 * VERIFY TARGET per mappings: 1.21.11 Mojang = HumanoidArmorLayer.render(...) calling
 * LivingEntity.getItemBySlot(EquipmentSlot). require = 0 keeps it a no-op if the invoke target differs.
 * 26.2: armor rendering moved toward the Equippable component — re-point the redirect there.
 */
@Mixin(net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer.class)
public abstract class ArmorDripMixin {

    @Redirect(method = "render",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/world/entity/LivingEntity;getItemBySlot(Lnet/minecraft/world/entity/EquipmentSlot;)Lnet/minecraft/world/item/ItemStack;"),
              require = 0)
    private ItemStack zalard$armorDrip(LivingEntity e, EquipmentSlot slot) {
        ItemStack real = e.getItemBySlot(slot);
        if (!(e instanceof LocalPlayer)) return real;          // only your own model
        int s = switch (slot) {
            case HEAD -> 0; case CHEST -> 1; case LEGS -> 2; case FEET -> 3; default -> -1;
        };
        if (s < 0) return real;
        ItemStack fake = DripService.fake(s);
        return fake.isEmpty() ? real : fake;
    }
}
