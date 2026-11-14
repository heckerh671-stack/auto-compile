package dev.zalard.adapter.mixin;

import dev.zalard.adapter.MoveFix;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Silent Aim movement fix, sprint-jump half. See MoveFix. */
@Mixin(LivingEntity.class)
public abstract class MoveFixMixin {
    // Plain @Inject on purpose: a @Redirect here crashed together with MixinExtras in modpacks.
    @Inject(method = "jumpFromGround", at = @At("TAIL"))
    private void zalard$jumpYaw(CallbackInfo ci) {
        if (MoveFix.applies(this)) MoveFix.fixJump((LivingEntity) (Object) this);
    }
}
