package dev.zalard.adapter.mixin;

import dev.zalard.adapter.SpoofText;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Skin Spoof on the player model. */
@Mixin(AbstractClientPlayer.class)
public abstract class SpoofSkinMixin {
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void zalard$skin(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerSkin swapped = SpoofText.spoofSkin(((AbstractClientPlayer) (Object) this).getUUID(), cir.getReturnValue(), true);
        if (swapped != null) cir.setReturnValue(swapped);
    }
}
