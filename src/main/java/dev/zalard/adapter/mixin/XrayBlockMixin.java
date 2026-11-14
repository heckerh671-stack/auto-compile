// Zalard - Copyright (c) 2026 weksom. All rights reserved. Proprietary; see LICENSE.
package dev.zalard.adapter.mixin;

import dev.zalard.nat.Z;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Xray: while on, non-ore block faces are culled (you see through terrain) and ore blocks keep
 * rendering. Toggling needs a chunk reload (F3+A) for vanilla to rebuild the meshes.
 *
 * VERIFY TARGET per mappings: 1.21.11 Mojang = Block.shouldRenderFace(BlockState, BlockGetter, BlockPos,
 * Direction, BlockPos). require = 0 means if this signature is off, Xray just does nothing (no crash) —
 * fix the descriptor and it comes alive. 26.2: re-check this signature; the face-cull path has churned.
 */
@Mixin(net.minecraft.world.level.block.Block.class)
public abstract class XrayBlockMixin {

    @Inject(method = "shouldRenderFace(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void zalard$xray(BlockState state, net.minecraft.world.level.BlockGetter level,
                                    net.minecraft.core.BlockPos pos, net.minecraft.core.Direction dir,
                                    net.minecraft.core.BlockPos neighbor, CallbackInfoReturnable<Boolean> cir) {
        if (!Z.is(Z.Q_XRAY)) return;
        cir.setReturnValue(isOre(state));     // ores render, everything else is culled -> see-through
    }

    private static boolean isOre(BlockState state) {
        try {
            String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            return path.endsWith("_ore") || path.equals("ancient_debris") || path.equals("raw_iron_block")
                    || path.equals("raw_gold_block") || path.equals("raw_copper_block");
        } catch (Throwable t) { return false; }
    }
}
