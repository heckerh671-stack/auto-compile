package dev.zalard.adapter.mixin;

import dev.zalard.adapter.MoveFix;
import dev.zalard.adapter.RotationBridgeImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Silent Aim movement fix, movement-math half: walk relative to the head yaw. See MoveFix. */
@Mixin(Entity.class)
public abstract class MoveFixEntityMixin {

    @ModifyArg(method = "moveRelative",
               at = @At(value = "INVOKE",
                        target = "Lnet/minecraft/world/entity/Entity;getInputVector(Lnet/minecraft/world/phys/Vec3;FF)Lnet/minecraft/world/phys/Vec3;"),
               index = 2)
    private float zalard$moveYaw(float yaw) {
        return MoveFix.applies(this) ? RotationBridgeImpl.yaw() : yaw;
    }
}
