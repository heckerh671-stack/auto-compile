package dev.zalard.adapter.mixinopt;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TBot crits: while it's setting up a crit, vanilla may not START sprinting again (with Toggle
 * Sprint the sprint key can't be "let go" by setting it up, so this is the reliable way).
 */
@Mixin(LocalPlayer.class)
public abstract class SprintHoldMixin {
    @Inject(method = "canStartSprinting", at = @At("HEAD"), cancellable = true)
    private void zalard$holdSprint(CallbackInfoReturnable<Boolean> cir) {
        if (dev.zalard.nat.Z.is(dev.zalard.nat.Z.Q_HOLDS_SPRINT)) cir.setReturnValue(false);
    }
}
