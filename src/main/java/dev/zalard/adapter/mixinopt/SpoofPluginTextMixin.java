package dev.zalard.adapter.mixinopt;

import dev.zalard.adapter.SpoofText;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Name Spoof inside text that plugin servers build themselves: team prefixes / suffixes (sidebar
 * scoreboard lines, nametags, tab). The real name is swapped for the spoofed one wherever it shows
 * up, keeping colors and formatting. Client side only: it changes what you see, nothing is sent.
 */
@Mixin(PlayerTeam.class)
public abstract class SpoofPluginTextMixin {
    @Inject(method = "formatNameForTeam", at = @At("RETURN"), cancellable = true)
    private static void zalard$team(Team team, Component name, CallbackInfoReturnable<MutableComponent> cir) {
        MutableComponent r = cir.getReturnValue();
        if (r == null || !SpoofText.namesActive()) return;
        Component out = SpoofText.replaceNames(r);
        if (out != r) cir.setReturnValue(out instanceof MutableComponent m ? m : Component.empty().append(out));
    }
}
