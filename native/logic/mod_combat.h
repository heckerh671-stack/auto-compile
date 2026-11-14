// Zalard native logic: combat modules (TBot, Aim Assist + Silent, Mace Swap, Shield Breaker).
#pragma once

namespace z {

// ================================================================ Mace Swap (+ Stun Slam)
// Presses your best mace's hotbar key right before a smash hit; vanilla reads number keys before
// clicks, so a click in the same tick swings the mace. Stun Slam: axe hit on a raised shield
// first, the mace hit the next tick.
namespace mace {
    static int M = -1;
    static SNum minFall; static SBool autoHit, stunSlam, breach; static SRange backDelay;
    static const bool PLAYERS_ONLY = false, SWAP_BACK = true;
    static int originalSlot = -1, swapBackIn = -1, hitSeen; static bool slamNext;

    static bool smashFall() {
        float need = M >= 0 ? minFall() : 1.6f;
        if (onGround() || sb(SF_GLIDING)) return false;
        return fallDist() > fmax_(1.55f, need);
    }
    static int bestMace() {
        // Prefer Breach puts the armor-penetration mace first (better into armored targets); otherwise
        // Density leads for raw fall-damage. Wind Burst is last either way (it's for chaining, not kills).
        static const int densityFirst[3] = { EN_DENSITY, EN_BREACH, EN_WIND_BURST };
        static const int breachFirst[3]  = { EN_BREACH, EN_DENSITY, EN_WIND_BURST };
        const int* order = (M >= 0 && breach()) ? breachFirst : densityFirst;
        for (int e = 0; e < 3; e++)
            for (int i = 0; i < 9; i++) { const InvRec& it = item(i); if (!isEmpty(it) && eq(it.id, XS("minecraft:mace")) && it.ench[order[e]] > 0) return i; }
        return findHotbar(XS("minecraft:mace"));
    }
    /** An attack right now would be a mace smash (holding one, or Mace Swap will pull one). */
    static bool smashReady() {
        if (!smashFall()) return false;
        if (isItem(selSlot(), XS("minecraft:mace"))) return true;
        return isOn(M) && bestMace() >= 0;
    }
    static bool acceptable(const Ent& e) {
        if (PLAYERS_ONLY && !e.player()) return false;
        if (friendEnt(e)) return false;
        return lock::locked.null() || !lock::wanted() || lock::locked == e.uuid();
    }
    static void restore() {
        if (SWAP_BACK && originalSlot >= 0 && selSlot() != originalSlot) pressHotbar(originalSlot);
        originalSlot = -1; swapBackIn = -1;
    }
    static void tick() {
        if (ev::hitSeq != hitSeen) {
            hitSeen = ev::hitSeq;
            if (originalSlot >= 0 && SWAP_BACK) swapBackIn = imax(1, backDelay.pickInt());
        }
        if (swapBackIn >= 0 && swapBackIn-- == 0) restore();
        if (originalSlot >= 0 && swapBackIn < 0 && (onGround() || !smashFall())) restore();
    }
    static void pre() {
        if (screenOpen() || usingItem() || !smashFall()) { slamNext = false; return; }
        Ent t = hasSilent() ? raycastEnt(silentYaw(), silentPitch(), 3.0) : crosshairEnt(3.0);
        if (!t.ok() || t.dead() || !acceptable(t)) { slamNext = false; return; }
        int m = bestMace();
        if (m < 0) return;
        int cur = selSlot();
        if (stunSlam() && !slamNext && t.player() && t.shield()) {
            int axe = findHotbarSuffix("_axe");
            if (axe >= 0 && pendAtk() <= 0) {
                if (originalSlot < 0) originalSlot = cur;
                if (cur != axe) pressHotbar(axe);
                clickAttack();
                slamNext = true; swapBackIn = -1;
                return;
            }
        }
        if (cur != m) { if (originalSlot < 0) originalSlot = cur; pressHotbar(m); swapBackIn = -1; }
        if ((autoHit() || slamNext) && pendAtk() <= 0) clickAttack();
        slamNext = false;
    }
    static void disable() { if (originalSlot >= 0) restore(); swapBackIn = -1; originalSlot = -1; }
    static void reg() {
        M = mod(XS("Mace Swap"), XS("Combat"));
        minFall = sNum(XS("Min Fall"), 1.6f, 1.6f, 20, "blocks");
        autoHit = sBool(XS("Auto Hit"), false);
        stunSlam = sBool(XS("Stun Slam"), true);
        breach = sBool(XS("Prefer Breach"), false);
        backDelay = sRange(XS("Swap Back Delay"), 1, 2, 1, 10, "ticks");
        LG[M].tick = tick; LG[M].pre = pre; LG[M].disable = disable;
    }
}

// ================================================================ TBot
// Crosshair on a target + weapon charged -> presses attack for you (vanilla does the hit).
// Crit Timing: in the air over a target it lets go of sprint a tick early and hits on the first
// falling tick; it adapts to holding jump and to tap-jumping.
namespace tbot {
    static int M = -1;
    static SRange delay; static SBool critTiming, players, lockTarget, simClicks;
    static const float MIN_CHARGE = 0.95f; static const bool SKIP_INVIS = true;
    static i64 fireAt = -1, lastOwnJump = -1000, lastJumpKey = -1000, jumpTicks[3];
    static int airTicks, groundTicks; static bool wasGround = true, ownJump, serverSprint, holdingSprint;

    static bool holdsSprint() { return isOn(M) && holdingSprint; }
    static bool lockWanted() { return isOn(M) && lockTarget(); }
    static Ent target() { return hasSilent() ? raycastEnt(silentYaw(), silentPitch(), 3.0) : crosshairEnt(3.0); }
    static bool valid(const Ent& t) {
        if (!t.ok() || t.dead()) return false;
        if (players() && !t.player()) return false;
        if (SKIP_INVIS && t.invisible()) return false;
        if (friendEnt(t)) return false;
        if (lockTarget()) { Ent l = lock::lockedFor(players()); if (l.ok() && !(l.uuid() == t.uuid())) return false; }
        return true;
    }
    static void tick() {
        bool ground = onGround();
        if (sb(SF_JUMP_KEY)) lastJumpKey = TICKS;
        serverSprint = sb(SF_SPRINTING);
        if (ground) { ownJump = false; airTicks = 0; }
        else if (wasGround) {
            ownJump = SF[SF_VY] > 0.3 && TICKS - lastJumpKey <= 2;
            if (ownJump) { lastOwnJump = TICKS; jumpTicks[0] = jumpTicks[1]; jumpTicks[1] = jumpTicks[2]; jumpTicks[2] = TICKS; }
        }
        wasGround = ground;
        groundTicks = ground ? groundTicks + 1 : 0;
        bool want = false;
        if (critTiming() && !ground && !lookSteered() && !mace::smashReady() && strength() >= MIN_CHARGE - 0.2f) want = valid(target());
        if (want) {
            if (sb(SF_SPRINTING)) { D(D_STOP_SPRINT); SF[SF_SPRINTING] = 0; }
            D(D_SET_SPRINT, 0);
            holdingSprint = true;
        } else if (holdingSprint && (ground || lookSteered())) {
            D(D_RESTORE_SPRINT);
            holdingSprint = false;
        }
    }
    static void pre() {
        if (!onGround()) airTicks++;
        if (screenOpen() || usingItem() || pendAtk() > 0) { fireAt = -1; return; }
        bool smash = mace::smashReady();
        if (!smash && strength() < MIN_CHARGE) { fireAt = -1; return; }
        bool silent = hasSilent();
        Ent t = target();
        if (!valid(t)) { fireAt = -1; return; }
        if (critTiming() && !smash && onGround() && !lookSteered()) {
            if (sb(SF_JUMP_KEY) && TICKS - lastOwnJump < 30) { fireAt = -1; return; }
            bool rhythm = TICKS - jumpTicks[1] < 40 && TICKS - lastOwnJump < 25;
            if (rhythm && groundTicks < 6) { fireAt = -1; return; }
        }
        if (critTiming() && !smash && !onGround() && !lookSteered()) {
            bool critNow = fallDist() > 0 && !serverSprint;
            if (!critNow) {
                double vy = SF[SF_VY];
                int untilFall = vy > 0 ? (int)dceil(vy / 0.08) + 1 : 1;
                if (serverSprint) untilFall = imax(untilFall, 1);
                int maxWait = ownJump ? 12 : 2;
                if (untilFall <= maxWait && airTicks < 14) return;
            }
        }
        if (fireAt < 0) fireAt = TICKS + delay.pickInt();
        if (TICKS < fireAt) return;
        fireAt = -1;
        // Simulate Clicks: route the hit through vanilla's attack-click path (startAttack handles the
        // swing, sweep, cooldown and bumps the click counter) instead of a direct attack packet — each
        // hit looks like a real left click. With silent aim we first point the crosshair at the target.
        if (silent && !simClicks()) { attackEnt(t); swing(); }
        else {
            if (silent) D(D_SET_HIT_TARGET, t.id());
            clickAttack(); lock::onHit(t, false);
        }
    }
    static void disable() {
        airTicks = 0; fireAt = -1;
        if (holdingSprint) { D(D_RESTORE_SPRINT); holdingSprint = false; }
    }
    static void reg() {
        M = mod(XS("TBot"), XS("Combat"));
        delay = sRange(XS("Delay"), 0, 0, 0, 10, "ticks");
        critTiming = sBool(XS("Crit Timing"), true);
        players = sBool(XS("Players Only"), true);
        lockTarget = sBool(XS("Target Lock"), true);
        simClicks = sBool(XS("Simulate Clicks"), false);
        lock::use(lockWanted);
        LG[M].tick = tick; LG[M].pre = pre; LG[M].disable = disable;
    }
}

// ================================================================ Aim Assist (Camera) + Silent Aim (Silent)
namespace aim {
    static int M = -1;
    static SEnum mode;
    // camera
    static SNum reach, fov, speed; static SBool vertical, onClick, lockTarget;
    static const bool PLAYERS = true, LOS = true; static const float PREDICTION = 1, RANDOM = 0.35f;
    // silent
    static SEnum tMode; static SNum sReach, sFov, rotSpeed; static SBool sAutoHit, sLock; static SRange hitDelay;
    static const float S_PRED = 1, LOCK_TOL = 3, S_RANDOM = 0.35f; static const bool S_PLAYERS = true, S_LOS = true, S_SKIP_INVIS = true;
    static int ROT = -1;

    static bool camMode() { return mode.idx() == 0; }
    static bool silentMode() { return mode.idx() == 1; }
    static bool closest() { return tMode.idx() == 1; }
    static bool visCam() { return camMode(); }
    static bool visSilent() { return silentMode(); }
    static bool visSilentFov() { return silentMode() && !closest(); }
    static bool lockCam() { return isOn(M) && camMode() && lockTarget(); }
    static bool lockSilent() { return isOn(M) && silentMode() && sLock(); }

    // ---- camera
    static AimNoise noise; static Tracker tracker; static Uuid target; static i64 lastClick;
    static void clearCam() { target = {0, 0}; noise.reset(); tracker.reset(); }
    static bool usable(const Ent& e) {
        if (!e.ok() || e.dead()) return false;
        if (PLAYERS && !e.player()) return false;
        Vec3 eye = selfEye(); double r = reach();
        if (e.distSq(eye) > r * r) return false;
        if (tg::angleTo(e, eye, selfYaw(), selfPitch()) > fov() * 0.5) return false;
        return !LOS || lineOfSight(eye, e.eye());
    }
    static bool holdable(const Ent& e) {
        if (!e.ok() || e.dead()) return false;
        double r = reach() + 1.0;
        return e.distSq(selfEye()) <= r * r;
    }
    static void tickCam() {
        if (screenOpen()) { clearCam(); return; }
        if (onClick()) {
            if (keyDown(-100)) lastClick = NOW;
            if (NOW - lastClick > 400) { clearCam(); return; }
        }
        Ent best;
        Ent l = lockTarget() ? lock::lockedFor(PLAYERS) : Ent{ nullptr };
        if (l.ok()) { tracker.restrictTo(l.uuid()); best = usable(l) ? l : Ent{ nullptr }; }
        else best = tg::pickInFov(reach(), PLAYERS, fov(), target, usable);
        Ent t = tracker.update(best, usable, holdable);
        if (!t.ok()) { clearCam(); return; }
        target = t.uuid();
        noise.tick(t.uuid(), RANDOM);
        Vec3 eye = predictedEye();
        double b[6]; predictedBox(t, PREDICTION, b);
        double cx = (b[0] + b[3]) * 0.5, cz = (b[2] + b[5]) * 0.5, h = b[4] - b[1];
        double top = b[4] - h * 0.08, low = b[1] + h * 0.25, cy = (top + low) * 0.5;
        float c[2]; rm::calc(eye, { cx, cy, cz }, c);
        double dist = dmax(0.5, dsqrt((cx - eye.x) * (cx - eye.x) + (cz - eye.z) * (cz - eye.z)));
        float halfYaw = (float)toDeg(datan(((b[3] - b[0]) * 0.5 * 0.7) / dist));
        float tp[2], bp[2]; rm::calc(eye, { cx, top, cz }, tp); rm::calc(eye, { cx, low, cz }, bp);
        float halfPitch = fabs_(bp[1] - tp[1]) * 0.5f;
        bool vert = vertical();
        cam::requestBox(t.uuid(), c[0], vert ? c[1] : 0, halfYaw, halfPitch, speed() * noise.speedMul(RANDOM), 1, fov() * 0.5f, !vert);
    }

    // ---- silent
    static AimNoise sNoise; static Tracker sTracker; static Uuid sTarget; static bool sUsable; static int lockedTicks, needTicks = -1;
    static void clearSilent() { sTarget = {0, 0}; sUsable = false; lockedTicks = 0; sNoise.reset(); sTracker.reset(); }
    static bool sUsableFn(const Ent& e) {
        if (!e.ok() || e.dead()) return false;
        if (S_PLAYERS && !e.player()) return false;
        if (S_SKIP_INVIS && e.invisible()) return false;
        Vec3 eye = selfEye(); double r = sReach();
        if (e.distSq(eye) > r * r) return false;
        if (!closest() && tg::angleTo(e, eye, selfYaw(), selfPitch()) > sFov() * 0.5) return false;
        return !S_LOS || lineOfSight(eye, e.eye());
    }
    static bool sHoldable(const Ent& e) {
        if (!e.ok() || e.dead()) return false;
        double r = sReach() + 1.0;
        return e.distSq(selfEye()) <= r * r;
    }
    static void tickSilent() {
        if (screenOpen() || sb(SF_GLIDING) || lookSteered()) { clearSilent(); return; }
        bool cl = closest();
        Ent best;
        Ent l = sLock() ? lock::lockedFor(S_PLAYERS) : Ent{ nullptr };
        if (l.ok()) { sTracker.restrictTo(l.uuid()); best = sUsableFn(l) ? l : Ent{ nullptr }; }
        else best = cl ? tg::pickClosest(sReach(), S_PLAYERS, sTarget, 0.75, sUsableFn)
                       : tg::pickInFov(sReach(), S_PLAYERS, sFov(), sTarget, sUsableFn);
        Ent t = sTracker.update(best, sUsableFn, sHoldable);
        if (!t.ok()) { clearSilent(); return; }
        if (sTarget.null() || !(t.uuid() == sTarget)) lockedTicks = 0;
        sTarget = t.uuid();
        sUsable = sUsableFn(t);
        sNoise.tick(t.uuid(), S_RANDOM);
        Vec3 eye = predictedEye();
        double b[6]; predictedBox(t, S_PRED, b);
        float want[2]; rm::calc(eye, sNoise.aimPoint(b, eye, S_RANDOM), want);
        double angle = tg::angleTo(t, selfEye(), selfYaw(), selfPitch());
        float scale;
        if (cl) scale = (float)(1.0 - 0.35 * dmin(1.0, angle / 180.0));
        else {
            double half = dmax(1.0, sFov() * 0.5);
            double byA = 1.0 - 0.55 * dmin(1.0, angle / half);
            double byD = 1.0 - 0.20 * dmin(1.0, dsqrt(t.distSq(eye)) / sReach());
            scale = (float)dmax(0.35, byA * byD);
        }
        hub::request(want[0], want[1], hub::HIGH, ROT, scale, t.uuid());
    }
    static void preSilent() {
        if (sTarget.null()) { lockedTicks = 0; return; }
        if (rot::lockedOn(LOCK_TOL)) lockedTicks++;
        else { lockedTicks = 0; needTicks = -1; }
        if (needTicks < 0) needTicks = imax(1, hitDelay.pickInt());
        Ent t = entByUuid(sTarget);
        if (!t.ok() || t.dead() || usingItem()) return;
        bool inReach = t.distSq(selfEye()) <= 9.0;
        if (!sAutoHit() || !sUsable || lockedTicks < needTicks || !inReach) return;
        if (strength() < 0.95f) return;
        attackEnt(t); swing();
        lockedTicks = 0; needTicks = -1;
    }

    static int lastMode = -1;
    static void tick() {
        int m = mode.idx();
        if (m != lastMode) { clearCam(); clearSilent(); lastMode = m; }
        if (camMode()) tickCam(); else tickSilent();
    }
    static void pre() { if (silentMode()) preSilent(); }
    static void disable() { clearCam(); clearSilent(); lastMode = -1; }
    static void reg() {
        const char* MODES[] = { XS("Camera"), XS("Silent") };
        const char* TMODES[] = { XS("Crosshair"), XS("Closest") };
        M = mod(XS("Aim Assist"), XS("Combat"));
        mode = sEnum(XS("Mode"), 0, MODES, 2);
        reach = vis(sNum(XS("Reach"), 4, 2, 6, "blocks"), visCam);
        fov = vis(sNum(XS("FOV"), 70, 10, 180, "deg"), visCam);
        speed = vis(sNum(XS("Speed"), 4, 1, 10), visCam);
        vertical = vis(sBool(XS("Vertical"), true), visCam);
        onClick = vis(sBool(XS("Only While Clicking"), false), visCam);
        lockTarget = vis(sBool(XS("Target Lock"), true), visCam);
        rotSpeed = vis(sNum(XS("Rotation Speed"), 18, 1, 40, "deg/t"), visSilent);
        tMode = vis(sEnum(XS("Target Mode"), 0, TMODES, 2), visSilent);
        sReach = vis(sNum(XS("Search Range"), 4.5f, 2, 8, "blocks"), visSilent);
        sFov = vis(sNum(XS("Silent FOV"), 90, 10, 360, "deg"), visSilentFov);
        sAutoHit = vis(sBool(XS("Silent Auto Hit"), false), visSilent);
        sLock = vis(sBool(XS("Silent Target Lock"), true), visSilent);
        hitDelay = vis(sRange(XS("Hit Delay"), 1, 3, 1, 10, "ticks"), visSilent);
        ROT = rotSettings(rotSpeed, 0.6f, S_RANDOM);
        lock::use(lockCam); lock::use(lockSilent);
        LG[M].tick = tick; LG[M].pre = pre; LG[M].disable = disable;
    }
}

// ================================================================ Shield Breaker
// Axe hit on a raised shield, only once they're really hittable (crosshair / hidden head on them
// inside 3 blocks). One hit per raised shield.
namespace shield {
    static int M = -1;
    static SRange leadIn, backDelay; static SBool autoHit;
    static const bool FACING = true;
    static int originalSlot = -1, backIn = -1, raisedTicks; static i64 readyAt = -1, lastHitTick = -1000;
    static Uuid lastTarget, raisedFor; static bool sawDown;

    static bool shielded(const Ent& t) {
        if (!t.ok() || t.dead() || !t.player() || friendEnt(t) || !t.shield()) return false;
        if (!FACING) return true;
        Vec3 tp = t.pos(), me = selfPos();
        double meYaw = toDeg(datan2(-(me.x - tp.x), me.z - tp.z));
        double diff = dfmod(meYaw - t.yaw() + 540, 360) - 180;
        return dabs(diff) <= 95;
    }
    static void tick() {
        if (backIn >= 0 && backIn-- == 0) {
            if (originalSlot >= 0 && selSlot() != originalSlot) pressHotbar(originalSlot);
            originalSlot = -1;
        }
    }
    static void pre() {
        if (screenOpen() || usingItem()) { readyAt = -1; return; }
        Ent t = hasSilent() ? raycastEnt(silentYaw(), silentPitch(), 3.0) : crosshairEnt(3.0);
        if (!lastTarget.null()) {
            Ent lt = entByUuid(lastTarget);
            if (!(lt.ok() && lt.shield())) sawDown = true;
        }
        if (!shielded(t)) { readyAt = -1; raisedTicks = 0; return; }
        if (!(t.uuid() == raisedFor)) { raisedFor = t.uuid(); raisedTicks = 0; }
        if (++raisedTicks < 2) return;
        if (t.uuid() == lastTarget && !sawDown && TICKS - lastHitTick < 30) return;
        int axe = findHotbarSuffix("_axe");
        if (axe < 0) return;
        if (readyAt < 0) readyAt = TICKS + leadIn.pickInt();
        if (TICKS < readyAt) return;
        int cur = selSlot();
        if (cur != axe) { if (originalSlot < 0) originalSlot = cur; pressHotbar(axe); }
        if (autoHit() && pendAtk() <= 0) clickAttack();
        backIn = imax(1, backDelay.pickInt());
        readyAt = -1;
        lastTarget = t.uuid(); lastHitTick = TICKS; sawDown = false;
    }
    static void disable() { originalSlot = -1; backIn = -1; readyAt = -1; }
    static void reg() {
        M = mod(XS("Shield Breaker"), XS("Combat"));
        leadIn = sRange(XS("Delay"), 0, 1, 0, 10, "ticks");
        backDelay = sRange(XS("Swap Back Delay"), 2, 4, 1, 40, "ticks");
        autoHit = sBool(XS("Auto Hit"), true);
        LG[M].tick = tick; LG[M].pre = pre; LG[M].disable = disable;
    }
}

} // namespace z
