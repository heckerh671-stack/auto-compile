package dev.zalard.adapter;

// Tiny shared stamp of when we last sent an attack packet. PlatformImpl.sendAttack
// writes to this; AutoElytra reads it to decide when to swap back from chestplate.
public final class AttackLog {
    private static volatile long lastMs = 0L;
    private AttackLog() {}
    public static long lastAttackMs() { return lastMs; }
    public static void mark()         { lastMs = System.currentTimeMillis(); }
}
