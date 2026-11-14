package dev.zalard.platform;

import java.util.List;

// esp + hud draw hooks.
public interface McPlatformExt2 {

    /** Block entities within `radius` blocks of the local player. Chests, shulkers, hoppers, etc. */
    default List<BlockEntityHandle> blockEntitiesNear(double radius) { throw unsupported(); }

    /** Dropped-item entity positions within `radius` whose item id contains `filter` (case-insensitive;
     *  blank = all). Each element is {x, y, z}. Java applies the filter so native only gets coordinates. */
    default List<double[]> droppedItemsNear(double radius, String filter) { throw unsupported(); }

    /** Position {x, y, z} of the local player's most-recently-thrown ender pearl, or null if none is in
     *  flight. Used by Pearl Catch to aim the wind charge at the pearl. */
    default double[] thrownPearlPos() { throw unsupported(); }

    /** Your active potion effects for the HUD, one per line "Display Name M:SS" (amplifier folded into
     *  the name, e.g. "Strength II"), "--" time for infinite. Empty string if none. */
    default String activeEffects() { return ""; }

    /** Current dimension's registry key — "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end". */
    default String currentDimension() { throw unsupported(); }

    /** True if we're in creative mode on this server. */
    default boolean isCreative() { throw unsupported(); }

    /** Ping to the current server, in ms. */
    default int serverPing() { throw unsupported(); }

    /** FPS from the Minecraft client stats. */
    default int currentFps() { throw unsupported(); }

    /** Current server-visible server-brand ("vanilla", "paper", "spigot", etc.). */
    default String serverBrand() { throw unsupported(); }

    /** Draw a 2D HUD rect (used by ArrayListHud, TargetHud). */
    default void hudRect(int x, int y, int w, int h, int argb)   { throw unsupported(); }
    /** Draw 2D HUD text. */
    default void hudText(String s, int x, int y, int argb)       { throw unsupported(); }
    /** 2D HUD text width in pixels, for right-align. */
    default int  hudTextWidth(String s)                          { throw unsupported(); }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("McPlatformExt2 method not implemented in this adapter yet");
    }
}
