package dev.zalard.adapter.mixinopt;

import dev.zalard.adapter.SpoofText;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Name Spoof on hologram nametags plugins make out of named armor stands / entities. */
@Mixin(Entity.class)
public abstract class SpoofEntityTextMixin {
    @Inject(method = "getCustomName", at = @At("RETURN"), cancellable = true)
    private void zalard$custom(CallbackInfoReturnable<Component> cir) {
        Component r = cir.getReturnValue();
        if (r == null || !SpoofText.namesActive()) return;
        Component out = SpoofText.replaceNames(r);
        if (out != r) cir.setReturnValue(out);
    }
}
