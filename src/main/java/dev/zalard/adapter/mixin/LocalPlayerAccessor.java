package dev.zalard.adapter.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
    @Accessor("yRotLast") void zalard$setYRotLast(float v);
    @Accessor("yRotLast") float zalard$getYRotLast();
    @Accessor("xRotLast") void zalard$setXRotLast(float v);
}
