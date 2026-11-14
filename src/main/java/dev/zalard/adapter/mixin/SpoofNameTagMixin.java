package dev.zalard.adapter.mixin;

import dev.zalard.spoof.SpoofRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Name Spoof above heads. Player overrides Entity#getName, so the hook has to
 * live on Player (the old Entity hook never fired for players).
 * getDisplayName builds on getName, so team colors/prefixes are kept.
 */
@Mixin(Player.class)
public abstract class SpoofNameTagMixin {
    @Inject(method = "getName", at = @At("HEAD"), cancellable = true)
    private void zalard$playerName(CallbackInfoReturnable<Component> cir) {
        String spoofed = SpoofRegistry.displayName(((Player) (Object) this).getUUID());
        if (spoofed != null) cir.setReturnValue(Component.literal(spoofed));
    }
}
