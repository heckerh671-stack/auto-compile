package dev.zalard.adapter.mixin;

import dev.zalard.adapter.RotationBridgeImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shows the silent-aim rotation on your own player model (third person). */
@Mixin(LivingEntityRenderer.class)
public abstract class SilentHeadMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"))
    private void zalard$silentHead(LivingEntity entity, LivingEntityRenderState state, float partial, CallbackInfo ci) {
        if (entity != Minecraft.getInstance().player || !RotationBridgeImpl.active()) return;
        if (!dev.zalard.nat.Z.is(dev.zalard.nat.Z.Q_SHOW_HEAD)) return;
        float yaw = RotationBridgeImpl.yaw(partial);            // smooth between ticks
        float head = Mth.wrapDegrees(yaw - state.bodyRot);
        if (head > 75f)  { state.bodyRot = yaw - 75f; head = 75f; }    // turn the body when the head can't
        if (head < -75f) { state.bodyRot = yaw + 75f; head = -75f; }
        state.yRot = head;
        state.xRot = RotationBridgeImpl.pitch(partial);
    }
}
