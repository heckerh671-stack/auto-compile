package dev.zalard.adapter.mixinopt;

import dev.zalard.adapter.Freecam;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam: after vanilla placed the camera for this frame, put it where the freecam is. */
@Mixin(Camera.class)
public abstract class FreecamCameraMixin {
    @Shadow private boolean detached;
    @Shadow protected abstract void setPosition(double x, double y, double z);
    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "setup", at = @At("TAIL"))
    private void zalard$freecam(CallbackInfo ci) {
        if (!Freecam.active()) return;
        double[] e = Freecam.eye();
        this.setRotation(Freecam.yaw(), Freecam.pitch());
        this.setPosition(e[0], e[1], e[2]);
        this.detached = true;            // draw your own body where you left it
    }
}
