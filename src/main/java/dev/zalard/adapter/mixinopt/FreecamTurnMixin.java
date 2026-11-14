package dev.zalard.adapter.mixinopt;

import dev.zalard.adapter.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam: the mouse turns the camera, not your player. */
@Mixin(Entity.class)
public abstract class FreecamTurnMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void zalard$freecamTurn(double yRot, double xRot, CallbackInfo ci) {
        if (!Freecam.active()) return;
        if ((Object) this != Minecraft.getInstance().player) return;
        Freecam.turn(yRot, xRot);
        ci.cancel();
    }
}
