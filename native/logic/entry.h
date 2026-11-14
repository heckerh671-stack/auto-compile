// Zalard native logic: JNI entry points (dev.zalard.nat.Z) and the hooks the GUI calls.
#pragma once

namespace z {

static bool booted;

static void registerModules() {
    // Combat (TBot on top)
    tbot::reg(); aim::reg(); crystal::reg(); anchor::reg(); mace::reg(); cart::reg(); shield::reg(); combo::reg();
    lag::reg(); autoshield::reg(); stunweb::reg(); autoweb::reg(); kbdisplace::reg(); pearlcatch::reg();
    chargestack::reg(); thruwalls::reg(); autotool::reg();
    // Movement
    velocity::reg(); lunge::reg(); noslow::reg(); flight::reg(); motion::reg();
    // Automation
    totem::reg(); fastplace::reg(); nofall::reg(); autobridge::reg();
    // Render
    esp::reg(); freecam::reg(); xray::reg(); nooverlay::reg(); visuals::reg(); drip::reg(); spotify::reg();
    camclip::reg(); hitbox::reg(); translate::reg();
    // Misc
    spoof::reg();
    // Settings (under the gear)
    hud::reg();
    panelsBuilt = false;
}

static void keybinds() {
    // the GUI key opens the click GUI
    static bool guiHeld;
    bool g = D(D_BIND_HELD, guiKey) == 1;
    if (g && !guiHeld && !screenOpen()) D(D_OPEN_GUI);
    guiHeld = g;
    for (int m = 0; m < mCount; m++) {
        int key = bindOf(m);
        bool held = (key > 0 || (key >= -98 && key <= -96)) && D(D_BIND_HELD, key) == 1;
        if (held && !LG[m].bindHeld) {
            if (LG[m].bind && isOn(m)) LG[m].bind();
            else if (!LG[m].alwaysOn) setOn(m, !isOn(m));
        }
        LG[m].bindHeld = held;
    }
}

static void tickAll() {
    keybinds();
    syncStates();
    lock::tick();
    for (int m = 0; m < mCount; m++) if (isOn(m) && LG[m].tick) LG[m].tick();
    // after the modules: this tick's aim requests go out with THIS tick's movement packet
    rot::tick();
    if (dirty) saveMain();
}
static void preInputAll() {
    lock::observeClicks();
    rot::preInput();
    for (int m = 0; m < mCount; m++) if (isOn(m) && LG[m].pre) LG[m].pre();
}

// ---- GUI hooks (declared before the GUI's JNI functions)
void guiFrameHook(JNIEnv* env) {
    E = env;
    if (!booted) return;
    pushVisibility();
    drainActions();
}
void guiOpenHook(JNIEnv* env) {
    E = env;
    if (!booted) return;
    guiOpenNow = true;
    refreshLists();
    hud::pushPanels();
}
void guiCloseHook(JNIEnv* env) {
    E = env;
    if (!booted) return;
    guiOpenNow = false;
    heCount = 0;
    saveMain();
}

} // namespace z

// ---------------------------------------------------------------- JNI: dev.zalard.nat.Z
#define ZFN(ret, name) static ret JNICALL z_##name

ZFN(void, boot)(JNIEnv* env, jclass, jlong nowMs, jlong nanoTime, jlong tokHi, jlong tokLo) {
    using namespace z;
    if (booted) return;
    // jar<->DLL binding + anti-debug, run as VM bytecode (vm.h) instead of a plain check: on a
    // mismatch / a debugger, just never start (silent).
    if (!vm::guard((u64)tokHi, (u64)tokLo)) return;
    enter(env, nowMs); NOWNS = nanoTime;
    RNG.seed((u64)nanoTime ^ ((u64)nowMs << 20));
    bridgeSetup(env);
    if (!bridgeOk) return;
    registerModules();
    readConfig(XS("config.json"));
    loadFriends();
    char a[64];
    if (R(R_READ_FILE, XS("active-config.txt"), a, 64)) { int n = len(a); while (n && (a[n - 1] == '\n' || a[n - 1] == ' ' || a[n - 1] == '\r')) a[--n] = 0; copy(activeConfig, a, 64); }
    for (int m = 0; m < mCount; m++) { if (LG[m].alwaysOn) setOn(m, true); LG[m].on = isOn(m); }
    booted = true;
    char b[96]; Str(b, 96).s(XS("zl ")).i(mCount);
    logLine(b);
}
ZFN(void, tick)(JNIEnv* env, jclass, jlong nowMs, jlong nanoTime) {
    using namespace z;
    if (!booted) return;
    enter(env, nowMs); NOWNS = nanoTime; cam::nowNs = nanoTime;
    // anti-debug, checked now and then: if a debugger attaches later, wind down after a random delay
    // (so the stop isn't obviously next to the check) by quietly killing the logic.
    static i64 disableAt = -1;
    if ((TICKS & 127) == 0 && disableAt < 0 && debuggerPresent()) disableAt = nowMs + 1500 + (i64)(RNG.nextDouble() * 4000);
    if (disableAt > 0 && nowMs >= disableAt) { booted = false; return; }
    if (!snapSelf()) { syncStates(); if (dirty) saveMain(); return; }
    TICKS++;
    tickAll();
}
ZFN(void, preInput)(JNIEnv* env, jclass, jlong nowMs, jlong nanoTime) {
    using namespace z;
    if (!booted) return;
    enter(env, nowMs); NOWNS = nanoTime; cam::nowNs = nanoTime;
    if (!snapSelf()) return;
    preInputAll();
}
ZFN(void, frame)(JNIEnv* env, jclass, jlong nowMs, jlong nanoTime) {
    using namespace z;
    if (!booted) return;
    enter(env, nowMs); NOWNS = nanoTime; cam::nowNs = nanoTime;
    cam::frame();
    bool needSelf = false;
    for (int m = 0; m < mCount; m++) if (isOn(m) && LG[m].frame) needSelf = true;
    if (!needSelf || !snapSelf()) return;
    for (int m = 0; m < mCount; m++) if (isOn(m) && LG[m].frame) LG[m].frame();
}
ZFN(void, renderWorld)(JNIEnv* env, jclass, jlong nowMs, jlong nanoTime) {
    using namespace z;
    if (!booted) return;
    enter(env, nowMs); NOWNS = nanoTime;
    if (!snapSelf()) return;
    for (int m = 0; m < mCount; m++) if (isOn(m) && LG[m].render) LG[m].render();
}
ZFN(void, renderHud)(JNIEnv* env, jclass, jlong nowMs, jlong nanoTime) {
    using namespace z;
    if (!booted) return;
    enter(env, nowMs); NOWNS = nanoTime;
    if (!snapSelf()) return;
    for (int m = 0; m < mCount; m++) if (isOn(m) && LG[m].hud) LG[m].hud();
}
ZFN(void, save)(JNIEnv* env, jclass) {
    using namespace z;
    if (!booted) return;
    E = env;
    saveMain();
}
ZFN(void, onAttack)(JNIEnv*, jclass, jint id, jboolean player, jboolean living, jboolean sprinting) {
    using namespace z;
    if (!living) return;
    ev::lastHitId = id; ev::lastHitPlayer = player; ev::lastHitSprinting = sprinting; ev::lastHitMs = NOW; ev::hitSeq++;
}
ZFN(void, onKnockback)(JNIEnv*, jclass, jdouble x, jdouble y, jdouble zz, jlong nowMs) {
    using namespace z;
    ev::kbX = x; ev::kbY = y; ev::kbZ = zz; ev::kbMs = nowMs; ev::kbSeq++;
}
ZFN(void, onExplosion)(JNIEnv*, jclass, jlong nowMs) { z::ev::explosionMs = nowMs; }
ZFN(jdoubleArray, filterKnockback)(JNIEnv* env, jclass, jdouble x, jdouble y, jdouble zz, jboolean explosion, jlong nowMs) {
    using namespace z;
    if (!booted || !isOn(velocity::M)) return nullptr;
    E = env; NOW = nowMs;
    double o[3];
    if (!velocity::filter(x, y, zz, explosion, o)) return nullptr;
    jdoubleArray a = env->NewDoubleArray(3);
    if (a) env->SetDoubleArrayRegion(a, 0, 3, o);
    return a;
}
ZFN(jlong, pingDelay)(JNIEnv*, jclass, jint shown) { return z::booted ? (jlong)z::spoof::pingDelay(shown) : 0; }
ZFN(jlong, lagConfig)(JNIEnv*, jclass) {
    using namespace z;
    return booted ? (jlong)lag::config() : 0;
}
ZFN(jboolean, chargeHold)(JNIEnv*, jclass) {
    using namespace z;
    return (booted && chargestack::chargeHold()) ? JNI_TRUE : JNI_FALSE;
}
ZFN(jint, q)(JNIEnv*, jclass, jint what) {
    using namespace z;
    if (!booted) return what == 5 ? 1 : 0;
    switch (what) {
    case 1: return tbot::holdsSprint();
    case 2: return isOn(noslow::M);
    case 3: return combo::suppressingFwd();
    case 4: return combo::forcingBk();
    case 5: return rot::showHead();
    case 6: return spoof::pingSpoof();
    case 7: return isOn(xray::M);
    case 8: return nooverlay::bits();
    case 9: return visuals::skyARGB();
    case 10: return visuals::flags();
    case 11: return isOn(camclip::M);
    case 12: return hitbox::q();
    case 13: return isOn(thruwalls::M);
    }
    return 0;
}
