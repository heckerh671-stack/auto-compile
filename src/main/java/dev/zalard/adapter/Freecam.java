package dev.zalard.adapter;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Freecam, done as a camera override (no fake entity, no setCameraEntity).
 *
 * The camera keeps its OWN position and its OWN yaw / pitch:
 *  - FreecamCameraMixin puts the camera there at the end of Camera.setup every frame and marks it
 *    "detached" so your own body is drawn where you left it.
 *  - FreecamTurnMixin catches the mouse turn before it reaches your player, so the mouse turns the
 *    camera only. Your player's head is never touched, and nothing that rotates your player (Silent
 *    Aim, Aim Assist, the server) can move the camera. That shared-rotation was what made the old
 *    version swing left and right.
 *  - Position moves once per tick (WASD / Space / Shift, along where the camera looks) and is blended
 *    between ticks by time, so flying is smooth at any frame rate.
 *
 * Your player gets no movement keys and no clicks while it's on, and nothing is sent for the camera.
 */
public final class Freecam {
    private Freecam() {}

    private static volatile boolean on;
    private static double speed = 1;
    private static double x, y, z, px, py, pz;      // camera eye now / last tick
    private static float yaw, pitch;
    private static long tickNanos;
    private static Object level;

    public static boolean active() { return on; }

    public static void set(boolean enable, double spd) {
        speed = spd;
        if (enable == on) return;
        Minecraft mc = Minecraft.getInstance();
        if (enable) {
            if (mc.player == null || mc.level == null) return;
            x = px = mc.player.getX();
            y = py = mc.player.getEyeY();
            z = pz = mc.player.getZ();
            yaw = mc.player.getYRot();
            pitch = mc.player.getXRot();
            level = mc.level;
            tickNanos = System.nanoTime();
            on = true;
        } else {
            on = false;
            if (mc.options != null) {                         // keys back to what you're holding
                var o = mc.options;
                for (KeyMapping k : new KeyMapping[]{ o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyShift })
                    k.setDown(PlatformExtImpl.physicalDown(mc, k));
            }
        }
    }

    /** Start of every tick (after the other key logic). */
    public static void tick() {
        if (!on) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.level != level) { on = false; return; }
        var o = mc.options;
        boolean typing = mc.screen != null;
        boolean w = !typing && PlatformExtImpl.physicalDown(mc, o.keyUp),   s = !typing && PlatformExtImpl.physicalDown(mc, o.keyDown);
        boolean a = !typing && PlatformExtImpl.physicalDown(mc, o.keyLeft), d = !typing && PlatformExtImpl.physicalDown(mc, o.keyRight);
        boolean up = !typing && PlatformExtImpl.physicalDown(mc, o.keyJump), down = !typing && PlatformExtImpl.physicalDown(mc, o.keyShift);
        boolean fast = !typing && PlatformExtImpl.physicalDown(mc, o.keySprint);
        // your player doesn't move or act while you fly the camera
        for (KeyMapping k : new KeyMapping[]{ o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyShift }) k.setDown(false);
        while (o.keyAttack.consumeClick()) {}
        while (o.keyUse.consumeClick()) {}
        while (o.keyPickItem.consumeClick()) {}

        double f = (w ? 1 : 0) - (s ? 1 : 0), st = (a ? 1 : 0) - (d ? 1 : 0), v = (up ? 1 : 0) - (down ? 1 : 0);
        double len = Math.sqrt(f * f + st * st);
        if (len > 0) { f /= len; st /= len; }
        double spd = speed * (fast ? 2.0 : 1.0);
        double r = Math.toRadians(yaw);
        double sin = Math.sin(r), cos = Math.cos(r);
        px = x; py = y; pz = z;
        x += (-sin * f + cos * st) * spd;
        z += ( cos * f + sin * st) * spd;
        y += v * spd;
        tickNanos = System.nanoTime();
    }

    /** Mouse movement (same scale vanilla uses for your head). */
    public static void turn(double dYaw, double dPitch) {
        yaw += (float) dYaw * 0.15f;
        pitch = Math.max(-90f, Math.min(90f, pitch + (float) dPitch * 0.15f));
    }

    /** Where the camera is this frame: last tick -> this tick, blended by time since the tick. */
    public static double[] eye() {
        double t = Math.min(1.0, Math.max(0.0, (System.nanoTime() - tickNanos) / 50_000_000.0));
        return new double[]{ px + (x - px) * t, py + (y - py) * t, pz + (z - pz) * t };
    }

    public static float yaw()   { return yaw; }
    public static float pitch() { return pitch; }
}
