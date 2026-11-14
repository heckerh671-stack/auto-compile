// Zalard native logic: modules + settings. They live directly in the click GUI's model (MM / SS),
// so the GUI edits exactly what the modules read; there's no syncing in between any more.
#pragma once

namespace z {

// ---------------------------------------------------------------- per-module logic
struct Logic {
    void (*tick)();
    void (*pre)();          // right before vanilla reads keys
    void (*frame)();        // every rendered frame
    void (*render)();       // world pass (ESP)
    void (*hud)();          // HUD pass
    void (*enable)();
    void (*disable)();
    void (*bind)();         // bind = action instead of toggle (while on)
    bool alwaysOn;
    bool on;                // last state the hooks were told about
    bool bindHeld;
};
static Logic LG[MAX_M];

static float DEFV[MAX_S], DEFV2[MAX_S];
static const char* DEFT[MAX_S];
static bool (*VIS[MAX_S])();
static bool HIDDEN[MAX_S];                 // saved, but never a row in the GUI (HUD positions etc.)
static bool dirty;                         // config needs writing

static int curMod = -1;
static int mod(const char* name, const char* cat, bool alwaysOn = false) {
    int m = newModule(name, cat, alwaysOn);
    curMod = m;
    if (m >= 0) { LG[m] = Logic{}; LG[m].alwaysOn = alwaysOn; if (alwaysOn) SS[MM[m].sToggle].v = 1; }
    return m;
}
static int addS(const char* name, int type) {
    if (curMod < 0) return -1;
    Module& m = MM[curMod];
    if (m.setCount >= MAX_MOD_SETTINGS) return -1;
    int i = newSetting(name, type);
    if (i < 0) return -1;
    m.set[m.setCount++] = i;
    return i;
}
static void remember(int i) { DEFV[i] = SS[i].v; DEFV2[i] = SS[i].v2; DEFT[i] = nullptr; }

/** Slider step from the range: fine for small ranges, whole numbers for big ones (as before). */
static float stepFor(float mn, float mx) { float s = mx - mn; return s <= 2 ? 0.01f : s <= 30 ? 0.1f : 1.f; }

struct SBool {
    int id = -1;
    bool operator()() const { return id >= 0 && SS[id].v != 0; }
    void set(bool v) const { if (id >= 0) SS[id].v = v ? 1.f : 0.f; }
};
struct SNum {
    int id = -1;
    float operator()() const { return id >= 0 ? SS[id].v : 0.f; }
    void set(float v) const { if (id >= 0) SS[id].v = v; }
};
struct SRange {
    int id = -1;
    float lo() const { return SS[id].v; }
    float hi() const { return SS[id].v2; }
    float pick() const { float a = lo(), b = hi(); return b <= a ? a : a + RNG.nextFloat() * (b - a); }
    /** Random whole number in [lo, hi]: "1 - 4" gives 1, 2, 3 or 4. */
    int pickInt() const { int a = iround(lo()), b = iround(hi()); return b <= a ? a : a + RNG.nextInt(b - a + 1); }
    i64 pickTicksMs() const { return (i64)pickInt() * 50; }
};
struct SEnum {
    int id = -1;
    int idx() const { return id >= 0 ? (int)SS[id].v : 0; }
    const char* str() const { int i = idx(); return id >= 0 && i >= 0 && i < SS[id].optCount ? SS[id].opts[i] : ""; }
    bool is(const char* o) const { return eq(str(), o); }
};
struct SText {
    int id = -1;
    const char* operator()() const { return id >= 0 ? SS[id].text : ""; }
};

static SBool sBool(const char* name, bool def) { int i = addS(name, T_BOOL); if (i >= 0) { SS[i].v = def; remember(i); } return { i }; }
static SNum sNum(const char* name, float def, float mn, float mx, const char* unit = "") {
    int i = addS(name, T_NUM);
    if (i >= 0) { Setting& s = SS[i]; s.v = def; s.mn = mn; s.mx = mx > mn ? mx : mn + 1; s.step = stepFor(mn, mx); s.unit = unit; remember(i); }
    return { i };
}
static SRange sRange(const char* name, float lo, float hi, float mn, float mx, const char* unit = "") {
    int i = addS(name, T_RANGE);
    if (i >= 0) { Setting& s = SS[i]; s.v = lo; s.v2 = hi; s.mn = mn; s.mx = mx; s.step = (mx - mn) <= 2 ? 0.05f : 1.f; s.unit = unit; remember(i); }
    return { i };
}
static SEnum sEnum(const char* name, int def, const char* const* opts, int n) {
    int i = addS(name, T_ENUM);
    if (i >= 0) {
        Setting& s = SS[i];
        for (int k = 0; k < n && k < MAX_OPTS; k++) s.opts[s.optCount++] = intern(opts[k]);
        s.v = (float)def; remember(i);
    }
    return { i };
}
static SText sText(const char* name, const char* def, const char* hint) {
    int i = addS(name, T_TEXT);
    if (i >= 0) { copy(SS[i].text, def, 160); SS[i].hint = hint; remember(i); DEFT[i] = def; }
    return { i };
}
/** A number that's saved but never shown (HUD element spots, panel spots). */
static SNum sHidden(const char* name, float def, float mn, float mx) {
    SNum n = sNum(name, def, mn, mx);
    if (n.id >= 0) { SS[n.id].visible = false; HIDDEN[n.id] = true; }
    return n;
}
template <class S> static S vis(S s, bool (*f)()) { if (s.id >= 0) VIS[s.id] = f; return s; }

// ---------------------------------------------------------------- module state
static bool isOn(int m) { return m >= 0 && SS[MM[m].sToggle].v != 0; }
static int bindOf(int m) { return (int)SS[MM[m].sBind].v; }
static void setOn(int m, bool v) {
    if (m < 0) return;
    if (LG[m].alwaysOn) v = true;
    SS[MM[m].sToggle].v = v ? 1.f : 0.f;
}

/** Runs onEnable / onDisable for every module whose on/off changed (GUI click, bind, config). */
static void syncStates() {
    for (int m = 0; m < mCount; m++) {
        if (LG[m].alwaysOn && !isOn(m)) setOn(m, true);
        bool on = isOn(m);
        if (on == LG[m].on) continue;
        LG[m].on = on;
        if (on && LG[m].enable) LG[m].enable();
        if (!on && LG[m].disable) LG[m].disable();
        dirty = true;
        char b[96]; Str(b, 96).s(MM[m].name).s(on ? XS(" on") : XS(" off"));
        logLine(b);
    }
}

/** The GUI's Reset: every setting of the module back to its default (on/off + bind stay). */
static void resetModule(const char* name) {
    for (int m = 0; m < mCount; m++) {
        if (!eq(MM[m].name, name)) continue;
        for (int k = 0; k < MM[m].setCount; k++) {
            int i = MM[m].set[k];
            if (HIDDEN[i]) continue;
            SS[i].v = DEFV[i]; SS[i].v2 = DEFV2[i];
            if (DEFT[i]) copy(SS[i].text, DEFT[i], 160);
        }
        dirty = true;
    }
}

/** Visibility predicates -> GUI rows (every GUI frame). */
static void pushVisibility() {
    for (int i = 0; i < sCount; i++) if (VIS[i]) SS[i].visible = !HIDDEN[i] && VIS[i]();
}

} // namespace z
