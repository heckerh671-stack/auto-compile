package dev.zalard.adapter;

import dev.zalard.platform.McPlatformXray;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LightLayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 26.2 implementation of the Xray platform surface.
 * Light queries use LightEngine; direct-ore scan iterates chunk sections.
 */
public final class PlatformXrayImpl implements McPlatformXray {

    private final Minecraft mc = Minecraft.getInstance();

    @Override public int blockLightAt(int x, int y, int z) {
        if (mc.level == null) return 15;
        try { return mc.level.getBrightness(LightLayer.BLOCK, new BlockPos(x, y, z)); }
        catch (Throwable t) { return 15; }
    }
    @Override public int skyLightAt(int x, int y, int z) {
        if (mc.level == null) return 15;
        try { return mc.level.getBrightness(LightLayer.SKY, new BlockPos(x, y, z)); }
        catch (Throwable t) { return 15; }
    }

    @Override public boolean chunkLoaded(int blockX, int blockZ) {
        if (mc.level == null) return false;
        return mc.level.hasChunk(blockX >> 4, blockZ >> 4);
    }

    @Override public List<String> entityTypesInChunk(int chunkX, int chunkZ) {
        List<String> out = new ArrayList<>();
        if (mc.level == null) return out;
        int minX = chunkX << 4, maxX = minX + 16;
        int minZ = chunkZ << 4, maxZ = minZ + 16;
        for (var e : mc.level.entitiesForRendering()) {
            double ex = e.getX(), ez = e.getZ();
            if (ex < minX || ex >= maxX || ez < minZ || ez >= maxZ) continue;
            out.add(e.getType().toShortString());
        }
        return out;
    }

    @Override public List<int[]> scanOres(int chunkX, int chunkZ, String[] ids, boolean exposedOnly) {
        List<int[]> out = new ArrayList<>();
        var level = mc.level;
        if (level == null || !level.hasChunk(chunkX, chunkZ)) return out;
        var blocks = new net.minecraft.world.level.block.Block[ids.length];
        for (int i = 0; i < ids.length; i++) {
            var id = Identifier.tryParse(ids[i]);
            blocks[i] = id == null ? null : BuiltInRegistries.BLOCK.getValue(id);
        }
        var chunk = level.getChunk(chunkX, chunkZ);
        var sections = chunk.getSections();
        int minSection = level.getMinY() >> 4;
        var pos = new BlockPos.MutableBlockPos();
        for (int si = 0; si < sections.length; si++) {
            var sec = sections[si];
            if (sec == null || sec.hasOnlyAir()) continue;
            // the palette says whether any of our ores can be in this section at all
            if (!sec.maybeHas(st -> index(blocks, st.getBlock()) >= 0)) continue;
            int baseY = (minSection + si) << 4;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int idx = index(blocks, sec.getBlockState(x, y, z).getBlock());
                if (idx < 0) continue;
                int wx = (chunkX << 4) + x, wy = baseY + y, wz = (chunkZ << 4) + z;
                if (exposedOnly && !exposed(level, pos, wx, wy, wz)) continue;
                out.add(new int[]{ wx, wy, wz, idx });
            }
        }
        return out;
    }

    private static int index(net.minecraft.world.level.block.Block[] blocks, net.minecraft.world.level.block.Block b) {
        for (int i = 0; i < blocks.length; i++) if (blocks[i] == b) return i;
        return -1;
    }

    private static final int[][] SIDES = { {1,0,0}, {-1,0,0}, {0,1,0}, {0,-1,0}, {0,0,1}, {0,0,-1} };
    private static boolean exposed(net.minecraft.client.multiplayer.ClientLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        for (int[] d : SIDES) {
            var st = level.getBlockState(pos.set(x + d[0], y + d[1], z + d[2]));
            if (st.isAir() || !st.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    @Override public List<int[]> findBlocksInChunk(int chunkX, int chunkZ, String blockId) {
        List<int[]> out = new ArrayList<>();
        if (mc.level == null) return out;
        var want = Identifier.tryParse(blockId);
        if (want == null) return out;
        var wantBlock = BuiltInRegistries.BLOCK.getValue(want);
        if (wantBlock == null) return out;

        var chunk = mc.level.getChunk(chunkX, chunkZ);
        int minY = mc.level.getMinY();
        int maxY = (mc.level.getMaxY() + 1);
        // Walk each y — slow but called at most every 10 ticks per chunk from XrayModule
        for (int y = minY; y < maxY; y++) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    var state = chunk.getBlockState(new BlockPos((chunkX << 4) + lx, y, (chunkZ << 4) + lz));
                    if (state.getBlock() == wantBlock) {
                        out.add(new int[]{(chunkX << 4) + lx, y, (chunkZ << 4) + lz});
                    }
                }
            }
        }
        return out;
    }
}
