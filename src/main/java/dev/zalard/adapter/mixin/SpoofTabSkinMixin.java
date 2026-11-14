package dev.zalard.adapter.mixin;

import dev.zalard.adapter.SpoofText;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Skin Spoof on tablist heads. */
@Mixin(PlayerInfo.class)
public abstract class SpoofTabSkinMixin {
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void zalard$tabSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        var gp = ((PlayerInfo) (Object) this).getProfile();
        if (gp == null) return;
        PlayerSkin swapped = SpoofText.spoofSkin(gp.id(), cir.getReturnValue(), false);
        if (swapped != null) cir.setReturnValue(swapped);
    }
}
