package dev.zalard.platform;

/** Static holder — set exactly once by the per-version Fabric entry point. */
public final class Platforms {
    private static volatile McPlatform platform;

    private Platforms() {}

    public static void install(McPlatform p) {
        if (platform != null) throw new IllegalStateException("Platform already installed: " + platform.minecraftVersion());
        platform = p;
    }

    public static McPlatform get() {
        McPlatform p = platform;
        if (p == null) throw new IllegalStateException("Mako platform not installed — did the version adapter's ClientModInitializer run?");
        return p;
    }
}
