package dev.zalard.spoof;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Central registry of per-player display overrides. Platform mixins query this
// at render time and substitute names / skin texture locations before passing
// through to vanilla rendering.
//
// Nothing here ever reaches the network. The server sees everyone's real
// identity; only our local client lies to itself.
public final class SpoofRegistry {

    private SpoofRegistry() {}

    public record Entry(String displayName, UUID skinSourceUuid) {}

    // real-UUID -> override
    private static final Map<UUID, Entry> OVERRIDES = new ConcurrentHashMap<>();
    // UUID -> ResourceLocation path we've cached a loaded skin texture under
    private static final Map<UUID, String> SKIN_PATHS = new ConcurrentHashMap<>();
    // skin owner UUID -> true if that skin uses the slim (Alex) arms
    private static final Map<UUID, Boolean> SLIM = new ConcurrentHashMap<>();

    public static void setOverride(UUID realUuid, String nameOrNull, UUID skinSourceOrNull) {
        if (nameOrNull == null && skinSourceOrNull == null) {
            OVERRIDES.remove(realUuid);
            return;
        }
        OVERRIDES.put(realUuid, new Entry(nameOrNull, skinSourceOrNull));
    }

    public static Entry get(UUID realUuid) { return OVERRIDES.get(realUuid); }

    /** The display name for `realUuid`, or null for "use the real one". */
    public static String displayName(UUID realUuid) {
        Entry e = OVERRIDES.get(realUuid);
        return e == null ? null : e.displayName;
    }

    /** Returns the UUID whose skin should be rendered for `realUuid`, or null for the real skin. */
    public static UUID skinSource(UUID realUuid) {
        Entry e = OVERRIDES.get(realUuid);
        return e == null ? null : e.skinSourceUuid;
    }

    /** Cache the ResourceLocation path we loaded a skin into so adapters can
     *  swap in the texture without re-fetching every frame. */
    public static void cacheSkinPath(UUID uuid, String path) { SKIN_PATHS.put(uuid, path); }
    public static String cachedSkinPath(UUID uuid)           { return SKIN_PATHS.get(uuid); }

    public static void setSlim(UUID skinOwner, boolean slim) { SLIM.put(skinOwner, slim); }
    /** null = unknown (keep the player's own model). */
    public static Boolean isSlim(UUID skinOwner)             { return SLIM.get(skinOwner); }

    // Cape Spoof: texture path of your spoofed cape (null = your real cape)
    private static volatile String capePath;
    public static void setCapePath(String path) { capePath = path; }
    public static String capePath() { return capePath; }

    public static void clear() { OVERRIDES.clear(); SKIN_PATHS.clear(); SLIM.clear(); }

    /** realUuid -> spoofed display name, for every active name override. */
    public static Map<UUID, String> nameOverrides() {
        Map<UUID, String> out = new java.util.HashMap<>();
        OVERRIDES.forEach((k, v) -> { if (v.displayName() != null) out.put(k, v.displayName()); });
        return out;
    }
}
