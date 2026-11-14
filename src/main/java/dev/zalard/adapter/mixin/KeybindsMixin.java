package dev.zalard.adapter.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs right before vanilla reads key presses each tick, so Trigger Bot's click is processed
 *  exactly like a real mouse click: same tick, before the movement packet. */
@Mixin(Minecraft.class)
public class KeybindsMixin {
    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void zalard$beforeKeybinds(CallbackInfo ci) {
        dev.zalard.nat.Z.run(dev.zalard.nat.Z.PRE_INPUT);
    }
}
