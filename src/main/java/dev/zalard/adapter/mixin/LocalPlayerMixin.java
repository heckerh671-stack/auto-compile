package dev.zalard.adapter.mixin;

import dev.zalard.adapter.RotationBridgeImpl;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs before vanilla decides whether this tick's movement packet carries a rotation
 * (it compares yRot/xRot with yRotLast/xRotLast).
 *  - fade step first, so the check and the packet see the angle that actually goes out
 *  - silent head moved            -> force a rotation into the packet
 *  - silent head did NOT move     -> tell vanilla nothing changed, so no rotation is sent even if
 *                                    you moved your mouse (no two identical looks in a row)
 *  - silent aim just let go       -> one rotation so the server gets your real camera
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void zalard$rotationIfMoved(CallbackInfo ci) {
        var self = (LocalPlayer) (Object) this;
        var acc = (LocalPlayerAccessor) self;
        if (RotationBridgeImpl.active()) {
            if (RotationBridgeImpl.needsSend()) {
                acc.zalard$setYRotLast(self.getYRot() + 1.0f);
            } else {
                acc.zalard$setYRotLast(self.getYRot());
                acc.zalard$setXRotLast(self.getXRot());
            }
        } else if (RotationBridgeImpl.takeResync()) {
            // your camera's raw yaw may be whole turns away from what the server was last sent;
            // shift it by those whole turns (looks identical) so the hand-back isn't a 360 spin
            if (RotationBridgeImpl.rawKnown()) {
                float turns = Math.round((RotationBridgeImpl.rawSent() - self.getYRot()) / 360f) * 360f;
                if (turns != 0f) {
                    self.setYRot(self.getYRot() + turns);   self.yRotO += turns;
                    self.setYHeadRot(self.getYHeadRot() + turns); self.yHeadRotO += turns;
                    self.yBodyRot += turns;                  self.yBodyRotO += turns;
                }
            }
            acc.zalard$setYRotLast(self.getYRot() + 1.0f);
        }
    }
}
