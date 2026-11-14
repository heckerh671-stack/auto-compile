package dev.zalard.adapter.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec2;
//? if >=26 {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No Slow: vanilla scales your movement input by 0.2 while you use an item. We undo that factor.
 *
 * 1.21.11: the scale happens in LocalPlayer.modifyInput(Vec2); we @Inject at RETURN and un-scale.
 * (A @Redirect here crashed launch — MixinExtras "ArrayList cannot be cast to AnnotationNode";
 *  a plain @Inject at RETURN avoids that, and require = 0 means a renamed method just disables
 *  No Slow instead of crashing.)
 *
 * 26.2: modifyInput is GONE. Input lives in KeyboardInput.tick(), which builds this.moveVector
 * (Vec2) and this.keyPresses (Input record). The 0.2 use-item slowdown is applied in the new
 * input path — the 26.2 branch re-scales moveVector at tick TAIL. See MULTIVERSION.md.
 *
 * The 1.21.11 branch is live/vcs; the 26.2 branch is Stonecutter-commented, so the current build
 * is unchanged.
 */
//? if <26 {
@Mixin(LocalPlayer.class)
public abstract class NoSlowMixin {
    @Inject(method = "modifyInput", at = @At("RETURN"), cancellable = true, require = 0)
    private void zalard$noSlow(Vec2 input, CallbackInfoReturnable<Vec2> cir) {
        if (!dev.zalard.nat.Z.is(dev.zalard.nat.Z.Q_NO_SLOW)) return;
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (!self.isUsingItem() || self.isPassenger()) return;
        Vec2 out = cir.getReturnValue();
        if (out != null) cir.setReturnValue(out.scale(5.0f));      // 1 / 0.2
    }
}
//?}
//? if >=26 {
/*@Mixin(KeyboardInput.class)
public abstract class NoSlowMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void zalard$noSlow(CallbackInfo ci) {
        if (!dev.zalard.nat.Z.is(dev.zalard.nat.Z.Q_NO_SLOW)) return;
        LocalPlayer self = Minecraft.getInstance().player;
        if (self == null || !self.isUsingItem() || self.isPassenger()) return;
        // 26.2 already applied the 0.2 use-item factor to moveVector in the vanilla tick. Undo it,
        // then rebuild keyPresses so sprint/strafe stay consistent with the restored vector.
        Vec2 v = this.moveVector;
        if (v == null) return;
        Vec2 restored = v.scale(5.0f);                             // 1 / 0.2
        this.moveVector = restored;
        Input keys = this.keyPresses;
        this.keyPresses = new Input(restored.y > 0.0F, restored.y < 0.0F, restored.x > 0.0F,
                restored.x < 0.0F, keys.jump(), keys.shift(), keys.sprint());
    }
}
*///?}
