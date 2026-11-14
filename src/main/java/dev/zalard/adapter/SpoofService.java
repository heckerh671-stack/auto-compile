package dev.zalard.adapter;

import dev.zalard.platform.Platforms;
import dev.zalard.spoof.Capes;
import dev.zalard.spoof.MojangSkinFetcher;
import dev.zalard.spoof.SpoofRegistry;

import java.util.UUID;

/**
 * The Java half of Client Spoofer. The module (settings, when to apply what) lives in the native
 * logic; this only does what needs Java: the name / skin overrides the mixins read, downloading
 * skins and capes, and loading them as textures. Nothing here is sent to the server.
 */
public final class SpoofService {
    private SpoofService() {}

    private static volatile int flags;
    /** Show your own nametag in third person (Name Spoof on). */
    public static boolean ownTag() { return (flags & 1) != 0; }
    /** Cape Spoof on. */
    public static boolean capeOn() { return (flags & 2) != 0; }
    static void flags(int f) { flags = f; }

    // fetch callbacks arrive on worker threads; chat goes out on the client thread
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> CHAT = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** Client thread, every tick. */
    public static void tick() {
        for (String m; (m = CHAT.poll()) != null; ) {
            try { Platforms.get().sendClientMessage(m); } catch (Throwable ignored) {}
        }
    }

    private static UUID self() { return Platforms.get().selfUuid(); }

    private static UUID skinOf(UUID real) {
        var e = SpoofRegistry.get(real);
        return e == null ? null : e.skinSourceUuid();
    }

    static void name(String name) {
        UUID self = self();
        SpoofRegistry.setOverride(self, name == null || name.isEmpty() ? null : name, skinOf(self));
    }

    static void skin(String src) {
        UUID self = self();
        if (src == null || src.isEmpty()) {
            var e = SpoofRegistry.get(self);
            SpoofRegistry.setOverride(self, e == null ? null : e.displayName(), null);
        } else applySkin(self, src);
    }

    static void other(String target, String newName, String skin) {
        UUID id = null;
        try { for (var e : Platforms.get().allPlayers()) if (target.equalsIgnoreCase(e.name())) { id = e.uuid(); break; } }
        catch (Throwable ignored) {}
        if (id == null) return;
        SpoofRegistry.setOverride(id, newName == null || newName.isEmpty() ? null : newName, skinOf(id));
        if (skin != null && !skin.isEmpty()) applySkin(id, skin);
    }

    static void cape(String name) {
        if (name == null || name.isEmpty()) { SpoofRegistry.setCapePath(null); return; }
        Capes.fetch(name,
                (hash, png) -> { try { Platforms.get().registerCapeTexture(hash, png); } catch (Throwable t) { CHAT.add("§b[Zalard]§r Cape failed: " + t.getMessage()); } },
                why -> CHAT.add("§b[Zalard]§r Couldn't get the " + name + " cape (" + why + ")"));
    }

    private static void applySkin(UUID realUuid, String sourceName) {
        // instant: the skin's owner is online and the game already has their skin loaded
        try {
            String[] on = Platforms.get().onlineSkin(sourceName);
            if (on != null) {
                UUID owner = UUID.fromString(on[0]);
                SpoofRegistry.cacheSkinPath(owner, on[1]);
                SpoofRegistry.setSlim(owner, "slim".equals(on[2]));
                var cur = SpoofRegistry.get(realUuid);
                SpoofRegistry.setOverride(realUuid, cur == null ? null : cur.displayName(), owner);
                return;
            }
        } catch (Throwable ignored) {}
        MojangSkinFetcher.prefetchAndApply(sourceName, realUuid,
            (uuid, png) -> {
                try {
                    Platforms.get().registerSkinTexture(uuid, png);
                    CHAT.add("§b[Zalard]§r Skin set to " + sourceName);
                } catch (Throwable t) {
                    CHAT.add("§b[Zalard]§r Skin failed: " + t.getMessage());
                }
            },
            why -> CHAT.add("§b[Zalard]§r Couldn't get " + sourceName + "'s skin (" + why + ")"));
    }
}
