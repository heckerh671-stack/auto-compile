package dev.zalard.platform;

/**
 * Opaque wrapper for a block entity (chest, shulker, hopper, etc.).
 * All ESP code queries this instead of raw Minecraft BlockEntity classes.
 */
public interface BlockEntityHandle {
    int x();
    int y();
    int z();
    String blockId();              // "minecraft:chest", "minecraft:trapped_chest", etc.
    boolean isContainer();         // chest, shulker, hopper, barrel, etc.
    boolean isTrapped();           // trapped chest only
}
