package dev.zalard.adapter.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.zalard.nativegui.NativeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paint the native GUI last, right before the frame is presented. */
@Mixin(Window.class)
public class WindowMixin {
    @Inject(method = "updateDisplay", at = @At("HEAD"), require = 1)
    private void zalard$drawNativeGui(CallbackInfo ci) {
        dev.zalard.nat.Z.run(dev.zalard.nat.Z.FRAME);   // per-frame: aim assist smoothing, Totem cursor
        NativeRenderer.renderIfOpen();
    }
}
