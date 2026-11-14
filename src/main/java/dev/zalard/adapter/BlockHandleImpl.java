package dev.zalard.adapter;

import dev.zalard.platform.BlockHandle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

final class BlockHandleImpl implements BlockHandle {
    private final BlockPos pos;
    private final BlockState state;

    BlockHandleImpl(BlockPos pos, BlockState state) { this.pos = pos; this.state = state; }

    @Override public int x() { return pos.getX(); }
    @Override public int y() { return pos.getY(); }
    @Override public int z() { return pos.getZ(); }

    @Override public String blockId() {
        var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key == null ? "minecraft:air" : key.toString();
    }

    @Override public boolean isAir()         { return state.isAir(); }
    @Override public boolean isReplaceable() { return state.canBeReplaced(); }
    @Override public boolean isSolid()       { return state.blocksMotion(); }
    @Override public boolean isLiquid()      { FluidState f = state.getFluidState(); return !f.isEmpty(); }
    @Override public float hardness()        { return state.getDestroySpeed(null, pos); }
}
