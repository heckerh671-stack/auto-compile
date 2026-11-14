package dev.zalard.adapter.mixinopt;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Fast Place: vanilla's 4-tick wait between held right clicks. */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("rightClickDelay") int zalard$getRightClickDelay();
    @Accessor("rightClickDelay") void zalard$setRightClickDelay(int v);
}
