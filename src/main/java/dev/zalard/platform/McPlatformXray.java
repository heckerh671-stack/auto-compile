package dev.zalard.platform;

// chunk + light + block-entity queries for xray.
public interface McPlatformXray {

    /** Block-light level 0..15 at the given position. Light updates leak around EM1
     *  anti-xray configs; strict EM2 shuffles light too but imperfectly. */
    default int blockLightAt(int x, int y, int z)   { throw unsupported(); }
    default int skyLightAt(int x, int y, int z)     { throw unsupported(); }

    /** True if the chunk (x>>4, z>>4) is loaded on the client. */
    default boolean chunkLoaded(int blockX, int blockZ) { throw unsupported(); }

    /** All entity types currently in the chunk at (chunkX, chunkZ) — reveal mob
     *  spawns in dark areas = cave below. */
    default java.util.List<String> entityTypesInChunk(int chunkX, int chunkZ) { throw unsupported(); }

    /** All currently-loaded block positions in a chunk section that match the given id.
     *  Honest result: on EM2 servers this returns the SERVER-SENT positions, which
     *  are shuffled. Useful only on vanilla / EM1 configs for direct ore ESP. */
    default java.util.List<int[]> findBlocksInChunk(int chunkX, int chunkZ, String blockId) { throw unsupported(); }

    /**
     * Fast ore scan of one chunk: walks only the 16x16x16 sections whose palette can contain one of
     * `ids`. Returns {x, y, z, index into ids}. `exposedOnly` keeps ores that touch air or a fluid
     * (anti-xray servers bury their fake ores, so this drops most of them).
     */
    default java.util.List<int[]> scanOres(int chunkX, int chunkZ, String[] ids, boolean exposedOnly) { throw unsupported(); }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("McPlatformXray method not implemented in this adapter yet");
    }
}
