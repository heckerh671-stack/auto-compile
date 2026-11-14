package dev.zalard.adapter.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla never draws your own nametag. With Name Spoof on, show it in third person
 *  so you can actually see the spoofed name on yourself. */
@Mixin(LivingEntityRenderer.class)
public abstract class OwnNametagMixin {
    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("HEAD"), cancellable = true)
    private void zalard$ownName(LivingEntity e, double distSq, CallbackInfoReturnable<Boolean> cir) {
        var mc = Minecraft.getInstance();
        if (e != mc.player || mc.options.getCameraType().isFirstPerson() || mc.options.hideGui) return;
        if (dev.zalard.adapter.SpoofService.ownTag()) cir.setReturnValue(true);
    }
}
