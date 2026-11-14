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
 * Drip (held item): swaps the main/off-hand item the third-person item layer renders for your own model.
 * Visual only — redirecting getItemBySlot inside the layer leaves your actual held item (and all logic)
 * untouched. First-person hand rendering goes through ItemInHandRenderer, a different path not covered here.
 *
 * VERIFY TARGET per mappings: 1.21.11 Mojang = ItemInHandLayer.render(...) calling getItemBySlot. If the
 * layer fetches the stack another way (getItemInHand/getMainHandItem), this redirect finds no target and
 * require = 0 makes it a safe no-op — re-point at whatever the layer actually calls. 26.2: re-check.
 */
@Mixin(net.minecraft.client.renderer.entity.layers.ItemInHandLayer.class)
public abstract class HandDripMixin {

    @Redirect(method = "render",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/world/entity/LivingEntity;getItemBySlot(Lnet/minecraft/world/entity/EquipmentSlot;)Lnet/minecraft/world/item/ItemStack;"),
              require = 0)
    private ItemStack zalard$handDrip(LivingEntity e, EquipmentSlot slot) {
        ItemStack real = e.getItemBySlot(slot);
        if (!(e instanceof LocalPlayer)) return real;
        int s = slot == EquipmentSlot.MAINHAND ? 4 : slot == EquipmentSlot.OFFHAND ? 5 : -1;
        if (s < 0) return real;
        ItemStack fake = DripService.fake(s);
        return fake.isEmpty() ? real : fake;
    }
}
