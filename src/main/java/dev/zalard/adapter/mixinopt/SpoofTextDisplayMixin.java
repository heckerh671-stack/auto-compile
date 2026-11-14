package dev.zalard.adapter.mixinopt;

import dev.zalard.adapter.SpoofText;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Name Spoof on text-display nametags (what most plugin nametags use on 1.20+). */
@Mixin(Display.TextDisplay.class)
public abstract class SpoofTextDisplayMixin {
    @Inject(method = "getText", at = @At("RETURN"), cancellable = true)
    private void zalard$text(CallbackInfoReturnable<Component> cir) {
        Component r = cir.getReturnValue();
        if (r == null || !SpoofText.namesActive()) return;
        Component out = SpoofText.replaceNames(r);
        if (out != r) cir.setReturnValue(out);
    }
}
