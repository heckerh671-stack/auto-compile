package dev.zalard.adapter.mixinopt;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Lets Skin Spoof check a texture is really there before pointing a skin at it. */
@Mixin(TextureManager.class)
public interface TextureManagerAccessor {
    @Accessor("byPath") Map<Identifier, AbstractTexture> zalard$byPath();
}
