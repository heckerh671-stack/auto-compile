// Zalard native logic: shared combat pieces - friends, combat events, Target Lock, target picking,
// rotation math, aim noise, the silent rotation engine and the camera aim driver.
#pragma once

namespace z {

// ================================================================ friends (case-insensitive names)
#define MAX_FRIENDS 64
static char FRIENDS[MAX_FRIENDS][17];
static int friendCount;
static bool isFriend(const char* name) {
    if (!name || !*name) return false;
    for (int i = 0; i < friendCount; i++) if (eqi(FRIENDS[i], name)) return true;
    return false;
}
static bool friendAdd(const char* n) {
    int l = len(n);
    if (l == 0 || l > 16 || isFriend(n) || friendCount >= MAX_FRIENDS) return false;
    copy(FRIENDS[friendCount++], n, 17);
    return true;
}
static bool friendRemove(const char* n) {
    for (int i = 0; i < friendCount; i++) if (eqi(FRIENDS[i], n)) {
        for (int k = i; k < friendCount - 1; k++) copy(FRIENDS[k], FRIENDS[k + 1], 17);
        friendCount--;
        return true;
    }
    return false;
}
static bool friendEnt(const Ent& e) { return e.player() && isFriend(e.name()); }

// ================================================================ combat events (fed by mixins)
namespace ev {
    static int hitSeq, lastHitId = -1; static bool lastHitPlayer, lastHitSprinting; static i64 lastHitMs;
    static int kbSeq; static double kbX, kbY, kbZ; static i64 kbMs, explosionMs;
    static bool kbFromExplosion() { i64 d = kbMs - explosionMs; return (d < 0 ? -d : d) < 300; }
}

// ================================================================ Target Lock
// The first person you hit becomes THE target; aim / TBot only go for them until they die or
// leave, you die, or you hit somebody else yourself. Module hits never change an existing lock,
// a mob never steals a player lock, friends are never locked.
namespace lock {
    static Uuid locked; static bool lockedPlayer; static bool switchClick;
    static bool (*users[8])(); static int userCount;
    static void use(bool (*f)()) { if (userCount < 8) users[userCount++] = f; }
    static bool wanted() { for (int i = 0; i < userCount; i++) if (users[i]()) return true; return false; }
    static void release() { locked = {0, 0}; lockedPlayer = false; }
    static Ent lockedFor(bool playersOnly) {
        if (locked.null()) return { nullptr };
        Ent e = entByUuid(locked);
        if (!e.ok() || e.dead()) return { nullptr };
        if (playersOnly && !e.player()) return { nullptr };
        return e;
    }
    static Ent lockedInRange(double maxDist, bool playersOnly) {
        Ent e = lockedFor(playersOnly);
        if (!e.ok() || e.invisible()) return { nullptr };
        return e.distSq(selfEye()) <= maxDist * maxDist ? e : Ent{ nullptr };
    }
    static void tick() {
        if (locked.null()) return;
        if (!wanted() || health() <= 0) { release(); return; }
        Ent e = entByUuid(locked);
        if (!e.ok() || e.dead()) release();
    }
    static bool onHit(const Ent& e, bool manual) {
        if (!e.ok() || !wanted() || e.dead() || friendEnt(e)) return false;
        Uuid u = e.uuid();
        if (!locked.null() && locked == u) return true;
        if (!locked.null() && !manual) return false;
        if (!locked.null() && lockedPlayer && !e.player()) return false;
        locked = u; lockedPlayer = e.player();
        return true;
    }
    /** First thing before vanilla reads clicks: who YOUR click is on. */
    static void observeClicks() {
        switchClick = false;
        if (!wanted() || screenOpen() || pendAtk() <= 0) return;
        Ent cross = crosshairEnt(3.0);
        if (cross.ok() && (cross.dead() || friendEnt(cross))) cross = { nullptr };
        if (!hasSilent()) { if (cross.ok()) onHit(cross, true); return; }
        Ent head = raycastEnt(silentYaw(), silentPitch(), 3.0);
        bool differs = cross.ok() && (!head.ok() || !(cross.uuid() == head.uuid()));
        if (differs && onHit(cross, true)) switchClick = true;
        else if (head.ok()) onHit(head, true);
    }
    static bool takeSwitchClick() { bool s = switchClick; switchClick = false; return s; }
}

/** A module hit (not your own click): vanilla attack + swing, and it may set the first lock. */
static void attackEnt(const Ent& e) {
    if (!e.ok()) return;
    lock::onHit(e, false);
    D(D_ATTACK, e.id());
}

// ================================================================ rotation math
namespace rm {
    /** {yaw, pitch} from `from` looking at `to`. */
    static void calc(const Vec3& from, const Vec3& to, float* out) {
        double dx = to.x - from.x, dy = to.y - from.y, dzz = to.z - from.z;
        double dist = dsqrt(dx * dx + dzz * dzz);
        out[0] = wrapDeg((float)(toDeg(datan2(dzz, dx)) - 90.0));
        out[1] = (float)-toDeg(datan2(dy, dist));
    }
    static float clampAbs(float v, float m) { return v > m ? m : v < -m ? -m : v; }
    // Snap this tick's rotation delta to the sensitivity GCD, but carry the rounding remainder into
    // the next tick (error feedback) instead of discarding it. Dropping it makes low-speed motion
    // step in coarse GCD chunks and undershoot, which the velocity controller then fights — that's
    // the jitter. Carrying it keeps the average motion continuous and steady while every emitted
    // rotation stays GCD-valid. rem may be null (one-shot callers that don't track state).
    static void gcd(float* rot, const float* last, float sens, float* rem = nullptr) {
        double f = sens * 0.6 + 0.2, g = f * f * f * 1.2;
        float dy = wrapDeg(rot[0] - last[0]) + (rem ? rem[0] : 0.f);
        float dp = (rot[1] - last[1]) + (rem ? rem[1] : 0.f);
        float qy = (float)(iround(dy / g) * g), qp = (float)(iround(dp / g) * g);
        if (rem) { rem[0] = dy - qy; rem[1] = dp - qp; }
        rot[0] = wrapDeg(last[0] + qy);
        float py = last[1] + qp;
        if (py > 90.f)  { py = 90.f;  if (rem) rem[1] = 0.f; }   // don't wind up residual at the pitch limit
        else if (py < -90.f) { py = -90.f; if (rem) rem[1] = 0.f; }
        rot[1] = py;
    }
    static Vec3 bodyAimPoint(const double* b, const Vec3& eye) {
        double cx = (b[0] + b[3]) * 0.5, cz = (b[2] + b[5]) * 0.5, h = b[4] - b[1];
        return { cx, dclamp(eye.y, b[1] + h * 0.45, b[4] - h * 0.12), cz };
    }
    static void smoothStep(const float* from, const float* to, float* vel, float maxY, float maxP, float smooth, float sens, float* rem, float* out) {
        smooth = fclamp(smooth, 0, 1);
        float dy = wrapDeg(to[0] - from[0]), dp = to[1] - from[1];
        float gain = 0.55f - 0.40f * smooth;
        float accY = maxY * (0.55f - 0.42f * smooth), accP = maxP * (0.55f - 0.42f * smooth);
        float wy = clampAbs(dy * gain, maxY), wp = clampAbs(dp * gain, maxP);
        vel[0] += clampAbs(wy - vel[0], accY); vel[1] += clampAbs(wp - vel[1], accP);
        if (fabs_(vel[0]) > fabs_(dy)) vel[0] = dy;
        if (fabs_(vel[1]) > fabs_(dp)) vel[1] = dp;
        out[0] = wrapDeg(from[0] + vel[0]); out[1] = fclamp(from[1] + vel[1], -90, 90);
        gcd(out, from, sens, rem);
    }
    static void trackStep(const float* from, const float* to, float* vel, float ffY, float ffP,
                          float maxY, float maxP, float smooth, float sens, float* rem, float* out) {
        smooth = fclamp(smooth, 0, 1);
        float dy = wrapDeg(to[0] - from[0]), dp = to[1] - from[1];
        float gain = 0.55f - 0.40f * smooth;
        float accY = maxY * (0.60f - 0.42f * smooth), accP = maxP * (0.60f - 0.42f * smooth);
        float wy = clampAbs(dy * gain + ffY, maxY), wp = clampAbs(dp * gain + ffP, maxP);
        vel[0] += clampAbs(wy - vel[0], accY + fabs_(ffY)); vel[1] += clampAbs(wp - vel[1], accP + fabs_(ffP));
        if (fabs_(vel[0]) > fabs_(dy)) vel[0] = dy;
        if (fabs_(vel[1]) > fabs_(dp)) vel[1] = dp;
        out[0] = wrapDeg(from[0] + vel[0]); out[1] = fclamp(from[1] + vel[1], -90, 90);
        gcd(out, from, sens, rem);
    }
    static bool settled(const float* a, const float* b, float th) {
        return fabs_(wrapDeg(b[0] - a[0])) < th && fabs_(b[1] - a[1]) < th;
    }
}

// ================================================================ target picking
typedef bool (*EntPred)(const Ent&);
namespace tg {
    static double angleTo(const Ent& e, const Vec3& eye, float yaw, float pitch) {
        Vec3 aim = rm::bodyAimPoint(e.box(), eye);
        float w[2]; rm::calc(eye, aim, w);
        double dy = fabs_(wrapDeg(w[0] - yaw)), dp = fabs_(w[1] - pitch);
        return dhypot(dy, dp);
    }
    /** Candidates in range: not you, alive, visible, not friends. */
    static bool candidate(const Ent& e, double maxDSq, bool playersOnly, const Vec3& eye) {
        if (e.self() || e.dead() || e.invisible()) return false;
        if (playersOnly && !e.player()) return false;
        if (friendEnt(e)) return false;
        return e.distSq(eye) <= maxDSq;
    }
    static Ent pickOne(double maxDist, bool playersOnly) {
        Ent l = lock::lockedInRange(maxDist, playersOnly);
        if (l.ok()) return l;
        snapEnts();
        Vec3 eye = selfEye();
        Ent best = { nullptr }; double bs = 1e300;
        for (int i = 0; i < entCount; i++) {
            Ent e = entAt(i);
            if (!candidate(e, maxDist * maxDist, playersOnly, eye)) continue;
            double s = dsqrt(e.distSq(eye)) + dmax(1, e.health()) * 0.1;
            if (s < bs) { bs = s; best = e; }
        }
        return best;
    }
    /** Closest to the crosshair inside a view cone; `sticky` is kept while it's in the cone. */
    static Ent pickInFov(double maxDist, bool playersOnly, float fovDeg, const Uuid& sticky, EntPred filter) {
        snapEnts();
        Vec3 eye = selfEye(); float yaw = selfYaw(), pitch = selfPitch(), half = fovDeg * 0.5f;
        Ent best = { nullptr }; double bs = 1e300;
        for (int i = 0; i < entCount; i++) {
            Ent e = entAt(i);
            if (!candidate(e, maxDist * maxDist, playersOnly, eye)) continue;
            double a = angleTo(e, eye, yaw, pitch);
            if (a > half) continue;
            if (filter && !filter(e)) continue;
            if (!sticky.null() && e.uuid() == sticky) return e;
            double s = a + dsqrt(e.distSq(eye)) * 2.0;
            if (s < bs) { bs = s; best = e; }
        }
        return best;
    }
    /** Closest by distance all around you; `sticky` kept unless someone is clearly closer. */
    static Ent pickClosest(double maxDist, bool playersOnly, const Uuid& sticky, double margin, EntPred filter) {
        snapEnts();
        Vec3 eye = selfEye();
        Ent best = { nullptr }, keep = { nullptr }; double bd = 1e300, kd = 1e300;
        for (int i = 0; i < entCount; i++) {
            Ent e = entAt(i);
            if (!candidate(e, maxDist * maxDist, playersOnly, eye)) continue;
            if (filter && !filter(e)) continue;
            double d = dsqrt(e.distSq(eye));
            if (!sticky.null() && e.uuid() == sticky) { keep = e; kd = d; }
            if (d < bd) { bd = d; best = e; }
        }
        if (keep.ok() && kd <= bd + margin) return keep;
        return best;
    }
}

/** Keeps an aim module on ONE target, holding it through short losses (grace ticks). */
struct Tracker {
    Uuid id{0, 0}; int grace = 0;
    void reset() { id = {0, 0}; grace = 0; }
    void restrictTo(const Uuid& u) { if (!id.null() && !(id == u)) reset(); }
    Ent update(const Ent& best, EntPred usable, EntPred holdable, int graceTicks = 6) {
        if (!id.null()) {
            Ent cur = entByUuid(id);
            if (cur.ok() && !cur.dead()) {
                if (usable(cur)) { grace = graceTicks; return cur; }
                if (grace > 0 && holdable(cur)) { grace--; return cur; }
            }
        }
        if (best.ok()) { id = best.uuid(); grace = graceTicks; return best; }
        reset();
        return { nullptr };
    }
};

// ================================================================ aim noise + prediction
struct AimNoise {
    Uuid forId{0, 0}; double ox = 0, oy = 0, tx = 0, ty = 0, spd = 1, spdT = 1; int retargetIn = 0;
    void reset() { forId = {0, 0}; ox = oy = tx = ty = 0; spd = spdT = 1; retargetIn = 0; }
    void tick(const Uuid& t, float amount) {
        if (t.null()) { reset(); return; }
        if (!(t == forId)) {
            forId = t;
            ox = tx = dclamp(RNG.gaussian() * 0.45, -1, 1);
            oy = ty = dclamp(RNG.gaussian() * 0.45, -1, 1);
            retargetIn = 0;
        }
        if (--retargetIn <= 0) {
            tx = dclamp(RNG.gaussian() * 0.5, -1, 1); ty = dclamp(RNG.gaussian() * 0.5, -1, 1);
            spdT = 1.0 + (RNG.nextDouble() * 2 - 1) * 0.22 * amount;
            retargetIn = 7 + RNG.nextInt(16);
        }
        ox += (tx - ox) * 0.11; oy += (ty - oy) * 0.11; spd += (spdT - spd) * 0.18;
    }
    float speedMul(float amount) const { return amount <= 0 ? 1.f : (float)spd; }
    Vec3 aimPoint(const double* b, const Vec3& eye, float amount) const {
        Vec3 base = rm::bodyAimPoint(b, eye);
        if (amount <= 0) return base;
        double h = b[4] - b[1], lo = b[1] + h * 0.40, hi = b[4] - h * 0.10;
        double halfW = dmin(b[3] - b[0], b[5] - b[2]) * 0.5;
        double dx = base.x - eye.x, dzz = base.z - eye.z, l = dsqrt(dx * dx + dzz * dzz);
        double px = 0, pz = 0;
        if (l > 1e-4) { px = -dzz / l; pz = dx / l; }
        double side = ox * halfW * 0.65 * amount;
        double y = dclamp(base.y + oy * (hi - lo) * 0.45 * amount, lo, hi);
        return { base.x + px * side, y, base.z + pz * side };
    }
};
static void predictedBox(const Ent& t, float ticks, double* out) {
    const double* b = t.box();
    for (int i = 0; i < 6; i++) out[i] = b[i];
    if (ticks <= 0) return;
    Vec3 n = t.pos(), p = t.prev();
    double vx = n.x - p.x, vy = n.y - p.y, vz = n.z - p.z;
    if (vx * vx + vy * vy + vz * vz > 4.0) return;
    vy *= 0.5;
    out[0] += vx * ticks; out[3] += vx * ticks; out[1] += vy * ticks; out[4] += vy * ticks; out[2] += vz * ticks; out[5] += vz * ticks;
}
static Vec3 predictedEye() {
    Vec3 eye = selfEye(), v = selfVel();
    if (v.x * v.x + v.z * v.z > 4.0) return eye;
    return { eye.x + v.x, eye.y + v.y * 0.5, eye.z + v.z };
}

// ================================================================ rotation requests + silent engine
namespace hub {
    struct Req { float yaw, pitch; int prio; int by; float scale; Uuid key; bool set; };
    static Req pending;
    enum { HIGHEST = 100, HIGH = 80, NORMAL = 60 };
    /** `by` = the requesting module's rotation-settings slot (see rotSettings). */
    static void request(float yaw, float pitch, int prio, int by, float scale = 1, Uuid key = {0, 0}) {
        if (!pending.set || prio >= pending.prio) pending = { yaw, pitch, prio, by, scale, key, true };
    }
    static bool consume(Req* out) { if (!pending.set) return false; *out = pending; pending.set = false; return true; }
}
/** Per-module aim settings the engine uses for whoever is steering. */
struct RotSettings { SNum speed; float smooth; bool showHead; float random; };
static RotSettings ROTS[4]; static int rotsCount;
static int rotSettings(SNum speed, float smooth, float random) {
    ROTS[rotsCount] = { speed, smooth, true, random };
    return rotsCount++;
}

namespace rot {
    static float vel[2], lastTY, lastTP, lastCamY, lastCamP, scale = 1, switchRamp = 1;
    static float gcdRem[2];   // GCD rounding remainder carried between ticks (smoothness; see rm::gcd)
    static bool hasTarget; static int cur = -1, lastBy = -1, settleCount, yieldTicks;
    static Uuid lastKey;
    static double wobble = 1, wobbleT = 1; static int wobbleIn;
    static float pSpeed = 1, pSmooth = 0, pPitch = 0.7f, pArc = 0, pJit = 1;
    static float tSpeed = 1, tSmooth = 0, tPitch = 0.7f, tArc = 0, tJit = 1;
    static int profileIn; static float jitY, jitP;

    static bool showHead() { return cur < 0 || ROTS[cur].showHead; }

    static bool throwingNow() {
        if (screenOpen()) return false;
        if (pendUse() <= 0 && !sb(SF_USE_KEY)) return false;
        const InvRec& m = mainHand();
        bool mw = !isEmpty(m) && (eq(m.id, XS("minecraft:wind_charge")) || eq(m.id, XS("minecraft:ender_pearl")));
        if (mw) return true;
        const InvRec& o = offHand();
        return isEmpty(m) && !isEmpty(o) && (eq(o.id, XS("minecraft:wind_charge")) || eq(o.id, XS("minecraft:ender_pearl")));
    }
    static void reroll(float rand, bool fresh) {
        profileIn = 30 + RNG.nextInt(50);
        if (rand <= 0) { tSpeed = 1; tSmooth = 0; tPitch = 0.7f; tArc = 0; tJit = 1; }
        else {
            tSpeed = 1 + (RNG.nextFloat() * 2 - 1) * 0.22f * rand;
            tSmooth = (RNG.nextFloat() * 2 - 1) * 0.14f * rand;
            tPitch = 0.7f + (RNG.nextFloat() * 2 - 1) * 0.18f * rand;
            tArc = (RNG.nextFloat() * 2 - 1) * rand;
            tJit = 0.6f + RNG.nextFloat() * 0.8f;
        }
        if (fresh) { pSpeed = tSpeed; pSmooth = tSmooth; pPitch = tPitch; pArc = tArc; pJit = tJit; }
    }
    static void step(const float* from, const float* to, float* out) {
        float speed = (cur < 0 ? 14.f : ROTS[cur].speed()) * (hasTarget ? scale : 1.f);
        float smooth = cur < 0 ? 0.6f : ROTS[cur].smooth;
        rm::smoothStep(from, to, vel, speed, speed * 0.65f, smooth, sensitivity(), gcdRem, out);
    }
    static void tick0() {
        if (hasSilent() && throwingNow()) yieldTicks = 4;
        if (yieldTicks > 0) {
            yieldTicks--;
            hub::Req drop; hub::consume(&drop);
            setSilent(selfYaw(), selfPitch());
            vel[0] = vel[1] = 0; gcdRem[0] = gcdRem[1] = 0; hasTarget = false; lastBy = -1; lastKey = {0, 0};
            if (yieldTicks == 0) clearSilent();
            return;
        }
        hub::Req req;
        if (!hub::consume(&req)) {
            hasTarget = false; lastBy = -1; lastKey = {0, 0};
            if (!hasSilent()) { vel[0] = vel[1] = 0; gcdRem[0] = gcdRem[1] = 0; return; }
            float from[2] = { silentYaw(), silentPitch() }, to[2] = { selfYaw(), selfPitch() };
            float camMove = fabs_(wrapDeg(to[0] - lastCamY)) + fabs_(to[1] - lastCamP);
            if (++settleCount > 3 || camMove > 2.f || rm::settled(from, to, 2.f)) {
                clearSilent(); vel[0] = vel[1] = 0; gcdRem[0] = gcdRem[1] = 0; cur = -1; return;
            }
            if (vel[0] * wrapDeg(to[0] - from[0]) < 0) vel[0] = 0;
            if (vel[1] * (to[1] - from[1]) < 0) vel[1] = 0;
            float o[2]; step(from, to, o);
            setSilent(o[0], o[1]);
            return;
        }
        bool sameModule = hasTarget && req.by == lastBy;
        bool continuing = sameModule && req.key == lastKey;
        if (sameModule && !continuing) switchRamp = 0.4f;
        else if (!sameModule) switchRamp = 1;
        else switchRamp = fmin_(1, switchRamp + 0.15f);
        float ffY = continuing ? wrapDeg(req.yaw - lastTY) : 0, ffP = continuing ? req.pitch - lastTP : 0;
        if (fabs_(ffY) > 25 || fabs_(ffP) > 25) { ffY = 0; ffP = 0; }
        lastTY = req.yaw; lastTP = req.pitch; lastBy = req.by; lastKey = req.key;
        hasTarget = true; settleCount = 0;
        if (req.by >= 0 && req.by < rotsCount) cur = req.by;
        scale = fclamp(req.scale, 0.1f, 1);

        float rand = cur < 0 ? 0 : fclamp(ROTS[cur].random, 0, 1);
        if (--wobbleIn <= 0) { wobbleT = 1.0 + (RNG.nextDouble() * 2 - 1) * 0.25 * rand; wobbleIn = 6 + RNG.nextInt(14); }
        wobble += (wobbleT - wobble) * 0.2;
        if (rand <= 0) wobble = 1;
        if (!continuing || --profileIn <= 0) reroll(rand, !continuing);
        float k = continuing ? 0.12f : 1;
        pSpeed += (tSpeed - pSpeed) * k; pSmooth += (tSmooth - pSmooth) * k; pPitch += (tPitch - pPitch) * k;
        pArc += (tArc - pArc) * k; pJit += (tJit - pJit) * k;

        float speed = (cur < 0 ? 14.f : ROTS[cur].speed()) * scale * (float)wobble * pSpeed * switchRamp;
        float smooth = fclamp((cur < 0 ? 0.6f : ROTS[cur].smooth) + pSmooth, 0, 1);
        float cap = fmax_(1, speed);
        ffY = fclamp(ffY, -cap, cap); ffP = fclamp(ffP, -cap, cap);
        float cy = hasSilent() ? silentYaw() : selfYaw(), cp = hasSilent() ? silentPitch() : selfPitch();
        float dy = wrapDeg(req.yaw - cy), dp = req.pitch - cp;
        float mag = (float)dsqrt(dy * dy + dp * dp);
        float ay = 0, ap = 0;
        if (rand > 0 && mag > 2) {
            float off = pArc * fmin_(mag * 0.16f, 7) * fmin_(1, (mag - 2) / 6);
            ay = -dp / mag * off; ap = dy / mag * off;
        }
        float amp = rand * pJit * (mag < 1.5f ? 0.5f : 1);
        jitY += ((float)RNG.gaussian() * 0.16f * amp - jitY) * 0.35f;
        jitP += ((float)RNG.gaussian() * 0.10f * amp - jitP) * 0.35f;
        if (rand <= 0) jitY = jitP = 0;
        float from[2] = { cy, cp }, to[2] = { req.yaw + ay + jitY, req.pitch + ap + jitP }, o[2];
        rm::trackStep(from, to, vel, ffY, ffP, speed, speed * pPitch, smooth, sensitivity(), gcdRem, o);
        setSilent(o[0], o[1]);
    }
    static void tick() { tick0(); lastCamY = selfYaw(); lastCamP = selfPitch(); }

    /** While the hidden head is turned, your attack clicks are judged along the head. */
    static void preInput() {
        if (!hasSilent() || screenOpen() || pendAtk() <= 0) return;
        if (lock::takeSwitchClick()) { consumeAttack(); swing(); return; }
        Ent head = raycastEnt(silentYaw(), silentPitch(), 3.0);
        if (head.ok()) { consumeAttack(); attackEnt(head); swing(); }
        else if (crosshairEnt(3.0).ok()) { consumeAttack(); swing(); }
    }
    static bool lockedOn(float tol) {
        if (!hasTarget || !hasSilent()) return false;
        float a[2] = { silentYaw(), silentPitch() }, b[2] = { lastTY, lastTP };
        return rm::settled(a, b, tol);
    }
}

// ================================================================ camera aim (visible aim assist)
// Modules set a target on the tick; the per-frame driver eases the real camera toward it.
namespace cam {
    static Uuid key; static float yaw, pitch, speed, strength = 1, fovHalf, yawRate, pitchRate, halfYaw, halfPitch;
    static bool yawOnly, ever; static i64 setAtNs, nowNs;

    static bool active() { return ever && nowNs - setAtNs < 120000000LL; }
    static void request(const Uuid& k, float y, float p, float spd, float str, float fovH, bool onlyYaw) {
        float dt = (float)((nowNs - setAtNs) / 1e9);
        bool same = ever && active() && k == key;
        if (!same) yawRate = pitchRate = 0;
        else if (dt > 0.02f && dt < 0.2f) {
            float yr = rm::clampAbs(wrapDeg(y - yaw) / dt, 240), pr = onlyYaw ? 0 : rm::clampAbs((p - pitch) / dt, 120);
            yawRate += (yr - yawRate) * 0.5f; pitchRate += (pr - pitchRate) * 0.5f;
        }
        key = k; yaw = y; pitch = p; speed = spd; strength = str; fovHalf = fovH; yawOnly = onlyYaw;
        halfYaw = halfPitch = 0; setAtNs = nowNs; ever = true;
    }
    static void requestBox(const Uuid& k, float cy, float cp, float hy, float hp, float spd, float str, float fovH, bool onlyYaw) {
        request(k, cy, cp, spd, str, fovH, onlyYaw);
        halfYaw = fmax_(0, hy); halfPitch = fmax_(0, hp);
    }
    static float since() { return fclamp((float)((nowNs - setAtNs) / 1e9), 0, 0.05f); }

    // ---- per-frame driver
    static i64 lastNs; static float carryY, carryP, velY, velP, outY, outP, yieldAmt, ramp;
    static bool haveOut, lastActive; static Uuid lastKey;
    static void reset() { carryY = carryP = velY = velP = 0; haveOut = false; yieldAmt = 0; ramp = 0; lastKey = {0, 0}; lastActive = false; }
    static float quant(float step, float lo, float hi, float g) {
        step = fclamp(step, lo, hi);
        if (g <= 0) return step;
        float q = (float)iround(step / g) * g;
        if (q > hi) q = (float)dfloor(hi / g) * g;
        if (q < lo) q = (float)dceil(lo / g) * g;
        return (q < lo || q > hi) ? 0 : q;
    }
    static void frame() {
        float dt = lastNs == 0 ? 0 : fmin_(0.05f, (float)((nowNs - lastNs) / 1e9));
        lastNs = nowNs;
        if (!active() || dt <= 0) { reset(); return; }
        if (D(D_CAM_GET) != 1 || OUTB[2] == 0) { reset(); return; }
        float curY = (float)OUTB[0], curP = (float)OUTB[1]; double sens = OUTB[3];
        float userY = 0, userP = 0;
        if (haveOut) {
            userY = wrapDeg(curY - outY); userP = curP - outP;
            if (fabs_(userY) > 45 || fabs_(userP) > 45) { velY = velP = carryY = carryP = 0; userY = userP = 0; }
        }
        if (!lastActive || !(key == lastKey)) { ramp = 0; lastKey = key; }
        lastActive = true;
        ramp = fmin_(1, ramp + dt / 0.15f);
        float spd = fclamp(speed, 0.5f, 12);
        float dyR = wrapDeg(yaw + yawRate * since() - curY);
        float dpR = yawOnly ? 0 : (pitch + pitchRate * since()) - curP;
        float hy = halfYaw, hp = halfPitch;
        float dy = (float)dsignum(dyR) * fmax_(0, fabs_(dyR) - hy), dp = (float)dsignum(dpR) * fmax_(0, fabs_(dpR) - hp);
        float err = (float)dsqrt(dy * dy + dp * dp);
        float str = fclamp(strength, 0, 1);
        if (fovHalf > 0) { float e = fmin_(1, err / fovHalf); str *= 1 - e * e; }
        float away = 0;
        if (userY * dyR < 0 && fabs_(dyR) > hy * 0.5f) away += fabs_(userY);
        if (!yawOnly && userP * dpR < 0 && fabs_(dpR) > hp * 0.5f) away += fabs_(userP);
        float push = fmin_(1, (away / dt) / 90);
        yieldAmt = fmax_(push, yieldAmt - dt / 0.25f);
        if (userY * velY < 0 && fabs_(userY) / dt > 10) velY *= 0.3f;
        if (userP * velP < 0 && fabs_(userP) / dt > 10) velP *= 0.3f;
        str *= (1 - yieldAmt) * ramp;
        float k = (2.5f + spd * 1.2f) * str, cap = (40 + spd * 30) * str;
        float shY = (float)dsignum(dy) * (float)dsqrt(fabs_(dy) * 4) * 0.5f + dy * 0.5f;
        float shP = (float)dsignum(dp) * (float)dsqrt(fabs_(dp) * 4) * 0.5f + dp * 0.5f;
        float wantY = fclamp(shY * k + yawRate * str * 0.85f, -cap, cap);
        float wantP = yawOnly ? 0 : fclamp(shP * k * 0.75f + pitchRate * str * 0.6f, -cap * 0.6f, cap * 0.6f);
        float a = 1 - (float)dexp(-dt * (8 + spd)), acc = (300 + spd * 160) * dt;
        velY += fclamp((wantY - velY) * a, -acc, acc);
        velP += fclamp((wantP - velP) * a, -acc, acc);
        if (yawOnly) velP = 0;
        float loY = fabs_(dyR) > hy ? fmin_(0, dyR) : dyR - hy, hiY = fabs_(dyR) > hy ? fmax_(0, dyR) : dyR + hy;
        float loP = fabs_(dpR) > hp ? fmin_(0, dpR) : dpR - hp, hiP = fabs_(dpR) > hp ? fmax_(0, dpR) : dpR + hp;
        float sy = velY * dt, sp = velP * dt;
        if (sy < loY || sy > hiY) { sy = fclamp(sy, loY, hiY); velY = velY * 0.5f + (sy / dt) * 0.5f; }
        if (sp < loP || sp > hiP) { sp = fclamp(sp, loP, hiP); velP = velP * 0.5f + (sp / dt) * 0.5f; }
        sy += carryY; sp += carryP;
        double f = sens * 0.6 + 0.2; float g = (float)(f * f * f * 1.2);
        float qy = quant(sy, loY, hiY, g), qp = yawOnly ? 0 : quant(sp, loP, hiP, g);
        carryY = g > 0 ? fclamp(sy - qy, -g, g) : 0;
        carryP = g > 0 && !yawOnly ? fclamp(sp - qp, -g, g) : 0;
        if (qy != 0 || qp != 0) {
            D(D_CAM_TURN, qy, qp);
            float np = fclamp(curP + qp, -90, 90);
            outY = curY + qy; outP = np;
        } else { outY = curY; outP = curP; }
        haveOut = true;
    }
}

} // namespace z
