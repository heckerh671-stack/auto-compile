// Zalard native logic: Crystal, Anchor, Cart, Combo Assist, Velocity, Lunge.
#pragma once

namespace z {

// ================================================================ Crystal
// Hold right click with crystals: crosshair on obsidian / bedrock -> a right click places one;
// crosshair on a crystal -> a left click pops it. Only your own clicks, faster.
namespace crystal {
    static int M = -1;
    static SRange placeDelay, breakDelay; static SBool needClick;
    static i64 nextPlace, nextBreak;
    static bool crystalHand() {
        return isItem(selSlot(), XS("minecraft:end_crystal")) || isItem(36, XS("minecraft:end_crystal"));
    }
    static bool crystalAt(int x, int y, int zz) {
        int n = (int)D(D_CRYSTALS_NEAR, 7.0);
        for (int i = 0; i < n; i++)
            if (dfloor(OUTB[i * 4 + 1]) == x && dfloor(OUTB[i * 4 + 2]) == y + 1 && dfloor(OUTB[i * 4 + 3]) == zz) return true;
        return false;
    }
    static void pre() {
        if (screenOpen() || usingItem()) return;
        if (needClick() && !sb(SF_USE_KEY)) return;
        if (!crystalHand()) return;
        int cid = (int)D(D_CROSSHAIR_CRYSTAL);
        if (cid >= 0) {
            if (TICKS >= nextBreak && pendAtk() <= 0) { clickAttack(); nextBreak = TICKS + breakDelay.pickInt(); }
            return;
        }
        if (TICKS < nextPlace) return;
        double h[7];
        if (!crosshairBlock(h)) return;
        int x = (int)h[0], y = (int)h[1], zz = (int)h[2];
        char id[64];
        if (!blockId(x, y, zz, id, 64) || (!eq(id, XS("minecraft:obsidian")) && !eq(id, XS("minecraft:bedrock")))) return;
        if (!(blockFlags(x, y + 1, zz) & BF_AIR)) return;
        if (crystalAt(x, y, zz)) return;
        if (pendUse() > 0) return;
        clickUse();
        nextPlace = TICKS + placeDelay.pickInt();
        if (nextBreak > TICKS + 1) nextBreak = TICKS + 1;
    }
    static void disable() { nextPlace = nextBreak = 0; }
    static void reg() {
        M = mod(XS("Crystal"), XS("Combat"));
        placeDelay = sRange(XS("Place Delay"), 0, 1, 0, 10, "ticks");
        breakDelay = sRange(XS("Break Delay"), 0, 1, 0, 10, "ticks");
        needClick = sBool(XS("Hold Right Click"), true);
        LG[M].pre = pre; LG[M].disable = disable;
    }
}

// ================================================================ Anchor
// You place the anchor; it swaps to glowstone, charges it, swaps back and blows it up.
namespace anchor {
    static int M = -1;
    static SBool autoAim, silent, safe; static SNum aimSpeed; static SRange swapDelay, detDelay;
    static const bool ONLY_PLACED = true, DETONATE = true; static const double REACH = 4.5;
    enum St { IDLE, CHARGE, SWAP_BACK, DETONATE_ };
    static St st = IDLE;
    static i64 actAt, startedAt, cooldownUntil, armedAt, keyTick = -1;
    static int ax, ay, az, anchorSlot = -1, face, pendX, pendY, pendZ;
    static double hx, hy, hz; static bool havePending, holdingUse;

    static i64 delayMs(const SRange& r) { i64 ms = r.pickTicksMs(); return ms <= 0 ? 0 : ms - 10; }
    static bool clickMode() { return !(autoAim() && silent()); }
    static bool holds(const char* id) {
        if (isItem(selSlot(), id)) return true;
        return clickMode() && keyTick == TICKS;
    }
    static void offset(int x, int y, int zz, int f, int* o) {
        o[0] = x; o[1] = y; o[2] = zz;
        switch (f) { case UP: o[1]++; break; case DOWN: o[1]--; break; case NORTH: o[2]--; break;
                     case SOUTH: o[2]++; break; case WEST: o[0]--; break; case EAST: o[0]++; break; }
    }
    static int charge(int x, int y, int zz) { return (int)D(D_ANCHOR_CHARGE, x, y, zz); }
    /** Turns onto the anchor; the ray hit once the look is really on it. */
    static bool aimHit(int x, int y, int zz, double* h) {
        Vec3 eye = selfEye(), c = { x + 0.5, y + 0.5, zz + 0.5 };
        float mid[2]; rm::calc(eye, c, mid);
        double dist = dmax(0.6, c.dist(eye));
        float half = (float)toDeg(datan(0.38 / dist));
        bool got;
        if (silent()) {
            float sy = hasSilent() ? silentYaw() : selfYaw(), sp = hasSilent() ? silentPitch() : selfPitch();
            float dy = wrapDeg(sy - mid[0]);
            float ty = mid[0] + fclamp(dy, -half, half), tp = mid[1] + fclamp(sp - mid[1], -half, half);
            hub::request(ty, tp, hub::HIGHEST, -1);
            if (!hasSilent()) return false;
            got = raycastBlock(silentYaw(), silentPitch(), REACH, h);
        } else {
            cam::requestBox({ 1, 1 }, mid[0], mid[1], half, half, aimSpeed(), 1, 0, false);
            got = crosshairBlock(h);
        }
        return got && (int)h[0] == x && (int)h[1] == y && (int)h[2] == zz;
    }
    static bool refreshHit() {
        double h[7];
        if (!aimHit(ax, ay, az, h)) return false;
        face = (int)h[3]; hx = h[4]; hy = h[5]; hz = h[6];
        return true;
    }
    static bool use() {
        if (!clickMode()) { useItemOn(ax, ay, az, face, hx, hy, hz, true); swing(); return true; }
        double c[7];
        if (!crosshairBlock(c) || (int)c[0] != ax || (int)c[1] != ay || (int)c[2] != az) return false;
        if (pendUse() > 0) return false;
        clickUse();
        return true;
    }
    static void finish() { havePending = false; st = IDLE; anchorSlot = -1; armedAt = 0; cooldownUntil = NOW + 150; }
    static void abort() {
        if (anchorSlot >= 0 && selSlot() != anchorSlot) pressHotbar(anchorSlot);
        finish();
    }
    // Safe Anchor: drop a glowstone between the anchor and you, on the anchor's player-facing face, so
    // the blast's exposure rays are blocked and you take far less self-damage. Placed while glowstone is
    // already in hand (end of charge), via useItemOn on the anchor face. Only in silent/auto-aim mode —
    // there detonation uses useItemOn (explicit coords), so the shield block doesn't break the detonate
    // raycast the way it would in click mode.
    static void placeSafeGlow() {
        if (!safe() || clickMode()) return;
        int glow = findHotbar(XS("minecraft:glowstone"));
        if (glow < 0) return;
        Vec3 eye = selfEye();
        double dx = eye.x - (ax + 0.5), dy = eye.y - (ay + 0.5), dz = eye.z - (az + 0.5);
        double adx = dx < 0 ? -dx : dx, ady = dy < 0 ? -dy : dy, adz = dz < 0 ? -dz : dz;
        int f = (ady >= adx && ady >= adz) ? (dy > 0 ? UP : DOWN)
              : (adx >= adz)               ? (dx > 0 ? EAST : WEST)
                                           : (dz > 0 ? SOUTH : NORTH);
        int o[3]; offset(ax, ay, az, f, o);                       // the cell on the player's side of the anchor
        if (!(blockFlags(o[0], o[1], o[2]) & (BF_AIR | BF_REPLACEABLE))) return;   // already filled
        int back = selSlot();
        if (back != glow) pressHotbar(glow);
        double hxx = ax + 0.5, hyy = ay + 0.5, hzz = az + 0.5;    // center of the face we click
        switch (f) { case UP: hyy = ay + 1; break; case DOWN: hyy = ay; break; case NORTH: hzz = az; break;
                     case SOUTH: hzz = az + 1; break; case WEST: hxx = ax; break; case EAST: hxx = ax + 1; break; }
        useItemOn(ax, ay, az, f, hxx, hyy, hzz, true);
        if (back != glow && selSlot() != back) pressHotbar(back);
    }
    static void doCharge() {
        int c = charge(ax, ay, az);
        if (c < 0) { abort(); return; }
        if (c == 0) {
            if (!holds(XS("minecraft:glowstone"))) { if (NOW - startedAt > 400) abort(); return; }
            if (autoAim() && !refreshHit()) return;
            if (!use()) return;
        }
        placeSafeGlow();                                          // shield before the detonate step
        st = SWAP_BACK; actAt = NOW + delayMs(swapDelay);
    }
    static void doDetonate() {
        int c = charge(ax, ay, az);
        if (c < 0) { finish(); return; }
        if (c == 0) { if (NOW - actAt > 300) finish(); return; }
        if (!holds(XS("minecraft:respawn_anchor"))) { if (NOW - actAt > 400) finish(); return; }
        if (autoAim() && !refreshHit()) return;
        if (!use()) return;
        finish();
    }
    static void pre() {
        if (screenOpen()) { if (st != IDLE) abort(); return; }
        bool holdingAnchor = isItem(selSlot(), XS("minecraft:respawn_anchor"));
        if (holdingAnchor && sb(SF_USE_KEY)) {
            armedAt = NOW;
            double c[7];
            if (crosshairBlock(c)) {
                int x = (int)c[0], y = (int)c[1], zz = (int)c[2];
                if (!(blockFlags(x, y, zz) & BF_REPLACEABLE)) { int o[3]; offset(x, y, zz, (int)c[3], o); pendX = o[0]; pendY = o[1]; pendZ = o[2]; }
                else { pendX = x; pendY = y; pendZ = zz; }
                havePending = true;
            }
        }
        if (st != IDLE && NOW - startedAt > 2500) { abort(); return; }
        if (st != IDLE) {
            setUseKey(false); holdingUse = true;
            if (autoAim() && st != CHARGE && st != DETONATE_) { double h[7]; aimHit(ax, ay, az, h); }
        } else if (holdingUse) { restoreUseKey(); holdingUse = false; }

        switch (st) {
        case IDLE: {
            if (NOW < cooldownUntil || !holdingAnchor) return;
            if (ONLY_PLACED && NOW - armedAt > 600) return;
            int x, y, zz; double h[7];
            if (autoAim() && havePending) {
                x = pendX; y = pendY; zz = pendZ;
                bool on = aimHit(x, y, zz, h);
                if (charge(x, y, zz) != 0) { if (NOW - armedAt > 600) havePending = false; return; }
                if (!on) return;
            } else {
                if (!crosshairBlock(h)) return;
                x = (int)h[0]; y = (int)h[1]; zz = (int)h[2];
            }
            if (charge(x, y, zz) != 0) return;
            if (selfEye().dist({ x + 0.5, y + 0.5, zz + 0.5 }) > REACH) return;
            int glow = findHotbar(XS("minecraft:glowstone"));
            if (glow < 0) return;
            ax = x; ay = y; az = zz; face = (int)h[3]; hx = h[4]; hy = h[5]; hz = h[6];
            anchorSlot = selSlot(); startedAt = NOW;
            if (anchorSlot != glow) { pressHotbar(glow); keyTick = TICKS; }
            st = CHARGE; actAt = NOW + delayMs(swapDelay);
            if (actAt <= NOW) doCharge();
            break;
        }
        case CHARGE: if (NOW >= actAt) doCharge(); break;
        case SWAP_BACK:
            if (NOW < actAt) return;
            if (selSlot() != anchorSlot) { pressHotbar(anchorSlot); keyTick = TICKS; }
            if (!DETONATE || sb(SF_NETHER)) { finish(); return; }
            st = DETONATE_; actAt = NOW + delayMs(detDelay);
            if (actAt <= NOW) doDetonate();
            break;
        case DETONATE_: if (NOW >= actAt) doDetonate(); break;
        }
    }
    static void disable() { st = IDLE; anchorSlot = -1; if (holdingUse) { restoreUseKey(); holdingUse = false; } }
    static void reg() {
        M = mod(XS("Anchor"), XS("Combat"));
        swapDelay = sRange(XS("Swap Delay"), 1, 2, 0, 10, "ticks");
        detDelay = sRange(XS("Detonate Delay"), 1, 2, 0, 10, "ticks");
        autoAim = sBool(XS("Auto Aim"), true);
        silent = sBool(XS("Silent"), true);
        safe = sBool(XS("Safe Anchor"), false);
        aimSpeed = sNum(XS("Aim Speed"), 5, 1, 12);   // gentler default so the aim eases onto the anchor
        LG[M].pre = pre; LG[M].disable = disable;
    }
}

// ================================================================ Cart
// Bow or loaded crossbow + right click on the ground: rail, TNT cart, (fire), shot. Each step is
// your own input: camera onto the spot, the number key, a click (or holding right click).
namespace cart {
    static int M = -1;
    static SNum reach, draw; static SBool safe;
    enum Act { CLICK, HOLD };
    struct Step { Vec3 aim; int slot; Act act; int ticks; };
    static Step steps[6]; static int nSteps, at = -1, held; static bool useTaken;

    static int findRail() {
        const char* ids[] = { XS("minecraft:rail"), XS("minecraft:powered_rail"), XS("minecraft:detector_rail"), XS("minecraft:activator_rail") };
        for (auto id : ids) { int s = findHotbar(id); if (s >= 0) return s; }
        return -1;
    }
    static int findFire() {
        const char* ids[] = { XS("minecraft:flint_and_steel"), XS("minecraft:fire_charge") };
        for (auto id : ids) { int s = findHotbar(id); if (s >= 0) return s; }
        return -1;
    }
    static bool support(double maxDist, int* s) {
        double h[7];
        if (!crosshairBlock(h)) return false;
        int x = (int)h[0], y = (int)h[1], zz = (int)h[2];
        if (blockFlags(x, y, zz) & BF_REPLACEABLE) y -= 1;
        else if ((int)h[3] != UP) return false;
        if (!(blockFlags(x, y, zz) & BF_SOLID)) return false;
        if (!(blockFlags(x, y + 1, zz) & BF_REPLACEABLE)) return false;
        if (selfEye().dist({ x + 0.5, y + 1.0, zz + 0.5 }) > maxDist) return false;
        s[0] = x; s[1] = y; s[2] = zz;
        return true;
    }
    static bool fireSpot(const int* s, int* f) {
        Vec3 eye = selfEye();
        double cx = s[0] + 0.5, cy = s[1] + 1.35, cz = s[2] + 0.5;
        for (double t = 0.97; t > 0.15; t -= 0.03) {
            double x = eye.x + (cx - eye.x) * t, y = eye.y + (cy - eye.y) * t, zz = eye.z + (cz - eye.z) * t;
            int bx = ifloor(x), by = ifloor(y), bz = ifloor(zz);
            if (bx == s[0] && bz == s[2]) continue;
            if (by != s[1] + 1) { if (by > s[1] + 1) break; continue; }
            if (!(blockFlags(bx, by, bz) & BF_AIR) || !(blockFlags(bx, by - 1, bz) & BF_SOLID)) continue;
            f[0] = bx; f[1] = by; f[2] = bz;
            return true;
        }
        return false;
    }
    static void stop() {
        at = -1; held = 0; nSteps = 0;
        if (useTaken) { restoreUseKey(); useTaken = false; }
    }
    // Safe Cart: drop an obsidian block low in front of you toward the cart, so the TNT blast is walled
    // off from your body while your aim/arrow still clears it. Placed silently (useItemOn) right before
    // the combo. Needs obsidian (or crying obsidian) in your hotbar — the only blocks worth blast-walling.
    static int findShieldBlock() {
        int o = findHotbar(XS("minecraft:obsidian"));
        return o >= 0 ? o : findHotbar(XS("minecraft:crying_obsidian"));
    }
    static void placeShield(const int* s) {
        int blk = findShieldBlock();
        if (blk < 0) return;
        Vec3 p = selfPos();
        double dx = (s[0] + 0.5) - p.x, dz = (s[2] + 0.5) - p.z;
        double len = dsqrt(dx * dx + dz * dz);
        if (len < 0.1) return;
        dx /= len; dz /= len;
        int cx = ifloor(p.x + dx), cy = ifloor(p.y), cz = ifloor(p.z + dz);   // feet-level cell toward the cart
        if (!(blockFlags(cx, cy, cz) & (BF_AIR | BF_REPLACEABLE))) return;     // keep it clear of your own body
        if (!(blockFlags(cx, cy - 1, cz) & BF_SOLID)) return;                  // need ground to click against
        if (selSlot() != blk) pressHotbar(blk);
        useItemOn(cx, cy - 1, cz, UP, cx + 0.5, cy, cz + 0.5, true);           // obsidian in the feet-front cell
    }
    static void tick() {
        if (at < 0) return;
        if (screenOpen() || at >= nSteps) { stop(); return; }
        Step& s = steps[at];
        float r[2]; rm::calc(selfEye(), s.aim, r); setRot(r[0], r[1]);
        if (s.slot >= 0 && selSlot() != s.slot) pressHotbar(s.slot);
        if (s.act == CLICK) { clickUse(); at++; }
        else {
            if (held == 0) { setUseKey(true); clickUse(); }
            if (++held > s.ticks) { setUseKey(false); held = 0; at++; }
        }
        if (at >= nSteps) stop();
    }
    static void pre() {
        if (at >= 0 || screenOpen() || pendUse() <= 0) return;
        const InvRec& hand = mainHand();
        if (isEmpty(hand)) return;
        int heldSlot = selSlot();
        bool bow = eq(hand.id, XS("minecraft:bow")) && !usingItem();
        bool xbow = eq(hand.id, XS("minecraft:crossbow")) && (hand.flags & 4);
        if (!bow && !xbow) return;
        int s[3];
        if (!support(reach(), s)) return;
        int rail = findRail(), cartS = findHotbar(XS("minecraft:tnt_minecart"));
        if (rail < 0 || cartS < 0) return;
        bool flame = bow && hand.ench[EN_FLAME] > 0;
        int f[3]; int fire = -1; bool haveFire = false;
        if (!flame) {
            haveFire = fireSpot(s, f); fire = findFire();
            if (!haveFire || fire < 0) return;
        }
        if (safe()) placeShield(s);          // wall yourself before the combo
        consumeUse();
        nSteps = 0;
        steps[nSteps++] = { { s[0] + 0.5, s[1] + 1.0, s[2] + 0.5 }, rail, CLICK, 0 };
        steps[nSteps++] = { { s[0] + 0.5, s[1] + 1.06, s[2] + 0.5 }, cartS, CLICK, 0 };
        if (haveFire) steps[nSteps++] = { { f[0] + 0.5, (double)f[1], f[2] + 0.5 }, fire, CLICK, 0 };
        if (bow) steps[nSteps++] = { { s[0] + 0.5, s[1] + 1.35, s[2] + 0.5 }, heldSlot, HOLD, iround(draw()) };
        else steps[nSteps++] = { { s[0] + 0.5, s[1] + 1.35, s[2] + 0.5 }, heldSlot, CLICK, 0 };
        at = 0; held = 0;
        setUseKey(false); useTaken = true;
    }
    static void disable() { stop(); }
    static void reg() {
        M = mod(XS("Cart"), XS("Combat"));
        reach = sNum(XS("Reach"), 4.5f, 2, 4.5f, "blocks");
        draw = sNum(XS("Draw"), 8, 3, 20, "ticks");
        safe = sBool(XS("Safe Cart"), false);
        LG[M].tick = tick; LG[M].pre = pre; LG[M].disable = disable;
    }
}

// ================================================================ Combo Assist (W-Tap / S-Tap / Jump Reset)
namespace combo {
    static int M = -1;
    static SEnum tapMode; static SRange tapDelay; static SBool jumpReset; static SNum jumpChance; static SRange jumpDelay;
    static const float TAP_CHANCE = 100; static const bool TAP_PLAYERS = true, TAP_SPRINT_ONLY = true, JUMP_FWD = false;
    static const float COOLDOWN = 250;
    static bool seeded, suppressing, forcingBack, tapBack;
    static int lastHitSeq, lastKbSeq; static i64 tickN, tapStart, tapEnd, lastTap, jumpAt = -1;

    static bool roll(float pct) { return pct >= 100 || RNG.nextFloat() * 100 < pct; }
    static bool adaptiveS() {
        Ent t = entById(ev::lastHitId);
        if (!t.ok()) return false;
        Vec3 me = selfPos(), tp = t.pos(), tq = t.prev();
        double dx = tp.x - me.x, dzz = tp.z - me.z, dist = dsqrt(dx * dx + dzz * dzz);
        if (dist < 1e-3) return true;
        double ux = dx / dist, uz = dzz / dist;
        double closing = (SF[SF_VX] * ux + SF[SF_VZ] * uz) - ((tp.x - tq.x) * ux + (tp.z - tq.z) * uz);
        int hurt = (int)SF[SF_HURT];
        if (dist < 2.3) return true;
        if (closing > 0.32) return true;
        if (hurt > 0 && dist < 2.9) return true;
        return false;
    }
    static void onOwnHit() {
        int m = tapMode.idx();                                   // 0 Adaptive 1 W-Tap 2 S-Tap 3 Off
        if (m == 3) return;
        if (TAP_PLAYERS && !ev::lastHitPlayer) return;
        if (TAP_SPRINT_ONLY && !ev::lastHitSprinting) return;
        if (!sb(SF_FWD_HELD)) return;
        if (tapStart > 0) return;
        if (NOW - lastTap < COOLDOWN) return;
        if (!roll(TAP_CHANCE)) return;
        bool s = m == 2 || (m == 0 && adaptiveS());
        i64 hold = (i64)tapDelay.pickInt() * 50 - 5;
        tapStart = NOW; tapEnd = NOW + (hold > 45 ? hold : 45); tapBack = s; lastTap = NOW;
    }
    static void onKnockback() {
        if (!jumpReset()) return;
        if ((int)SF[SF_HURT] == 0) return;
        if (JUMP_FWD && !sb(SF_FWD_HELD)) return;
        if (!roll(jumpChance())) return;
        jumpAt = tickN + jumpDelay.pickInt();
    }
    static void tick() {
        tickN++;
        if (!seeded) { lastHitSeq = ev::hitSeq; lastKbSeq = ev::kbSeq; seeded = true; }
        if (ev::hitSeq != lastHitSeq) { lastHitSeq = ev::hitSeq; onOwnHit(); }
        if (ev::kbSeq != lastKbSeq) { lastKbSeq = ev::kbSeq; if (!ev::kbFromExplosion()) onKnockback(); }
        if (jumpAt >= 0 && tickN >= jumpAt) {
            jumpAt = -1;
            if (onGround() && !screenOpen()) jump();
        }
        bool tapping = tapStart > 0 && NOW >= tapStart && NOW < tapEnd;
        if (tapStart > 0 && NOW >= tapEnd) { tapStart = 0; tapEnd = 0; }
        bool wantSup = tapping && !screenOpen(), wantBack = tapping && tapBack && !screenOpen();
        if (wantSup) D(D_SET_FWD, 0); else if (suppressing) D(D_RESTORE_FWD);
        if (wantBack) D(D_SET_BACK, 1); else if (forcingBack) D(D_RESTORE_BACK);
        suppressing = wantSup; forcingBack = wantBack;
    }
    static void disable() {
        if (suppressing) D(D_RESTORE_FWD);
        if (forcingBack) D(D_RESTORE_BACK);
        suppressing = forcingBack = false; tapStart = tapEnd = 0; jumpAt = -1; seeded = false;
    }
    static bool suppressingFwd() { return isOn(M) && suppressing; }
    static bool forcingBk() { return isOn(M) && forcingBack; }
    static void reg() {
        const char* TAPS[] = { XS("Adaptive"), XS("W-Tap"), XS("S-Tap"), XS("Off") };
        M = mod(XS("Combo Assist"), XS("Combat"));
        tapMode = sEnum(XS("Combo Tap"), 0, TAPS, 4);
        tapDelay = sRange(XS("Delay"), 2, 3, 1, 10, "ticks");
        jumpReset = sBool(XS("Jump Reset"), true);
        jumpChance = sNum(XS("Jump Chance"), 80, 1, 100, "%");
        jumpDelay = sRange(XS("Jump Delay"), 0, 0, 0, 3, "ticks");
        LG[M].tick = tick; LG[M].disable = disable;
    }
}

// ================================================================ Velocity
// Keeps a reduced share of incoming knockback, randomized per hit, capped for Grim.
namespace velocity {
    static int M = -1;
    static SNum horiz, vert; static SBool grim;
    static const bool RANDOMIZE = true, DIRECTIONAL = true, SKIP_EXPLOSION = true;
    static const float SPREAD = 8, DROPOUT = 15, DIR_TH = 0.3f, EXPLOSION_MIN = 0.6f;
    static double selfVx, selfVz;
    static void tick() { selfVx = SF[SF_VX]; selfVz = SF[SF_VZ]; }
    static bool filter(double vx, double vy, double vz, bool explosion, double* o) {
        o[0] = vx; o[1] = vy; o[2] = vz;
        if (explosion && SKIP_EXPLOSION && dsqrt(vx * vx + vz * vz) >= EXPLOSION_MIN) return true;
        double hCut = horiz() / 100.0, vCut = vert() / 100.0;
        if (grim()) { hCut = dmin(hCut, 0.30); vCut = dmin(vCut, 0.30); }
        if (RANDOMIZE && RNG.nextDouble() * 100 < DROPOUT) return true;
        if (RANDOMIZE) {
            hCut = dclamp(hCut + RNG.gaussian() * SPREAD / 100.0, 0, 0.5);
            vCut = dclamp(vCut + RNG.gaussian() * SPREAD / 100.0, 0, 0.5);
        }
        if (DIRECTIONAL) {
            double dot = selfVx * vx + selfVz * vz;
            double mag = dsqrt((selfVx * selfVx + selfVz * selfVz) * (vx * vx + vz * vz));
            if (mag > 1e-4 && dot / mag > DIR_TH) hCut = 0;
        }
        o[0] = vx * (1 - hCut); o[2] = vz * (1 - hCut);
        o[1] = vy > 0 ? vy * (1 - vCut) : vy;
        return true;
    }
    static void reg() {
        M = mod(XS("Velocity"), XS("Movement"));
        horiz = sNum(XS("Horizontal Cut"), 20, 0, 100, "%");
        vert = sNum(XS("Vertical Cut"), 10, 0, 100, "%");
        grim = sBool(XS("Grim-Safe Caps"), true);
        LG[M].tick = tick;
    }
}

// ================================================================ Spear Lunge
// On a server with the modded spear + LUNGE enchant, the lunge thrust is a STAB player-action packet
// (PlayerAction STAB) — NOT a wind-charge launch or a left-click. Bind it: swap to your best LUNGE
// spear, send ONE stab (reads as a normal thrust on the wire), then swap back. Mirrors the reference
// (water.wtf SpearLungeModule). Silent swaps back the same tick so the hotbar never visibly moves.
namespace lunge {
    static int M = -1;
    static SBool silent, swapBack; static SEnum cdMode; static SNum cdThreshold;
    enum St { IDLE, BACK };
    static St st = IDLE; static int startSlot = -1, t;

    static int bestSpear() {
        int best = -1, lvl = -1;
        for (int i = 0; i < 9; i++) { const InvRec& it = item(i); if (isEmpty(it) || !endsWith(it.id, "_spear")) continue; if (it.ench[EN_LUNGE] > lvl) { lvl = it.ench[EN_LUNGE]; best = i; } }
        return best;
    }
    // None: fire every press. Vanilla: only once the attack cooldown has recovered past the threshold,
    // so the single stab rides the normal swing stream and the server accepts it as a real thrust.
    static bool cooldownReady() { return cdMode.idx() == 0 || strength() >= cdThreshold() / 100.f; }
    static void swapToStart() {
        if (swapBack() && startSlot >= 0 && selSlot() != startSlot) pressHotbar(startSlot);
        startSlot = -1;
    }
    static void bind() {
        if (st != IDLE || screenOpen() || usingItem()) return;
        int sp = bestSpear();
        if (sp < 0 || !cooldownReady()) return;
        startSlot = selSlot();
        if (selSlot() != sp) pressHotbar(sp);
        D(D_SPEAR_STAB);                     // the lunge thrust (modded STAB player-action packet)
        if (silent()) { swapToStart(); st = IDLE; }   // silent: select spear, stab, select back — one tick
        else { st = BACK; t = 0; }                     // visible: hold the spear a tick, then swap back
    }
    static void tick() { if (st == BACK && ++t >= 1) { swapToStart(); st = IDLE; } }
    static void disable() { if (!silent()) swapToStart(); st = IDLE; startSlot = -1; t = 0; }
    static void reg() {
        const char* CD[] = { XS("None"), XS("Vanilla") };
        M = mod(XS("Lunge"), XS("Combat"));
        silent = sBool(XS("Silent"), true);
        swapBack = sBool(XS("Swap Back"), true);
        cdMode = sEnum(XS("Cooldown"), 0, CD, 2);
        cdThreshold = sNum(XS("Cooldown %"), 85, 0, 100, "%");
        LG[M].tick = tick; LG[M].bind = bind; LG[M].disable = disable;
    }
}

} // namespace z
