package dev.zalard.adapter;

public final class RotationBridgeImpl {
    private static volatile boolean active;
    private static volatile float yaw, pitch;
    private static volatile float prevYaw, prevPitch;
    private static volatile float sentYaw, sentPitch;
    private static volatile boolean sentKnown;
    private static volatile boolean resync;

    // --- fade-out state ---
    private static volatile boolean fading;
    private static volatile float fadeTargetYaw, fadeTargetPitch;
    private static volatile int fadeTicksLeft;
    // Grim's human cap is ~45 deg/tick yaw, ~35 deg/tick pitch. Stay under.
    private static final float MAX_YAW_STEP = 42.0f;
    private static final float MAX_PITCH_STEP = 28.0f;

    private RotationBridgeImpl() {}

    public static void set(float y, float p) {
        y = wrap(y);
        p = clampPitch(p);
        if (active) { prevYaw = yaw; prevPitch = pitch; }
        else        { prevYaw = y;   prevPitch = p; }
        yaw = y; pitch = p;
        active = true;
        fading = false;
    }

    /** Hard clear -- only use on world unload / disconnect. */
    public static void clear() {
        if (active) resync = true;
        active = false;
        fading = false;
    }

    /** Preferred clear: fade silent rotation back to the real one. */
    public static void clear(float realYaw, float realPitch) {
        if (!active) { resync = true; return; }
        fadeTargetYaw = wrap(realYaw);
        fadeTargetPitch = clampPitch(realPitch);
        fading = true;
        fadeTicksLeft = 6;
    }

    /** Must run once per tick BEFORE vanilla computes its rotation delta. */
    public static void tickFade() {
        tickFade0();
    }

    /**
     * Same, but fades toward where your camera is NOW. The hand-back target used to be frozen at the
     * moment silent aim let go, so if you kept moving your mouse during the fade the head landed on
     * an old angle and then jumped to your real one when the fade ended.
     */
    public static void tickFade(float liveYaw, float livePitch) {
        if (fading) { fadeTargetYaw = wrap(liveYaw); fadeTargetPitch = clampPitch(livePitch); }
        tickFade0();
    }

    private static void tickFade0() {
        if (!fading || !active) return;
        float dy = wrap(fadeTargetYaw - yaw);
        float dp = fadeTargetPitch - pitch;
        float sy = clamp(dy, -MAX_YAW_STEP, MAX_YAW_STEP);
        float sp = clamp(dp, -MAX_PITCH_STEP, MAX_PITCH_STEP);
        prevYaw = yaw; prevPitch = pitch;
        yaw = wrap(yaw + sy);
        pitch = clampPitch(pitch + sp);
        fadeTicksLeft--;
        boolean arrived = Math.abs(dy) <= MAX_YAW_STEP && Math.abs(dp) <= MAX_PITCH_STEP;
        if (arrived || fadeTicksLeft <= 0) {
            fading = false;
            active = false;
            resync = true;
        }
    }

    public static void noteSent(float y, float p) {
        sentYaw = wrap(y); sentPitch = p; sentKnown = true;
    }

    public static boolean needsSend() {
        if (!active) return false;
        if (!sentKnown) return true;
        return Math.abs(wrap(yaw - sentYaw)) > 0.001f
            || Math.abs(pitch - sentPitch) > 0.001f;
    }

    /** True iff rewriting with current yaw/pitch would duplicate last sent. */
    public static boolean rewriteDuplicatesLast() {
        if (!sentKnown) return false;
        return Math.abs(wrap(yaw - sentYaw)) <= 0.001f
            && Math.abs(pitch - sentPitch) <= 0.001f;
    }

    // Vanilla never wraps yaw (it can be 725.3 after a few spins). Sending a wrapped silent yaw
    // (e.g. 5.3) right after 725.3 looks like a 720 degree snap to the server.
    private static volatile float rawSent;
    private static volatile boolean rawKnown;
    public static void noteRawSent(float y) { rawSent = y; rawKnown = true; }
    public static boolean rawKnown()        { return rawKnown; }
    public static float rawSent()           { return rawSent; }
    /** `wrappedYaw` expressed next to the last sent raw yaw (same angle, no multi-turn jump). */
    public static float continuous(float wrappedYaw, float fallbackBase) {
        float base = rawKnown ? rawSent : fallbackBase;
        return base + wrap(wrappedYaw - base);
    }

    public static boolean takeResync() { boolean r = resync; resync = false; return r; }
    public static boolean active() { return active; }
    public static boolean fading() { return fading; }
    public static float yaw()   { return yaw; }
    public static float pitch() { return pitch; }

    public static float yaw(float partial) {
        return wrap(prevYaw + wrap(yaw - prevYaw) * partial);
    }
    public static float pitch(float partial) {
        return prevPitch + (pitch - prevPitch) * partial;
    }

    private static float clampPitch(float p) { return p < -90f ? -90f : (p > 90f ? 90f : p); }
    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static float wrap(float y) {
        y = y % 360f;
        if (y >= 180f) y -= 360f;
        else if (y < -180f) y += 360f;
        return y;
    }
}
