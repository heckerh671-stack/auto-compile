package dev.zalard.adapter.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
    @Accessor("clickCount") int zalard$getClickCount();
    @Accessor("clickCount") void zalard$setClickCount(int v);
    @Accessor("key") com.mojang.blaze3d.platform.InputConstants.Key zalard$getKey();
}
