// Zalard native logic: movement + automation modules and Freecam.
#pragma once

namespace z {

namespace noslow {
    static int M = -1;
    static void reg() { M = mod(XS("No Slow"), XS("Movement")); }
}

// Holds inbound (clientbound) packets. The actual queue lives in Java on the netty pipeline
// (dev.zalard.adapter.Lag) because packets never reach native; this module just owns the toggle and
// settings and hands them to Java once per tick via Z.lagConfig(). Two modes:
//   Backtrack - release packets on a rolling `Hold`-ms delay, so other players' positions arrive late
//               and their hitbox lags behind where they really are (you hit the ghost).
//   Blink     - hold everything while on, release the lot the moment you toggle off.
namespace lag {
    static int M = -1;
    static SEnum mode; static SNum hold, burst; static SBool onHit;
    // packed for Java: bit0 on, bits1-2 mode (0 backtrack / 1 blink / 2 burst), bit3 release-on-hit,
    // bits16-31 hold ms, bits32-47 burst ms
    static u64 config() {
        if (M < 0 || !isOn(M)) return 0;
        i64 ms = iround(hold()); if (ms < 0) ms = 0; if (ms > 0xFFFF) ms = 0xFFFF;
        i64 bms = iround(burst()); if (bms < 0) bms = 0; if (bms > 0xFFFF) bms = 0xFFFF;
        return (u64)1 | ((u64)(mode.idx() & 3) << 1) | ((u64)(onHit() ? 1 : 0) << 3)
             | ((u64)ms << 16) | ((u64)bms << 32);
    }
    static void reg() {
        const char* MODES[] = { XS("Backtrack"), XS("Blink"), XS("Burst") };
        M = mod(XS("Lag"), XS("Combat"));
        mode = sEnum(XS("Mode"), 0, MODES, 3);
        hold = sNum(XS("Hold"), 150, 0, 1000, "ms");
        burst = sNum(XS("Burst"), 120, 20, 600, "ms");   // Burst mode: freeze this long, snap, repeat
        onHit = sBool(XS("Release On Hit"), true);
    }
}

/** Horizontal velocity of `speed` toward your WASD input relative to where you look. */
static void moveDir(double speed, double* out) {
    double f = (sb(SF_K_FWD) ? 1 : 0) - (sb(SF_K_BACK) ? 1 : 0), s = (sb(SF_K_LEFT) ? 1 : 0) - (sb(SF_K_RIGHT) ? 1 : 0);
    double l = dsqrt(f * f + s * s);
    if (l < 1e-4) { out[0] = out[1] = 0; return; }
    f /= l; s /= l;
    double r = toRad(selfYaw()), sn = dsin(r), cs = dcos(r);
    out[0] = (-sn * f + cs * s) * speed; out[1] = (cs * f + sn * s) * speed;
}

namespace flight {
    static int M = -1;
    static SNum speed, vSpeed; static SBool antiKick; static int t;
    static void tick() {
        t++;
        double h[2]; moveDir(speed(), h);
        double vy = sb(SF_K_JUMP) ? vSpeed() : sb(SF_K_SNEAK) ? -vSpeed() : 0;
        if (antiKick() && vy == 0 && t % 40 == 0) vy = -0.04;
        setVel(h[0], vy, h[1]);
    }
    static void disable() { if (inGame) setVel(0, 0, 0); }
    static void reg() {
        M = mod(XS("Flight"), XS("Movement"));
        speed = sNum(XS("Speed"), 1, 0.1f, 5, "b/t");
        vSpeed = sNum(XS("Vertical Speed"), 0.6f, 0.1f, 3, "b/t");
        antiKick = sBool(XS("Anti Kick"), true);
        LG[M].tick = tick; LG[M].disable = disable;
    }
}

namespace motion {
    static int M = -1;
    static SEnum mode; static SNum speed;
    static void tick() {
        if (screenOpen() || lookSteered()) return;
        double h[2]; moveDir(speed(), h);
        if (h[0] == 0 && h[1] == 0) return;
        bool ground = onGround();
        if (mode.idx() == 1) { if (ground) setVel(h[0], SF[SF_VY], h[1]); return; }
        if (ground && !sb(SF_K_SNEAK)) jump();
        setVel(h[0], SF[SF_VY], h[1]);
    }
    static void reg() {
        const char* MODES[] = { XS("Strafe"), XS("Ground") };
        M = mod(XS("Motion"), XS("Movement"));
        mode = sEnum(XS("Mode"), 0, MODES, 2);
        speed = sNum(XS("Speed"), 0.36f, 0.2f, 2, "b/t");
        LG[M].tick = tick;
    }
}

namespace fastplace {
    static int M = -1;
    static SNum delay; static SBool blocks;
    static void tick() {
        if (screenOpen() || !sb(SF_USE_KEY)) return;
        if (blocks() && !sb(SF_HOLD_BLOCK)) return;
        int want = iround(delay());
        if ((int)SF[SF_RCD] > want) D(D_SET_RCD, want);
    }
    static void reg() {
        M = mod(XS("Fast Place"), XS("Automation"));
        delay = sNum(XS("Delay"), 0, 0, 3, "ticks");
        blocks = sBool(XS("Blocks Only"), true);
        LG[M].tick = tick;
    }
}

// Water bucket clutch with your own inputs: look down, bucket key, right click just before landing,
// scoop it back up.
namespace nofall {
    static int M = -1;
    static SNum minFall; static SBool pickUp;
    enum St { IDLE, AIM, PLACED, PICKUP };
    static St st = IDLE; static int slotBefore = -1, t; static float pitchBefore;
    static double groundGap() {
        Vec3 p = selfPos();
        int x = ifloor(p.x), zz = ifloor(p.z), y0 = ifloor(p.y);
        for (int y = y0; y > y0 - 14; y--) if (blockFlags(x, y, zz) & BF_SOLID) return p.y - (y + 1);
        return -1;
    }
    static void finish() {
        if (slotBefore >= 0 && selSlot() != slotBefore) pressHotbar(slotBefore);
        if (st != IDLE) setRot(selfYaw(), pitchBefore);
        st = IDLE; slotBefore = -1; t = 0;
    }
    static void tick() {
        if (screenOpen()) { if (st != IDLE) finish(); return; }
        bool inWater = sb(SF_IN_WATER);
        switch (st) {
        case IDLE: {
            if (onGround() || inWater || fallDist() < minFall() || lookSteered()) return;
            if (SF[SF_VY] > -0.3 || findHotbar(XS("minecraft:water_bucket")) < 0) return;
            double gap = groundGap();
            if (gap < 0 || gap > 12) return;
            slotBefore = selSlot(); pitchBefore = selfPitch();
            st = AIM; t = 0;
            break;
        }
        case AIM: {
            if (onGround() || inWater || ++t > 60) { finish(); return; }
            setRot(selfYaw(), 90);
            int b = findHotbar(XS("minecraft:water_bucket"));
            if (b < 0) { finish(); return; }
            if (selSlot() != b) { pressHotbar(b); return; }
            double gap = groundGap(), vy = -SF[SF_VY];
            if (gap >= 0 && gap <= dmax(1.2, vy * 2.5 + 0.5) && gap + 1.62 <= 4.4) {
                double h[7];
                if (crosshairBlock(h)) { clickUse(); st = PLACED; t = 0; }
            }
            break;
        }
        case PLACED:
            if (++t > 40) { finish(); return; }
            if (inWater || onGround()) { if (pickUp()) { st = PICKUP; t = 0; } else finish(); }
            break;
        case PICKUP:
            setRot(selfYaw(), 90);
            if (t == 0 && isItem(selSlot(), XS("minecraft:bucket"))) clickUse();
            if (++t >= 3) finish();
            break;
        }
    }
    static void disable() { st = IDLE; slotBefore = -1; }
    static void reg() {
        M = mod(XS("No Fall"), XS("Automation"));
        minFall = sNum(XS("Min Fall"), 4, 3, 30, "blocks");
        pickUp = sBool(XS("Pick Up"), true);
        LG[M].tick = tick; LG[M].disable = disable;
    }
}

namespace freecam {
    static int M = -1;
    static SNum speed;
    static void tick() { D(D_SET_FREECAM, 1, speed()); }
    static void disable() { D(D_SET_FREECAM, 0, 0); }
    static void reg() {
        M = mod(XS("Freecam"), XS("Render"));
        speed = sNum(XS("Speed"), 1, 0.1f, 4, "b/t");
        LG[M].tick = tick; LG[M].disable = disable;
    }
}

// ================================================================ Totem
// Offhand totem with your own keys: number key of a hotbar totem, F next tick, your slot back.
// Cursor Sim opens the inventory and moves the mouse to the totem like a hand; Silent Sim does
// the same swap with the same timing without the inventory showing.
namespace totem {
    static int M = -1;
    static SNum hp, cursorSpeed, hoverHp; static SRange delay; static SBool always, cursorSim, autoOpen, silentSim, hover;
    static const float PANIC_HP = 4, PANIC_DELAY = 40, RESCAN = 500, SPEED_CAP = 0.21f;
    static const bool SKIP_MOVING = true, SKIP_CONTAINER = true, AUTO_CLOSE = true;
    static const i64 MIN_GAP = 100;
    static i64 lastSwap, lastEmptyScan, confirmUntil = -1, readyAt = -1, openedAt, closeAt = -1, silentAt = -1;
    static int cursorSlot = -1, backToSlot = -1; static bool pressF, openedByUs, silentSprint;
    // cursor movement (frames)
    static double sx, sy, curveOff, prog = -1; static i64 arriveMs = -1, dwellMs, lastFrameNs;

    static bool totemOff() { return isItem(36, XS("minecraft:totem_of_undying")); }
    // Hover Totem: when HP drops under Hover HP, just switch your held hotbar slot to the totem (no mouse,
    // no inventory) so you're holding it ready to pop from the main hand; switch back to your weapon once
    // you recover. Independent of the offhand auto-totem logic below.
    static int hoverBack = -1;
    static void hoverTick() {
        if (!hover() || screenOpen()) { return; }
        int totemHot = findHotbar(XS("minecraft:totem_of_undying"));
        if (health() <= hoverHp() && totemHot >= 0) {
            if (selSlot() != totemHot) {
                if (hoverBack < 0) hoverBack = selSlot();      // remember your weapon slot
                pressHotbar(totemHot);
            }
        } else if (hoverBack >= 0 && health() > hoverHp()) {
            if (isItem(selSlot(), XS("minecraft:totem_of_undying"))) pressHotbar(hoverBack);  // recovered -> back to weapon
            hoverBack = -1;
        }
    }
    static int findAnyTotem() { int h = findHotbar(XS("minecraft:totem_of_undying")); return h >= 0 ? h : findInventory(XS("minecraft:totem_of_undying")); }
    static i64 confirmMs() { int ping = (int)SF[SF_PING]; i64 v = (i64)ping * 2 + 150; return v < 350 ? 350 : v > 1500 ? 1500 : v; }
    static bool waiting(bool panic) {
        if (readyAt < 0) {
            i64 d = delay.pickTicksMs();
            if (panic && d > (i64)PANIC_DELAY) d = (i64)PANIC_DELAY;
            readyAt = NOW + (d - 10 > 0 ? d - 10 : 0);
        }
        return NOW < readyAt;
    }
    static bool needNow(bool emptyOff) { return (always() && emptyOff) || health() <= hp(); }

    static void tick() {
        hoverTick();                         // hotbar-switch hover (independent of the offhand swap below)
        bool invOpen = sb(SF_INV_OPEN);
        if (cursorSim() && invOpen) {
            bool have = totemOff();
            if (openedByUs) {
                if (have && closeAt < 0) closeAt = NOW + 120 + (i64)(RNG.nextDouble() * 200);
                bool done = (have && NOW >= closeAt) || NOW - openedAt > 3000 || (findAnyTotem() < 0 && !have);
                if (done) {
                    cursorSlot = -1;
                    if (AUTO_CLOSE) D(D_CLOSE_SCREEN);
                    openedByUs = false; closeAt = -1;
                    return;
                }
            }
            if (have || NOW - lastSwap < MIN_GAP) { cursorSlot = -1; return; }
            if (cursorSlot < 0) cursorSlot = findAnyTotem();
            return;
        }
        cursorSlot = -1;
        if (openedByUs && !invOpen) { openedByUs = false; closeAt = -1; }

        if (silentSprint) { D(D_RESTORE_SPRINT); silentSprint = false; }
        if (cursorSim() && silentSim()) {
            if (screenOpen()) { silentAt = -1; return; }
            const InvRec& off = offHand();
            bool have = totemOff(), empty = isEmpty(off);
            if (have || !needNow(empty)) { silentAt = -1; if (have) { readyAt = -1; confirmUntil = -1; } return; }
            if (confirmUntil > 0 && NOW < confirmUntil) return;
            confirmUntil = -1;
            bool panic = health() <= PANIC_HP;
            if (NOW - lastSwap < MIN_GAP || waiting(panic)) return;
            if (silentAt < 0) {
                if (findAnyTotem() < 0) return;
                double dist = 60 + RNG.nextDouble() * 140;
                double durS = (0.10 + dist / 1800.0) * (11 - cursorSpeed()) / 5.0;
                i64 react = panic ? 20 + (i64)(RNG.nextDouble() * 40) : 60 + (i64)(RNG.nextDouble() * 90);
                silentAt = NOW + react + (i64)(durS * 1000) + 35 + (i64)(RNG.nextDouble() * 60);
                return;
            }
            if (NOW < silentAt) return;
            silentAt = -1;
            int slot = findAnyTotem();
            if (slot < 0) return;
            D(D_SET_SPRINT, 0);
            if (sb(SF_SPRINTING)) D(D_STOP_SPRINT);
            silentSprint = true;
            D(D_SWAP_SLOT_OFFHAND, slot);
            lastSwap = NOW; readyAt = -1; confirmUntil = NOW + confirmMs();
            return;
        }
        silentAt = -1;

        if (cursorSim() && autoOpen()) {
            if (screenOpen()) return;
            if (totemOff()) return;
            if (!needNow(isEmpty(offHand()))) return;
            bool panic = health() <= PANIC_HP;
            if (NOW - lastSwap < MIN_GAP || waiting(panic)) return;
            if (findAnyTotem() < 0) return;
            D(D_OPEN_INV);
            openedByUs = true; openedAt = NOW; closeAt = -1;
            return;
        }
        bool panic = health() <= PANIC_HP;
        if (pressF) {
            pressSwapOffhand(); pressF = false;
            confirmUntil = NOW + confirmMs();
            return;
        }
        if (backToSlot >= 0) {
            if (selSlot() != backToSlot) pressHotbar(backToSlot);
            backToSlot = -1;
            return;
        }
        if (NOW - lastSwap < MIN_GAP) return;
        if (SKIP_CONTAINER && sb(SF_CONTAINER)) return;
        if (NOW - lastEmptyScan < RESCAN) return;
        if (totemOff()) { readyAt = -1; confirmUntil = -1; return; }
        if (confirmUntil > 0 && NOW < confirmUntil) return;
        confirmUntil = -1;
        if (!needNow(isEmpty(offHand()))) { readyAt = -1; return; }
        if (waiting(panic)) return;

        int hot = findHotbar(XS("minecraft:totem_of_undying"));
        if (hot >= 0) {
            if (SKIP_MOVING && !panic && dsqrt(SF[SF_HSPEED_SQ]) > SPEED_CAP) return;
            int original = selSlot();
            if (original != hot) { pressHotbar(hot); backToSlot = original; pressF = true; }
            else { pressSwapOffhand(); confirmUntil = NOW + confirmMs(); }
            lastSwap = NOW; readyAt = -1;
            return;
        }
        int inv = findInventory(XS("minecraft:totem_of_undying"));
        if (inv < 0) { lastEmptyScan = NOW; return; }
        int freeHot = -1;
        for (int i = 0; i < 9; i++) if (isEmpty(item(i))) { freeHot = i; break; }
        if (freeHot < 0) freeHot = 0;
        D(D_SWAP_TO_HOTBAR, inv, freeHot);
        lastSwap = NOW;
    }

    static void frame() {
        i64 nowNs = NOWNS;
        double dt = lastFrameNs == 0 ? 0 : dmin(0.05, (nowNs - lastFrameNs) / 1e9);
        lastFrameNs = nowNs;
        if (cursorSlot < 0 || !sb(SF_INV_OPEN)) { prog = -1; arriveMs = -1; return; }
        if (D(D_INV_SLOT_POS, cursorSlot) != 1) return;
        double tx = OUTB[0], ty = OUTB[1];
        if (D(D_CURSOR_POS) != 1) return;
        double cx = OUTB[0], cy = OUTB[1];
        if (prog < 0) {
            sx = cx; sy = cy; prog = 0; arriveMs = -1;
            double dist = dhypot(tx - sx, ty - sy);
            curveOff = (RNG.nextDouble() - 0.5) * dmin(60, dist * 0.25);
            dwellMs = 35 + (i64)(RNG.nextDouble() * 60);
        }
        if (prog < 1) {
            double dist = dhypot(tx - sx, ty - sy);
            double durS = (0.10 + dist / 1800.0) * (11 - cursorSpeed()) / 5.0;
            prog = dmin(1, prog + dt / dmax(0.03, durS));
            double e = prog * prog * prog * (prog * (prog * 6 - 15) + 10);
            double nx = ty - sy, ny = -(tx - sx), nl = dmax(1e-6, dhypot(nx, ny));
            double bend = dsin(PI * e) * curveOff;
            D(D_SET_CURSOR, sx + (tx - sx) * e + nx / nl * bend, sy + (ty - sy) * e + ny / nl * bend);
            return;
        }
        if (arriveMs < 0) arriveMs = NOW;
        if (NOW - arriveMs < dwellMs) return;
        int now = findAnyTotem();
        if (now != cursorSlot) { cursorSlot = now; prog = -1; return; }
        D(D_SWAP_SLOT_OFFHAND, cursorSlot);
        lastSwap = NOW; readyAt = -1;
        cursorSlot = -1; prog = -1; arriveMs = -1;
    }
    static bool visCursor() { return cursorSim(); }
    static bool visOpen() { return cursorSim() && !silentSim(); }
    static void reg() {
        M = mod(XS("Totem"), XS("Automation"));
        hp = sNum(XS("HP Threshold"), 10, 0.5f, 20, "HP");
        always = sBool(XS("Always Hold"), true);
        delay = sRange(XS("Delay"), 1, 3, 0, 20, "ticks");
        cursorSim = sBool(XS("Cursor Sim"), false);
        cursorSpeed = vis(sNum(XS("Cursor Speed"), 5, 1, 10), visCursor);
        autoOpen = vis(sBool(XS("Open Inventory"), true), visOpen);
        silentSim = vis(sBool(XS("Silent Sim"), false), visCursor);
        hover = sBool(XS("Hover Totem"), false);
        hoverHp = sNum(XS("Hover HP"), 8, 1, 19, "HP");
        LG[M].tick = tick; LG[M].frame = frame;
        setOn(M, true);
    }
}

// ================================================================ Auto Shield
// Raises YOUR shield (holds right-click) when a hostile player is in range, and drops it the tick you
// swing so your own hits still land. Works with a shield in the main hand or the offhand (slot 36).
// This is the opposite of Shield Breaker, which swaps to an axe to punch THROUGH an enemy's shield.
namespace autoshield {
    static int M = -1;
    static SNum range; static SBool dropToAttack, maceOnly;
    static bool raised = false;
    static bool hasShield() { return isItem(selSlot(), XS("minecraft:shield")) || isItem(36, XS("minecraft:shield")); }
    static bool enemyNear() {
        snapEnts();
        Vec3 eye = selfEye(); double rSq = range() * range();
        for (int i = 0; i < entCount; i++) {
            Ent e = entAt(i);
            if (e.self() || e.dead() || !e.player() || friendEnt(e)) continue;
            if (e.distSq(eye) <= rSq) return true;
        }
        return false;
    }
    // Mace Only: find a player who's about to mace-smash you — airborne, above you, and falling (toward
    // you). We can't see their held item, so this setup is the tell.
    static Ent maceThreat() {
        snapEnts();
        Vec3 eye = selfEye(); double myY = selfPos().y, best = range() * range(); Ent out{ nullptr };
        for (int i = 0; i < entCount; i++) {
            Ent e = entAt(i);
            if (e.self() || e.dead() || !e.player() || friendEnt(e)) continue;
            if (e.ground()) continue;                       // airborne
            Vec3 p = e.pos();
            if (p.y - myY < 0.2) continue;                  // above you
            if (p.y - e.prev().y > -0.03) continue;         // falling toward you
            double d = e.distSq(eye);
            if (d <= best) { best = d; out = e; }
        }
        return out;
    }
    static void drop() { if (raised) { restoreUseKey(); raised = false; } }
    static void pre() {
        // your own right-click (eating, bow, your manual block) always wins
        if (screenOpen() || usingItem()) { drop(); return; }
        if (!hasShield()) { drop(); return; }
        Ent threat{ nullptr };
        if (maceOnly()) {
            threat = maceThreat();
            if (!threat.ok()) { drop(); return; }           // only block an actual incoming smash
        } else if (!enemyNear()) { drop(); return; }
        if (dropToAttack() && pendAtk() > 0 && !maceOnly()) { drop(); return; }   // mace-only: keep blocking
        if (threat.ok()) {                                  // aim at them so the shield faces the smash
            Vec3 me = selfEye(), tp = threat.pos();
            double dx = tp.x - me.x, dy = (tp.y + 0.9) - me.y, dz = tp.z - me.z;
            double dist = dsqrt(dx * dx + dz * dz);
            float yaw = wrapDeg((float)(toDeg(datan2(dz, dx)) - 90.0));
            float pitch = (float)-toDeg(datan2(dy, dist));
            setSilent(yaw, pitch);
        }
        if (!raised) { setUseKey(true); raised = true; }
    }
    static void disable() { drop(); }
    static void reg() {
        M = mod(XS("Auto Shield"), XS("Combat"));
        range = sNum(XS("Range"), 4, 1, 8, "blocks");
        dropToAttack = sBool(XS("Drop To Attack"), true);
        maceOnly = sBool(XS("Mace Only"), false);   // only block an incoming mace smash, aiming at them
        LG[M].pre = pre; LG[M].disable = disable;
    }
}

// ================================================================ cobweb placement (shared by webs)
// Put a cobweb in the air/replaceable cell (cx,cy,cz) by clicking the top face of the solid block
// under it. Swaps to a cobweb and (optionally) back the same tick, the way Lunge does its spear swap.
namespace web {
    static bool place(int cx, int cy, int cz, bool swapBack) {
        int cf = blockFlags(cx, cy, cz);
        if (!(cf & (BF_AIR | BF_REPLACEABLE))) return false;          // cell must be fillable
        if (!(blockFlags(cx, cy - 1, cz) & BF_SOLID)) return false;   // need a floor to click against
        if (selfEye().dist({ cx + 0.5, cy + 0.5, cz + 0.5 }) > 4.5) return false;
        int slot = findHotbar(XS("minecraft:cobweb"));
        if (slot < 0) return false;
        int back = selSlot();
        if (back != slot) pressHotbar(slot);
        useItemOn(cx, cy - 1, cz, 1 /*UP face*/, cx + 0.5, cy, cz + 0.5, true);   // web lands in (cx,cy,cz)
        if (swapBack && selSlot() != back) pressHotbar(back);
        return true;
    }
}

// ================================================================ Stun Web
// Webs the feet of the player under your crosshair while you hold right-click: traps them mid-combo.
// Uses the silent aim angles if silent aim is active, otherwise your real crosshair.
namespace stunweb {
    static int M = -1;
    static SNum reach; static SRange delay; static SBool swapBack, needClick;
    static i64 nextAt;
    static void pre() {
        if (screenOpen() || usingItem() || TICKS < nextAt) return;
        if (needClick() && !sb(SF_USE_KEY)) return;
        Ent t = hasSilent() ? raycastEnt(silentYaw(), silentPitch(), reach()) : crosshairEnt(reach());
        if (!t.ok() || !t.player() || friendEnt(t)) return;
        Vec3 tp = t.pos();
        if (web::place(ifloor(tp.x), ifloor(tp.y), ifloor(tp.z), swapBack())) nextAt = TICKS + delay.pickInt();
    }
    static void disable() { nextAt = 0; }
    static void reg() {
        M = mod(XS("Stun Web"), XS("Combat"));
        reach = sNum(XS("Reach"), 4, 2, 6, "blocks");
        delay = sRange(XS("Delay"), 4, 6, 1, 40, "ticks");
        needClick = sBool(XS("Hold Right Click"), true);
        swapBack = sBool(XS("Swap Back"), true);
        LG[M].pre = pre; LG[M].disable = disable;
    }
}

// ================================================================ Auto Web
// Automatically webs the feet of the nearest hostile player in range. No input needed.
namespace autoweb {
    static int M = -1;
    static SNum range; static SRange delay; static SBool swapBack;
    static i64 nextAt;
    static void pre() {
        if (screenOpen() || usingItem() || TICKS < nextAt) return;
        snapEnts();
        Vec3 eye = selfEye(); double best = range() * range(); Ent target{ nullptr };
        for (int i = 0; i < entCount; i++) {
            Ent e = entAt(i);
            if (e.self() || e.dead() || !e.player() || friendEnt(e)) continue;
            double d = e.distSq(eye);
            if (d < best) { best = d; target = e; }
        }
        if (!target.ok()) return;
        Vec3 tp = target.pos();
        if (web::place(ifloor(tp.x), ifloor(tp.y), ifloor(tp.z), swapBack())) nextAt = TICKS + delay.pickInt();
    }
    static void disable() { nextAt = 0; }
    static void reg() {
        M = mod(XS("Auto Web"), XS("Combat"));
        range = sNum(XS("Range"), 4, 1, 6, "blocks");
        delay = sRange(XS("Delay"), 6, 10, 1, 60, "ticks");
        swapBack = sBool(XS("Swap Back"), true);
        LG[M].pre = pre; LG[M].disable = disable;
    }
}

// ================================================================ Auto Bridge
// Scaffold: while you hold a block, places one in the cell under your feet (and one block ahead in the
// direction you're moving) so you can bridge across gaps. Silent by default — places with useItemOn so
// the camera never snaps. Needs a solid neighbour to click against, like a hand-placed block does.
namespace autobridge {
    static int M = -1;
    static SNum lookAhead; static SBool groundOnly;
    // place a block in cell (bx,by,bz) by clicking a solid neighbour's shared face
    static bool placeAt(int bx, int by, int bz) {
        if (!(blockFlags(bx, by, bz) & (BF_AIR | BF_REPLACEABLE))) return false;
        if (selfEye().dist({ bx + 0.5, by + 0.5, bz + 0.5 }) > 4.5) return false;
        static const int N[6][3] = { {0,-1,0},{0,1,0},{-1,0,0},{1,0,0},{0,0,-1},{0,0,1} };
        static const int FACE[6] = { 1, 0, 5, 4, 3, 2 };   // the neighbour face pointing back at our cell
        for (int i = 0; i < 6; i++) {
            int nx = bx + N[i][0], ny = by + N[i][1], nz = bz + N[i][2];
            if (!(blockFlags(nx, ny, nz) & BF_SOLID)) continue;
            double hx = bx + 0.5 - N[i][0] * 0.5, hy = by + 0.5 - N[i][1] * 0.5, hz = bz + 0.5 - N[i][2] * 0.5;
            useItemOn(nx, ny, nz, FACE[i], hx, hy, hz, true);
            return true;
        }
        return false;
    }
    static void tick() {
        if (screenOpen() || usingItem() || pendUse() > 0) return;
        if (!sb(SF_HOLD_BLOCK)) return;                       // only when a placeable block is in hand
        if (groundOnly() && !onGround()) return;
        Vec3 p = selfPos();
        int by = ifloor(p.y) - 1, bx = ifloor(p.x), bz = ifloor(p.z);
        if (!(blockFlags(bx, by, bz) & BF_SOLID)) { placeAt(bx, by, bz); return; }   // hole under feet first
        double vx = SF[SF_VX], vz = SF[SF_VZ];
        if (vx * vx + vz * vz < 0.0009) return;               // standing still: nothing to bridge
        int fx = ifloor(p.x + vx * lookAhead()), fz = ifloor(p.z + vz * lookAhead());
        if (!(blockFlags(fx, by, fz) & BF_SOLID)) placeAt(fx, by, fz);
    }
    static void reg() {
        M = mod(XS("Auto Bridge"), XS("Automation"));
        lookAhead = sNum(XS("Look Ahead"), 2, 0, 3, "blocks");
        groundOnly = sBool(XS("Ground Only"), true);
        LG[M].tick = tick;
    }
}

// ================================================================ KB Displace
// Sprint-hit knockback has a bonus that is applied along the ATTACKER'S yaw, not position (vanilla).
// So by setting the server-side yaw to a chosen direction on the hit tick (silently, camera untouched),
// a sprint hit sends the target that way. No spoofed positions, no bad packets — just a look + sprint,
// both legal inputs. Registered after the aura so its silent yaw is the last word on the hit tick;
// if your aura fights it, we move this onto the aura instead.
//   Direction, relative to the you->target line:  Toward You | Left | Right.
namespace kbdisplace {
    static int M = -1;
    static SEnum dir; static SNum reach; static SBool reqSprint;
    static void pre() {
        if (screenOpen() || usingItem()) return;
        if (reqSprint() && !sb(SF_SPRINTING)) return;             // the bonus only lands on a sprint hit
        Ent t = hasSilent() ? raycastEnt(silentYaw(), silentPitch(), reach()) : crosshairEnt(reach());
        if (!t.ok() || !t.player() || friendEnt(t)) return;
        Vec3 me = selfEye(), tp = t.pos();
        double dx = tp.x - me.x, dz = tp.z - me.z;
        double len = dsqrt(dx * dx + dz * dz);
        if (len < 1e-4) return;
        dx /= len; dz /= len;
        double px, pz;                                            // world push direction
        switch (dir.idx()) {
            case 0: px = -dx; pz = -dz; break;                   // Toward You (pull in)
            case 1: px =  dz; pz = -dx; break;                   // Left of the you->target line
            default: px = -dz; pz =  dx; break;                  // Right
        }
        float yaw = wrapDeg((float)(toDeg(datan2(pz, px)) - 90.0));   // face the push dir -> sprint KB follows it
        double dy = tp.y + 0.9 - me.y;                           // keep pitch on the target so the hit stays in reach
        float pitch = (float)-toDeg(datan2(dy, len));
        setSilent(yaw, pitch);
    }
    static void reg() {
        M = mod(XS("KB Displace"), XS("Combat"));
        const char* D_[] = { XS("Toward You"), XS("Left"), XS("Right") };
        dir = sEnum(XS("Direction"), 0, D_, 3);
        reach = sNum(XS("Reach"), 3, 2, 5, "blocks");
        reqSprint = sBool(XS("Require Sprint"), true);
        LG[M].pre = pre;
    }
}

// ================================================================ Pearl Catch
// Pearl + wind-charge airborne combo. On the bind: throw an ender pearl along your current look (any
// angle), then a few ticks later aim a wind charge at the thrown pearl and throw it — the burst shoves
// the pearl further/up, so your teleport lands airborne. Aiming the wind charge uses silent rotation,
// so the UseItem packet is rewritten to that angle (same path as the silent-throw fix). Needs the pearl
// and a wind charge in your hotbar.
namespace pearlcatch {
    static int M = -1;
    static SNum windDelay, upLead; static SBool swapBack;
    enum St { IDLE, WAIT };
    static St st = IDLE; static int t = 0, startSlot = -1;
    static void reset() {
        if (swapBack() && startSlot >= 0 && selSlot() != startSlot) pressHotbar(startSlot);
        st = IDLE; t = 0; startSlot = -1;
    }
    static void bind() {
        if (st != IDLE || screenOpen() || usingItem()) return;
        int pearl = findHotbar(XS("minecraft:ender_pearl"));
        if (pearl < 0 || findHotbar(XS("minecraft:wind_charge")) < 0) return;
        startSlot = selSlot();
        if (selSlot() != pearl) pressHotbar(pearl);
        clickUse();                              // throw the pearl along your real look
        st = WAIT; t = 0;
    }
    static void tick() {
        if (st != WAIT) return;
        if (++t < (int)windDelay()) return;       // let the pearl clear your hitbox first
        if (D(D_PEARL_POS) != 1) { if (t > (int)windDelay() + 20) reset(); return; }
        double px = OUTB[0], py = OUTB[1], pz = OUTB[2];
        int wind = findHotbar(XS("minecraft:wind_charge"));
        if (wind < 0) { reset(); return; }
        Vec3 eye = selfEye();
        double dx = px - eye.x, dy = (py + upLead() * 0.1) - eye.y, dz = pz - eye.z;
        double dist = dsqrt(dx * dx + dz * dz);
        float yaw = wrapDeg((float)(toDeg(datan2(dz, dx)) - 90.0));
        float pitch = (float)-toDeg(datan2(dy, dist));
        setSilent(yaw, pitch);                    // UseItemPacketMixin rewrites the throw to this angle
        if (selSlot() != wind) pressHotbar(wind);
        clickUse();                               // throw the wind charge at the pearl
        reset();
    }
    static void disable() { reset(); }
    static void reg() {
        M = mod(XS("Pearl Catch"), XS("Combat"));
        windDelay = sNum(XS("Wind Delay"), 3, 1, 12, "ticks");
        upLead = sNum(XS("Up Lead"), 3, 0, 15, "blocks");
        swapBack = sBool(XS("Swap Back"), true);
        LG[M].bind = bind; LG[M].tick = tick; LG[M].disable = disable;
    }
}

// ================================================================ Charge Stack  (2b2t wind launch)
// Freezes you by holding the inbound explosion-knockback packets (Java-side, like Lag but for YOUR
// explode/velocity packets), spams wind charges straight down at your feet so each one stacks at full
// strength instead of pushing you off the spot, then releases the whole held queue at once — the client
// applies every stacked knockback together and you rocket up. Needs wind charges in your hotbar.
//   chargeHold() is polled by the Java ChargeStack service: true = hold, false = release the queue.
namespace chargestack {
    static int M = -1;
    static SNum count, interval; static SBool autoOff;
    static bool holding = false;
    static int st = 0;                       // 0 idle, 1 spamming, 2 settling before release
    static int thrown = 0, t = 0, startSlot = -1;
    static bool chargeHold() { return holding; }
    static void start() {
        if (st != 0 || screenOpen() || usingItem()) return;
        if (findHotbar(XS("minecraft:wind_charge")) < 0) return;
        startSlot = selSlot();
        holding = true; st = 1; thrown = 0; t = 0;      // begin holding the knockback packets
    }
    static void tick() {
        if (st == 0) return;
        if (st == 1) {
            if (t % (int)interval() == 0 && thrown < (int)count()) {
                int w = findHotbar(XS("minecraft:wind_charge"));
                if (w < 0) { st = 2; t = 0; }
                else {
                    setSilent(selfYaw(), 90.f);          // aim straight down at your feet
                    if (selSlot() != w) pressHotbar(w);
                    clickUse();                          // throw a wind charge at the ground
                    thrown++;
                }
            }
            t++;
            if (thrown >= (int)count()) { st = 2; t = 0; }
        } else if (st == 2) {
            // let the last charge's knockback packet arrive and queue, then release everything -> launch
            if (++t >= 3) {
                holding = false; st = 0;                 // Java flushes the held queue now
                if (startSlot >= 0 && selSlot() != startSlot) pressHotbar(startSlot);
                if (autoOff()) setOn(M, false);
            }
        }
    }
    static void disable() { holding = false; st = 0; thrown = 0; t = 0; }   // toggling off also releases
    static void bind() { start(); }
    static void reg() {
        M = mod(XS("Charge Stack"), XS("Movement"));
        count = sNum(XS("Charges"), 8, 1, 40, "");
        interval = sNum(XS("Interval"), 2, 1, 10, "ticks");
        autoOff = sBool(XS("Auto Off"), true);
        LG[M].bind = bind; LG[M].tick = tick; LG[M].disable = disable;
    }
}

// ================================================================ Translation
// Set your native language; incoming chat in any other language is auto-translated into it (Java does
// the lookup via an online translator). Pushes your language code to Java (T_SET_NATIVE_LANG) on change.
namespace translate {
    static int M = -1;
    static SEnum lang;
    static const char* CODES[15] = { "en","es","ru","fr","de","pt","zh","ja","ko","it","tr","ar","uk","pl","nl" };
    static char lastSent[8];
    static void push(const char* c) { if (!eq(c, lastSent)) { T(T_SET_NATIVE_LANG, c); copy(lastSent, c, 8); } }
    static void tick() { int i = lang.idx(); push(CODES[i < 0 ? 0 : i > 14 ? 14 : i]); }
    static void disable() { push(""); }
    static void reg() {
        M = mod(XS("Translation"), XS("Settings"));
        const char* L[] = { XS("English"), XS("Spanish"), XS("Russian"), XS("French"), XS("German"),
                            XS("Portuguese"), XS("Chinese"), XS("Japanese"), XS("Korean"), XS("Italian"),
                            XS("Turkish"), XS("Arabic"), XS("Ukrainian"), XS("Polish"), XS("Dutch") };
        lang = sEnum(XS("Native Language"), 0, L, 15);
        LG[M].tick = tick; LG[M].disable = disable;
    }
}

// ================================================================ Through Walls
// Lets your combat target selection ignore blocks, so the aura / TBot can reach a player behind a wall
// (servers that don't check line-of-sight for attacks accept the hit). Java reads Q_THRU_WALLS.
namespace thruwalls {
    static int M = -1;
    static void reg() { M = mod(XS("Through Walls"), XS("Combat")); }
}

// ================================================================ Auto Tool
// Pulls out the best tool for whatever's under your crosshair: axe for wood, pickaxe for stone/ore,
// shovel for dirt/sand, hoe for leaves/plants, shears for cobweb — and for a player, your best weapon,
// or an axe if they're holding a shield (to break it).
namespace autotool {
    static int M = -1;
    static SBool weapons, swapBack;
    static int backSlot = -1, switchedTo = -1;
    static bool ct(const char* s, const char* sub) {
        for (int i = 0; s[i]; i++) { int j = 0; while (sub[j] && s[i + j] == sub[j]) j++; if (!sub[j]) return true; }
        return false;
    }
    static int bestWeapon() {
        int s = findHotbarSuffix("_sword"); if (s >= 0) return s;
        int m = findHotbar(XS("minecraft:mace")); if (m >= 0) return m;
        return findHotbarSuffix("_axe");
    }
    static int toolForBlock(const char* id) {
        if (ct(id, "_log") || ct(id, "_wood") || ct(id, "planks") || ct(id, "_stem") || ct(id, "chest")
            || ct(id, "crafting") || ct(id, "bookshelf") || ct(id, "barrel") || ct(id, "pumpkin") || ct(id, "melon")
            || ct(id, "ladder") || ct(id, "sign") || ct(id, "fence") || ct(id, "door")) return findHotbarSuffix("_axe");
        if (ct(id, "stone") || ct(id, "_ore") || ct(id, "deepslate") || ct(id, "cobble") || ct(id, "obsidian")
            || ct(id, "netherrack") || ct(id, "blackstone") || ct(id, "basalt") || ct(id, "brick") || ct(id, "concrete")
            || ct(id, "terracotta") || ct(id, "anchor") || ct(id, "furnace") || ct(id, "amethyst") || ct(id, "metal")
            || ct(id, "iron") || ct(id, "copper") || ct(id, "gold_block") || ct(id, "netherite")) return findHotbarSuffix("_pickaxe");
        if (ct(id, "dirt") || ct(id, "grass_block") || ct(id, "sand") || ct(id, "gravel") || ct(id, "podzol")
            || ct(id, "mud") || ct(id, "clay") || ct(id, "soul_") || ct(id, "path") || ct(id, "farmland")) return findHotbarSuffix("_shovel");
        if (ct(id, "leaves") || ct(id, "hay") || ct(id, "wart") || ct(id, "sculk") || ct(id, "moss")
            || ct(id, "sponge") || ct(id, "target") || ct(id, "dried_kelp")) return findHotbarSuffix("_hoe");
        if (ct(id, "cobweb")) { int sh = findHotbar(XS("minecraft:shears")); return sh >= 0 ? sh : findHotbarSuffix("_sword"); }
        return -1;
    }
    static int pick() {
        Ent t = hasSilent() ? raycastEnt(silentYaw(), silentPitch(), 4.0) : crosshairEnt(4.0);
        if (t.ok() && t.player() && !friendEnt(t)) {
            if (!weapons()) return -1;
            return t.shield() ? findHotbarSuffix("_axe") : bestWeapon();
        }
        double h[7];
        if (!crosshairBlock(h)) return -1;
        char id[64];
        if (!blockId((int)h[0], (int)h[1], (int)h[2], id, 64)) return -1;
        return toolForBlock(id);
    }
    static void pre() {
        if (screenOpen() || usingItem()) return;
        int want = pick();
        if (want >= 0) {
            if (selSlot() != want) {
                if (switchedTo < 0) backSlot = selSlot();     // first switch: remember your slot
                pressHotbar(want); switchedTo = want;
            }
        } else if (switchedTo >= 0) {
            if (swapBack() && selSlot() == switchedTo && backSlot >= 0) pressHotbar(backSlot);
            switchedTo = -1; backSlot = -1;
        }
    }
    static void disable() { switchedTo = -1; backSlot = -1; }
    static void reg() {
        M = mod(XS("Auto Tool"), XS("Automation"));
        weapons = sBool(XS("Weapons"), true);
        swapBack = sBool(XS("Swap Back"), false);
        LG[M].pre = pre; LG[M].disable = disable;
    }
}

} // namespace z
