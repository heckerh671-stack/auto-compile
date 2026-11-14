package dev.zalard.adapter.mixinopt;

import dev.zalard.adapter.Freecam;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam: no first-person hand floating in front of the flying camera. */
@Mixin(GameRenderer.class)
public abstract class FreecamHandMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void zalard$freecamHand(CallbackInfo ci) {
        if (Freecam.active()) ci.cancel();
    }
}
