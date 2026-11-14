package dev.zalard.adapter.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MultiPlayerGameMode.class)
public interface GameModeInvoker {
    /** Vanilla's own held-slot sync: sends the slot packet only if it changed. */
    @Invoker("ensureHasSentCarriedItem") void zalard$syncSlot();
}
