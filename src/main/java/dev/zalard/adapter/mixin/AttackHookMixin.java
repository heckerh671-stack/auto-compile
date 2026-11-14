package dev.zalard.adapter.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every attack you send (your own clicks AND module hits) goes through here: W-Tap / S-Tap and
 *  Mace Swap react to it on the next tick. */
@Mixin(MultiPlayerGameMode.class)
public abstract class AttackHookMixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void zalard$onAttack(Player player, Entity target, CallbackInfo ci) {
        if (target == null || player == null) return;
        try {
            dev.zalard.nat.Z.onAttack(target.getId(), target instanceof Player, target instanceof LivingEntity, player.isSprinting());
            dev.zalard.adapter.Lag.onAttack();   // backtrack: flush the held window so this hit lands
        } catch (Throwable ignored) {}
    }
}
