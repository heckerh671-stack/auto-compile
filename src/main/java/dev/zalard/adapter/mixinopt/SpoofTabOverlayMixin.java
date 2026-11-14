package dev.zalard.adapter.mixinopt;

import dev.zalard.adapter.SpoofText;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Name Spoof in the tab list's final text (plugins send their own formatted tab names). */
@Mixin(PlayerTabOverlay.class)
public abstract class SpoofTabOverlayMixin {
    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void zalard$tab(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        Component r = cir.getReturnValue();
        if (r == null || !SpoofText.namesActive()) return;
        Component out = SpoofText.replaceNames(r);
        if (out != r) cir.setReturnValue(out);
    }
}
