package dev.zalard.platform;

/** Version-abstract block state at a position. */
public interface BlockHandle {
    int x();
    int y();
    int z();
    String blockId();              // "minecraft:obsidian"
    boolean isAir();
    boolean isReplaceable();
    boolean isSolid();
    boolean isLiquid();
    /** Hardness used by auto-miner / crystal placement scoring. */
    float hardness();
}
