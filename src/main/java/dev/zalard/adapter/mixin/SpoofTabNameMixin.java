package dev.zalard.adapter.mixin;

import dev.zalard.spoof.SpoofRegistry;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Name Spoof on the tablist (PlayerTabOverlay reads getTabListDisplayName first). */
@Mixin(PlayerInfo.class)
public abstract class SpoofTabNameMixin {
    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void zalard$tabName(CallbackInfoReturnable<Component> cir) {
        PlayerInfo info = (PlayerInfo) (Object) this;
        var gp = info.getProfile();
        if (gp == null) return;
        String spoofed = SpoofRegistry.displayName(gp.id());
        if (spoofed == null) return;
        Component server = cir.getReturnValue();
        if (server == null) {
            // vanilla-style entry: your spoofed name with your team's prefix / colour
            cir.setReturnValue(net.minecraft.world.scores.PlayerTeam.formatNameForTeam(info.getTeam(), Component.literal(spoofed)));
        } else {
            // the server (a plugin) formatted it: keep their ranks / colours, swap the name inside
            cir.setReturnValue(dev.zalard.adapter.SpoofText.replaceNames(server));
        }
    }
}
