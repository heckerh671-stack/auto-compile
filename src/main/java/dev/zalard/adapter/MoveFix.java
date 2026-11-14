package dev.zalard.adapter;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Movement fix for Silent Aim (Grim "Simulation").
 *
 * Grim re-simulates every tick of your movement with every key combo vanilla could press, using
 * the yaw from your movement packet (the hidden head). While the head is turned, your client must
 * therefore move EXACTLY like a vanilla client whose camera is the head.
 *
 * How (v2 — the first version rebuilt the input vector by hand and got its length a couple of
 * percent off for some combos, which Grim flagged as constant ~0.001-0.005 offsets):
 *  - at the start of each tick we press the W/A/S/D combo that, relative to the head, points
 *    closest to where your real keys point relative to your camera (<= 22.5 deg off). Vanilla then
 *    builds the input vector, sneak/item slowdown and sprint start/stop itself, bit-for-bit;
 *  - the movement math uses the head yaw (MoveFixEntityMixin);
 *  - the sprint-jump boost goes toward the head (MoveFixMixin).
 * When the head hands back to the camera, the keys go back to what you're physically holding.
 */
public final class MoveFix {
    private MoveFix() {}

    private static final int[][] DIRS = {                // {strafe (+ = left), forward}
        { 0, 1}, { 0,-1}, { 1, 0}, {-1, 0}, { 1, 1}, {-1, 1}, { 1,-1}, {-1,-1}
    };
    private static boolean remapped;

    public static boolean applies(Object entity) {
        return entity == Minecraft.getInstance().player && RotationBridgeImpl.active();
    }

    /** Start of tick, after the rotation engine has set this tick's head yaw. */
    public static void tickKeys() {
        var mc = Minecraft.getInstance();
        var pl = mc.player;
        if (pl == null || mc.options == null || mc.screen != null) { remapped = false; return; }
        var o = mc.options;
        boolean w = PlatformExtImpl.physicalDown(mc, o.keyUp) && !forwardSuppressed();
        boolean s = PlatformExtImpl.physicalDown(mc, o.keyDown) || backForced();
        boolean a = PlatformExtImpl.physicalDown(mc, o.keyLeft);
        boolean d = PlatformExtImpl.physicalDown(mc, o.keyRight);

        if (!RotationBridgeImpl.active()) {
            if (remapped) { o.keyUp.setDown(w); o.keyDown.setDown(s); o.keyLeft.setDown(a); o.keyRight.setDown(d); }
            remapped = false;
            return;
        }
        int fz = (w ? 1 : 0) - (s ? 1 : 0), fx = (a ? 1 : 0) - (d ? 1 : 0);
        if (fz == 0 && fx == 0) {
            o.keyUp.setDown(false); o.keyDown.setDown(false); o.keyLeft.setDown(false); o.keyRight.setDown(false);
            remapped = true;
            return;
        }
        double n0 = Math.sqrt(fx * fx + fz * fz);
        double[] want = world(fx / n0, fz / n0, pl.getYRot());
        float head = RotationBridgeImpl.yaw();
        int bx = 0, bz = 1; double best = -2;
        for (int[] dir : DIRS) {
            double n = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1]);
            double[] v = world(dir[0] / n, dir[1] / n, head);
            double dot = v[0] * want[0] + v[1] * want[1];
            if (dot > best) { best = dot; bx = dir[0]; bz = dir[1]; }
        }
        o.keyUp.setDown(bz > 0); o.keyDown.setDown(bz < 0);
        o.keyLeft.setDown(bx > 0); o.keyRight.setDown(bx < 0);
        remapped = true;
    }

    /** Movement Reset's W-tap / sprint reset is holding W up right now. */
    private static boolean forwardSuppressed() {
        return dev.zalard.nat.Z.is(dev.zalard.nat.Z.Q_SUPPRESS_FWD);
    }

    /** Movement Reset's S-Tap is holding S down right now. */
    private static boolean backForced() {
        return dev.zalard.nat.Z.is(dev.zalard.nat.Z.Q_FORCE_BACK);
    }

    /** Right after vanilla's jump: its sprint boost went toward the camera; send it toward the head. */
    public static void fixJump(LivingEntity self) {
        if (!self.isSprinting()) return;                       // vanilla added no boost
        float cam  = self.getYRot() * ((float) Math.PI / 180f);
        float head = RotationBridgeImpl.yaw() * ((float) Math.PI / 180f);
        self.addDeltaMovement(new Vec3((-Mth.sin(head) + Mth.sin(cam)) * 0.2, 0.0,
                                       ( Mth.cos(head) - Mth.cos(cam)) * 0.2));
    }

    /** Same rotation as Entity.getInputVector. */
    private static double[] world(double x, double z, float yaw) {
        float sn = Mth.sin(yaw * ((float) Math.PI / 180f)), cs = Mth.cos(yaw * ((float) Math.PI / 180f));
        return new double[]{ x * cs - z * sn, z * cs + x * sn };
    }
}
