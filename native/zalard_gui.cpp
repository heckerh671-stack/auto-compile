// Zalard - Copyright (c) 2026 weksom. All rights reserved. Proprietary; see LICENSE.
// Mako GUI — native core (ClickGUI layout).
// Owns module/setting state, layout, input, animation and produces a vertex
// list that the Java side draws with one shader. Built freestanding (no CRT):
// no heap, no static constructors, no exceptions. Everything is set up in init().

#include "jni.h"
#include "font_atlas.h"

typedef unsigned int u32;
typedef unsigned char u8;
typedef decltype(sizeof 0) usize;

// ---------------------------------------------------------------- CRT shims
#ifndef MAKO_HARNESS
extern "C" {
int _fltused = 0;
void* memset(void* d, int c, usize n) { volatile u8* p = (u8*)d; while (n--) *p++ = (u8)c; return d; }
void* memcpy(void* d, const void* s, usize n) { volatile u8* p = (u8*)d; const u8* q = (const u8*)s; while (n--) *p++ = *q++; return d; }
void* memmove(void* d, const void* s, usize n) {
    volatile u8* p = (u8*)d; const u8* q = (const u8*)s;
    if (p < q) while (n--) *p++ = *q++; else { p += n; q += n; while (n--) *--p = *--q; }
    return d;
}
int memcmp(const void* a, const void* b, usize n) { const u8* p = (const u8*)a; const u8* q = (const u8*)b; for (; n; n--, p++, q++) if (*p != *q) return *p - *q; return 0; }
}
#else
#include <string.h>
#endif

// ---------------------------------------------------------------- math
static inline float fminf_(float a, float b) { return a < b ? a : b; }
static inline float fmaxf_(float a, float b) { return a > b ? a : b; }
static inline float clampf(float v, float a, float b) { return v < a ? a : v > b ? b : v; }
static inline float fabsf_(float v) { return v < 0 ? -v : v; }
static inline float floorf_(float v) { int i = (int)v; return (float)(v < (float)i ? i - 1 : i); }
static inline float roundf_(float v) { return floorf_(v + 0.5f); }
static inline float lerpf(float a, float b, float t) { return a + (b - a) * t; }
static inline float easeOut(float t) { t = t < 0 ? 0 : t > 1 ? 1 : t; float u = 1 - t; return 1 - u * u * u; }
/** Frame-rate independent exponential approach. */
static inline float approach(float cur, float target, float speed, float dt) {
    float t = 1.0f - 1.0f / (1.0f + speed * dt + 0.5f * speed * speed * dt * dt);
    return lerpf(cur, target, clampf(t, 0, 1));
}

static int slen(const char* s) { int n = 0; while (s[n]) n++; return n; }
static bool seq(const char* a, const char* b) { while (*a && *a == *b) { a++; b++; } return *a == *b; }
static char lower(char c) { return (c >= 'A' && c <= 'Z') ? c + 32 : c; }
static bool containsCI(const char* hay, const char* needle) {
    int n = slen(needle); if (!n) return true;
    for (int i = 0; hay[i]; i++) { int j = 0; while (j < n && hay[i + j] && lower(hay[i + j]) == lower(needle[j])) j++; if (j == n) return true; }
    return false;
}
static void scpy(char* d, const char* s, int cap) { int i = 0; for (; s[i] && i < cap - 1; i++) d[i] = s[i]; d[i] = 0; }
static void scat(char* d, const char* s, int cap) { int n = slen(d); scpy(d + n, s, cap - n); }
static void itoa_(int v, char* out) {
    char t[16]; int n = 0; bool neg = v < 0; unsigned u = neg ? -v : v;
    do { t[n++] = '0' + u % 10; u /= 10; } while (u);
    int k = 0; if (neg) out[k++] = '-'; while (n) out[k++] = t[--n]; out[k] = 0;
}
static void fmtNum(float v, float step, char* out) {
    if (step >= 1) { itoa_((int)roundf_(v), out); return; }
    if (step < 0.1f) {
        int t = (int)roundf_(v * 100); bool neg = t < 0; if (neg) t = -t;
        char a[16]; itoa_(t / 100, a); out[0] = 0; if (neg) scat(out, "-", 24);
        scat(out, a, 24); scat(out, ".", 24);
        char d[3] = { (char)('0' + (t / 10) % 10), (char)('0' + t % 10), 0 }; scat(out, d, 24); return;
    }
    int t = (int)roundf_(v * 10); bool neg = t < 0; if (neg) t = -t;
    char a[16]; itoa_(t / 10, a);
    out[0] = 0; if (neg) scat(out, "-", 24);
    scat(out, a, 24); scat(out, ".", 24); char d[2] = { (char)('0' + t % 10), 0 }; scat(out, d, 24);
}

// ---------------------------------------------------------------- colors
#define C_BACKDROP  0xF5020202u
#define C_PANEL_T   0xFF141414u
#define C_PANEL_B   0xFF0D0D0Du
#define C_DEEP      0xFF060606u
#define C_BORDER    0xFF1F1F1Fu
#define C_BORDER_HI 0xFF363636u
#define C_TEXT      0xFFEDEDEDu
#define C_TEXT_DIM  0xFFB8B8B8u
#define C_MUTED     0xFF8C8C8Cu
#define C_TAB_IDLE  0xFF7A7A7Au
#define C_TAB_HOVER 0xFFB4B4B4u
#define C_TAB_PILL  0xFF232323u
#define C_TRACK     0xFF262626u
#define C_FILL      0xFFE2E2E2u
#define C_KNOB_OFF  0xFFFFFFFFu
#define C_KNOB_ON   0xFF101010u
#define C_TOGGLE_ON 0xFFDADADAu
#define C_DIMFILL   0xFF5E5E5Eu

static u32 mixc(u32 a, u32 b, float t) {
    t = clampf(t, 0, 1);
    u32 r = 0;
    for (int s = 0; s < 32; s += 8) {
        float x = (float)((a >> s) & 255), y = (float)((b >> s) & 255);
        r |= ((u32)(x + (y - x) * t + 0.5f) & 255) << s;
    }
    return r;
}

// ---------------------------------------------------------------- draw list
struct Vtx { float x, y, u, v, p0, p1, p2, p3, r, g, b, a, c0, c1, c2, c3, kind; };
#define MAX_VTX 120000
static Vtx VB[MAX_VTX];
static int vcount;
static float S = 1;                  // virtual -> pixel scale
static float clipPx[4];              // current clip in pixels
static float gA = 1;                 // global alpha multiplier for fades

static void setClip(float x0, float y0, float x1, float y1) { clipPx[0] = x0 * S; clipPx[1] = y0 * S; clipPx[2] = x1 * S; clipPx[3] = y1 * S; }
static void noClip() { clipPx[0] = -1e6f; clipPx[1] = -1e6f; clipPx[2] = 1e6f; clipPx[3] = 1e6f; }

static void vtx(float x, float y, float u, float v, const float* p, u32 c, float kind) {
    if (vcount >= MAX_VTX) return;
    Vtx& o = VB[vcount++];
    o.x = x; o.y = y; o.u = u; o.v = v;
    o.p0 = p[0]; o.p1 = p[1]; o.p2 = p[2]; o.p3 = p[3];
    o.r = ((c >> 16) & 255) / 255.f; o.g = ((c >> 8) & 255) / 255.f; o.b = (c & 255) / 255.f; o.a = (c >> 24) / 255.f * gA;
    o.c0 = clipPx[0]; o.c1 = clipPx[1]; o.c2 = clipPx[2]; o.c3 = clipPx[3];
    o.kind = kind;
}
/** Quad in pixel space; top/bottom colors give a vertical gradient. */
static void quad(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1,
                 const float* p, u32 top, u32 bot, float kind) {
    if (x1 < clipPx[0] || x0 > clipPx[2] || y1 < clipPx[1] || y0 > clipPx[3]) return;
    vtx(x0, y0, u0, v0, p, top, kind); vtx(x1, y0, u1, v0, p, top, kind); vtx(x1, y1, u1, v1, p, bot, kind);
    vtx(x0, y0, u0, v0, p, top, kind); vtx(x1, y1, u1, v1, p, bot, kind); vtx(x0, y1, u0, v1, p, bot, kind);
}

// kind 0: rounded rect (p = cx,cy,hx,hy ; u = radius)
static void rrect(float x, float y, float w, float h, float r, u32 top, u32 bot) {
    if (w <= 0 || h <= 0) return;
    float X = x * S, Y = y * S, W = w * S, H = h * S, R = fminf_(r * S, fminf_(W, H) * 0.5f);
    float p[4] = { X + W * 0.5f, Y + H * 0.5f, W * 0.5f, H * 0.5f };
    quad(X - 1, Y - 1, X + W + 1, Y + H + 1, R, 0, R, 0, p, top, bot, 0);
}
static void rrect(float x, float y, float w, float h, float r, u32 c) { rrect(x, y, w, h, r, c, c); }
/** Card = 1px border + fill (border stays 1 physical pixel at any scale). */
static void card(float x, float y, float w, float h, float r, u32 border, u32 top, u32 bot) {
    rrect(x, y, w, h, r, border);
    float o = 1.0f / S;
    rrect(x + o, y + o, w - 2 * o, h - 2 * o, r - o, top, bot);
}
static void card(float x, float y, float w, float h, float r, u32 border, u32 fill) { card(x, y, w, h, r, border, fill, fill); }
static void circle(float cx, float cy, float d, u32 c) { rrect(cx - d / 2, cy - d / 2, d, d, d / 2, c); }
// kind 2: capsule segment (p = ax,ay,bx,by ; u = half thickness)
static void seg(float ax, float ay, float bx, float by, float thick, u32 c) {
    float A = ax * S, B = ay * S, C = bx * S, D = by * S, t = thick * S * 0.5f;
    float p[4] = { A, B, C, D };
    quad(fminf_(A, C) - t - 1, fminf_(B, D) - t - 1, fmaxf_(A, C) + t + 1, fmaxf_(B, D) + t + 1, t, 0, t, 0, p, c, c, 2);
}
// kind 3: ring (p = cx,cy,radius,halfThickness)
static void ring(float cx, float cy, float rad, float thick, u32 c) {
    float X = cx * S, Y = cy * S, R = rad * S, t = thick * S * 0.5f;
    float p[4] = { X, Y, R, t };
    quad(X - R - t - 1, Y - R - t - 1, X + R + t + 1, Y + R + t + 1, 0, 0, 0, 0, p, c, c, 3);
}

// ---------------------------------------------------------------- text
static int glyphIndex(const char*& s) {
    u8 c = (u8)*s++;
    if (c < 0x80) return (c >= 32 && c < 127) ? c - 32 : 31; // '?'
    unsigned cp = 0; int extra = 0;
    if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; extra = 1; }
    else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; extra = 2; }
    else { cp = c & 0x07; extra = 3; }
    while (extra-- && (*s & 0xC0) == 0x80) cp = (cp << 6) | ((u8)*s++ & 0x3F);
    for (int i = 0; i < GLYPH_COUNT - 95; i++) if (EXTRA_CPS[i] == cp) return 95 + i;
    return 31;
}
#ifdef FACE_PIX
/** Pixel faces: size snapped so one font pixel is a whole number of screen pixels (crisp, even strokes). */
static float snapSize(int face, float size) {
    (void)face;
    return size;   // Inter is a smooth SDF font — no pixel-grid size snapping needed
}
#else
static float snapSize(int, float size) { return size; }
#endif
static float snapTrack(float track) { return track == 0 ? 0 : roundf_(track * S) / S; }
static float textW(int face, const char* s, float size, float track = 0) {
    size = snapSize(face, size); track = snapTrack(track);
    float w = 0; int n = 0;
    while (*s) { int gi = glyphIndex(s); w += GLYPHS[face][gi].adv * size + track; n++; }
    return n ? w - track : 0;
}
/** Draws text with its cap height centered on cy. Returns width. */
static float text(int face, const char* s, float x, float cy, float size, u32 col, float track = 0) {
    size = snapSize(face, size); track = snapTrack(track);
    float base = roundf_((cy + CAP_H[face] * size * 0.5f) * S);
    float pen = roundf_(x * S), sz = size * S, start = x;
    float p[4] = { 0, 0, 0, 0 };
    while (*s) {
        const GlyphInfo& g = GLYPHS[face][glyphIndex(s)];
        if (g.w) {
            float gx = pen + g.xoff * sz, gy = base + g.yoff * sz;
            float gw = g.w * sz / ATLAS_EM, gh = g.h * sz / ATLAS_EM;
            quad(gx, gy, gx + gw, gy + gh,
                 (float)g.x / ATLAS_W, (float)g.y / ATLAS_H, (float)(g.x + g.w) / ATLAS_W, (float)(g.y + g.h) / ATLAS_H,
                 p, col, col, 1);
        }
        pen += g.adv * sz + track * S;
    }
    return pen / S - start;
}
static void textR(int face, const char* s, float right, float cy, float size, u32 col, float track = 0) {
    text(face, s, right - textW(face, s, size, track), cy, size, col, track);
}

// =====================================================================================
// Classic click GUI (from mako-gui-cpp): category panels side by side, flat rows that fill
// with the accent color when on, settings open inline under the module (sliders, checkboxes,
// mode buttons, Reset). Pixel font. Small top bar for Modules / Configs / Friends + search.
// =====================================================================================

// ---------------------------------------------------------------- theme (gui.cpp)
#define K_INK    0xFF000000u
#define K_PANEL  0xFF0F1014u
#define K_HEAD   0xFF1B1D26u
#define K_DEEP   0xFF08090Cu
#define K_TEXT   0xFFC9CED8u
#define K_WHITE  0xFFFFFFFFu
#define K_TRACK  0xFF2B2F3Au
#define K_BOXOFF 0xFF4A4F5Eu
#define K_BOXIN  0xFF12141Au
#define K_CHEV   0xFF8A90A0u
#define K_TITLE  0xFFE8EAF0u
#define K_RESET  0xFFA7ADBBu
static const u32 K_ACCENTS[4] = { 0xFF6C4CF0u, 0xFFD6402Fu, 0xFF1F8A5Bu, 0xFFB45F00u };
static u32 aa(u32 rgb, float a) { return ((u32)(clampf(a, 0, 1) * 255.f + 0.5f) << 24) | (rgb & 0x00FFFFFFu); }

// fonts (gui.h FontId)
#define F_BRAND 22.0f
#define F_TITLE 16.0f
#define F_ROW   16.0f
#define F_LABEL 14.0f
#define F_SMALL 13.0f

// names the reused HUD / formatting code expects
#define COL_HEADER K_HEAD
#define COL_TEXT   K_TEXT
#define COL_DIM    K_CHEV
#define FS_SET     F_SMALL

// ---------------------------------------------------------------- model
enum SType { T_BOOL, T_BIND, T_NUM, T_RANGE, T_ENUM, T_ITEMS, T_SLOTS, T_PRIO, T_TEXT };

#define MAX_OPTS 64
struct Setting {
    const char* name; int type;
    float v, v2, mn, mx, step; const char* unit;
    const char* opts[MAX_OPTS]; int optCount;
    char text[160];
    const char* hint;
    bool visible;
    float hov;
};
#define MAX_MOD_SETTINGS 40
struct Module {
    const char* name; const char* cat; bool fixed;
    int sToggle, sBind, sVisible;
    int set[MAX_MOD_SETTINGS]; int setCount;
    bool open; float openA, hov, onA;
};
#define MAX_S 900
#define MAX_M 64
static Setting SS[MAX_S]; static int sCount;
static Module MM[MAX_M]; static int mCount;
static unsigned changeSerial;

static char POOL[96 * 1024]; static int poolUsed;
static const char* intern(const char* s, int n = -1) {
    if (n < 0) n = slen(s);
    if (poolUsed + n + 1 > (int)sizeof POOL) return "?";
    char* d = POOL + poolUsed; memcpy(d, s, n); d[n] = 0; poolUsed += n + 1; return d;
}

static int newSetting(const char* name, int type) {
    if (sCount >= MAX_S) return -1;
    Setting& s = SS[sCount];
    memset(&s, 0, sizeof s);
    s.name = intern(name); s.type = type; s.unit = ""; s.step = 1; s.mx = 1; s.hint = ""; s.visible = true;
    return sCount++;
}
static int newModule(const char* name, const char* cat, bool fixed) {
    if (mCount >= MAX_M) return -1;
    Module& m = MM[mCount];
    memset(&m, 0, sizeof m);
    m.name = intern(name); m.cat = intern(cat); m.fixed = fixed;
    m.sToggle = newSetting("Toggle", T_BOOL);
    m.sBind = newSetting("Bind", T_BIND); if (m.sBind >= 0) SS[m.sBind].v = -1;
    m.sVisible = newSetting("Visible", T_BOOL); if (m.sVisible >= 0) SS[m.sVisible].v = 1;
    return mCount++;
}


// ---------------------------------------------------------------- formatting
static const char* shortUnit(const char* u) {
    if (seq(u, "ticks")) return "t";
    if (seq(u, "blocks")) return "b";
    if (seq(u, "deg/t")) return "\xC2\xB0/t";
    return u;
}
static void fmtVal(const Setting& s, float v, char* out) {
    fmtNum(v, s.step, out);
}
/** Lower-case copy for display (no caps anywhere in the modules). Rotating buffers, so a few can be live at once. */
static const char* lc(const char* t) {
    static char bufs[8][160]; static int at;
    char* o = bufs[at = (at + 1) & 7];
    int n = 0;
    for (; t && t[n] && n < 159; n++) { char c = t[n]; o[n] = (c >= 'A' && c <= 'Z') ? (char)(c + 32) : c; }
    o[n] = 0;
    return o;
}
static const char* keyName(int key, char* buf) {
    if (key == -98) return "MMB";
    if (key == -97) return "MB4";
    if (key == -96) return "MB5";
    if (key < 0) return "None";
    if ((key >= 65 && key <= 90) || (key >= 48 && key <= 57)) { buf[0] = (char)key; buf[1] = 0; return buf; }
    if (key >= 290 && key <= 314) { buf[0] = 'F'; itoa_(key - 289, buf + 1); return buf; }
    if (key >= 320 && key <= 329) { scpy(buf, "NUM", 8); itoa_(key - 320, buf + 3); return buf; }
    switch (key) {
        case -100: return "LMB"; case -99: return "RMB"; case -98: return "MMB";
        case 32: return "SPACE"; case 39: return "'"; case 44: return ","; case 45: return "-"; case 46: return ".";
        case 47: return "/"; case 59: return ";"; case 61: return "="; case 91: return "["; case 92: return "\\";
        case 93: return "]"; case 96: return "`"; case 257: return "ENTER"; case 258: return "TAB";
        case 260: return "INSERT"; case 261: return "DELETE"; case 262: return "RIGHT"; case 263: return "LEFT";
        case 264: return "DOWN"; case 265: return "UP"; case 266: return "PGUP"; case 267: return "PGDN";
        case 268: return "HOME"; case 269: return "END"; case 280: return "CAPS";
        case 340: return "LSHIFT"; case 341: return "LCTRL"; case 342: return "LALT";
        case 344: return "RSHIFT"; case 345: return "RCTRL"; case 346: return "RALT";
    }
    buf[0] = 'K'; itoa_(key, buf + 1); return buf;
}
/** Draws `s` left-aligned but cut with "…" so it ends before `maxRight`. */
static void textClip(int face, const char* s, float x, float cy, float size, u32 col, float maxRight) {
    if (x + textW(face, s, size) <= maxRight) { text(face, s, x, cy, size, col); return; }
    char buf[96]; int n = 0;
    const char* ell = "\xE2\x80\xA6";
    float ew = textW(face, ell, size);
    while (s[n] && n < 90) {
        buf[n] = s[n]; buf[n + 1] = 0;
        if (x + textW(face, buf, size) + ew > maxRight) { buf[n] = 0; break; }
        n++;
    }
    scat(buf, ell, 96);
    text(face, buf, x, cy, size, col);
}
static void centered(int face, const char* s, float x, float y, float w, float h, float size, u32 col) {
    text(face, s, x + (w - textW(face, s, size)) * 0.5f, y + h * 0.5f, size, col);
}


// ---------------------------------------------------------------- panels (one per category)
#define MAX_C 8
struct Cat { const char* name; const char* title; int mods[MAX_M]; int count; float scroll, scrollMax; int k; float openA, fullH; };
static int zord[MAX_C]; static int zCount;                    // panel draw order: last = on top
static bool catOpen[6];                  // panels start folded: click a header to open it (kept while the game runs)
static Cat CC[MAX_C]; static int cCount;
static bool panelsBuilt; static int pCount;
#define NCAT 6
static const char* const CAT_ORDER[] = { "Combat", "Movement", "Automation", "Render", "Player", "Misc" };
static const char* const CAT_TITLE[] = { "COMBAT", "MOVEMENT", "AUTOMATION", "RENDER", "PLAYER", "MISC" };
// modules of the "Settings" category have no panel: they live in the gear's Settings panel
static int setMods[MAX_M]; static int setModCount;

static void buildPanels() {
    cCount = 0; setModCount = 0;
    for (int i = 0; i < mCount; i++) if (seq(MM[i].cat, "Settings") && setModCount < MAX_M) setMods[setModCount++] = i;
    for (int k = 0; k < NCAT; k++) {
        Cat& c = CC[cCount];
        c.name = CAT_ORDER[k]; c.title = CAT_TITLE[k]; c.count = 0; c.scroll = 0; c.scrollMax = 0; c.k = k;
        for (int i = 0; i < mCount; i++) {
            bool known = false;
            for (const char* o : CAT_ORDER) if (seq(o, MM[i].cat)) known = true;
            if (seq(MM[i].cat, "Settings")) continue;
            bool mine = seq(MM[i].cat, CAT_ORDER[k]) || (k == NCAT - 1 && !known);   // anything unknown -> Misc
            if (mine && c.count < MAX_M) c.mods[c.count++] = i;
        }
        if (c.count) cCount++;
    }
    pCount = cCount;
    zCount = cCount;
    for (int i = 0; i < cCount; i++) zord[i] = i;
    panelsBuilt = true;
}

// ---------------------------------------------------------------- UI state
static float VW, VH, mx = -1, my = -1, curDt;
static int focused = -1;                 // setting being dragged / listened / edited
static int dragHandle = -1;              // slider: 0 lo, 1 hi
static float dragX, dragW;
static bool bindListen, textEdit; static char editBuf[160];
static int guiKey = 344;                 // GLFW_KEY_RIGHT_SHIFT
static float openAnim;

static int page;                         // 0 modules, 1 configs, 2 friends
static char search[33]; static bool searchFocus;
static char pin[33]; static bool pinFocus;
static char listBuf[2][4096]; static const char* listItems[2][64]; static int listCount[2];
static char activeCfg[64];
static char actQ[16][96]; static int actHead, actTail;
static int accentLocal;
static float listScroll;

static bool inside(float px, float py, float x, float y, float w, float h) { return px >= x && px < x + w && py >= y && py < y + h; }

static void pushAction(const char* verb, const char* arg) {
    char* d = actQ[actTail]; scpy(d, verb, 96); scat(d, ":", 96); scat(d, arg, 96);
    actTail = (actTail + 1) % 16; if (actTail == actHead) actHead = (actHead + 1) % 16;
}

// accent: the HUD module's "Accent" setting when it has one (so the array list matches), else local
static int accentSetting() {
    for (int i = 0; i < mCount; i++) {
        if (!seq(MM[i].name, "HUD")) continue;
        for (int k = 0; k < MM[i].setCount; k++) {
            Setting& s = SS[MM[i].set[k]];
            if (s.type == T_ENUM && seq(s.name, "Accent")) return MM[i].set[k];
        }
    }
    return -1;
}
static int accentIdx() { int a = accentSetting(); int v = a >= 0 ? (int)SS[a].v : accentLocal; return v < 0 ? 0 : v > 3 ? 3 : v; }
static u32 ACC;

// ---------------------------------------------------------------- HUD editing (behind the panels)
struct HudEl { char label[32]; float x, y, w, h; };          // framebuffer px
static HudEl HE[16]; static int heCount; static int heDrag = -1; static float heOffX, heOffY;
static int moveIdx = -1; static float moveX, moveY;

static void drawHud() {
    for (int i = 0; i < heCount; i++) {
        HudEl& e = HE[i];
        float x = e.x / S, y = e.y / S, w = fmaxf_(e.w / S, 16), h = fmaxf_(e.h / S, 10);
        bool hv = inside(mx, my, x - 2, y - 2, w + 4, h + 4) || heDrag == i;
        if (!hv) continue;                                   // outline only while you point at it
        u32 bc = 0xA0FFFFFFu;
        float o = 1.0f / S;
        rrect(x - 2, y - 2, w + 4, o, 0, bc); rrect(x - 2, y + h + 2 - o, w + 4, o, 0, bc);
        rrect(x - 2, y - 2, o, h + 4, 0, bc); rrect(x + w + 2 - o, y - 2, o, h + 4, 0, bc);
        if (hv) {
            float lw = textW(FACE_REG, e.label, FS_SET) + 8;
            float ly = y - 2 - 13 < 1 ? y + h + 3 : y - 2 - 13;
            rrect(x - 2, ly, lw, 12, 0, COL_HEADER);
            text(FACE_PIX, e.label, x + 2, ly + 6, FS_SET, COL_TEXT);
        }
    }
}
static bool hudPress(float px, float py) {
    for (int i = heCount - 1; i >= 0; i--) {
        HudEl& e = HE[i];
        float ex = e.x / S, ey = e.y / S, ew = fmaxf_(e.w / S, 16), eh = fmaxf_(e.h / S, 10);
        if (inside(px, py, ex - 2, ey - 2, ew + 4, eh + 4)) { heDrag = i; heOffX = px - ex; heOffY = py - ey; return true; }
    }
    return false;
}
static void hudDrag(float px, float py) {
    if (heDrag < 0) return;
    HudEl& e = HE[heDrag];
    float nx = clampf((px - heOffX) * S, 0, VW * S - e.w), ny = clampf((py - heOffY) * S, 0, VH * S - e.h);
    e.x = nx; e.y = ny; moveIdx = heDrag; moveX = nx; moveY = ny;
}


// ---------------------------------------------------------------- drawing helpers
static void T(int face, const char* s, float x, float y, float h, float size, u32 c, float track = 0) {
    text(face, s, x, y + h * 0.5f, size, c, track);
}
static float TW(int face, const char* s, float size, float track = 0) { return textW(face, s, size, track); }
static void box(float x, float y, float w, float h, u32 c) { if (w > 0 && h > 0) rrect(x, y, w, h, 0, c); }
static void chevron(float cx, float cy, bool up, u32 c) {
    float d = up ? -1.f : 1.f;
    seg(cx - 5, cy - 2 * d, cx, cy + 3 * d, 2.0f, c);
    seg(cx, cy + 3 * d, cx + 5, cy - 2 * d, 2.0f, c);
}
static void framed(float x, float y, float w, float h, u32 fill) { box(x, y, w, h, K_INK); box(x + 1, y + 1, w - 2, h - 2, fill); }

// ---------------------------------------------------------------- layout constants
#define PANEL_W 216.0f
#define PITCH   232.0f
#define PANEL_TOP 64.0f
#define HEAD_H  36.0f
#define ROW_H   34.0f
#define PAD     12.0f
#define INNER_W (PANEL_W - 2 * PAD)
#define SLIDER_H 46.0f
#define TOGGLE_H 32.0f
#define RESET_H 34.0f

// the design is a 1280 x 760 canvas; it's centered when the screen is wider / taller
static float canvasX() { return (VW - 1280.f) * 0.5f; }
static float canvasY() { return fmaxf_(0.f, (VH - 760.f) * 0.5f); }
static float panelsLeft() { return canvasX() + 76.f; }

// Panels can be dragged by their header. Positions are kept relative to the design canvas
// (so they scale with the window) and saved by Java. NOPOS = the default spot. (It used to be "any
// negative value", so a panel dragged left of the centered canvas jumped back: you couldn't push it
// all the way to the left on wide screens.)
#define NOPOS -10000.f
static bool hasPos(float v) { return v > NOPOS * 0.5f; }
static float posX[6] = { NOPOS, NOPOS, NOPOS, NOPOS, NOPOS, NOPOS }, posY[6] = { NOPOS, NOPOS, NOPOS, NOPOS, NOPOS, NOPOS };
static int dragPanel = -1; static bool dragMoved; static float dragOffX, dragOffY, dragStartX, dragStartY;
// default spots: one row across the screen when it fits (6 panels), else two rows
static float defPitch() { return cCount > 1 ? fminf_(PITCH, (VW - 16 - 220) / (cCount - 1)) : PITCH; }
static bool oneRow() { return defPitch() >= 222.f; }
static float defX(int ci) {
    if (oneRow()) {
        float p = defPitch(), w = p * (cCount - 1) + 220;
        float x0 = cCount <= 5 ? panelsLeft() : (VW - w) * 0.5f;
        if (x0 + w > VW - 8) x0 = (VW - w) * 0.5f;
        return x0 + ci * p;
    }
    int per = (cCount + 1) / 2, col = ci % per;
    float w = PITCH * (per - 1) + 220, x0 = fmaxf_(8.f, (VW - w) * 0.5f);
    return x0 + col * PITCH;
}
static float defY(int ci) {
    if (oneRow()) return canvasY() + PANEL_TOP;
    int per = (cCount + 1) / 2;
    return ci < per ? PANEL_TOP : PANEL_TOP + (VH - PANEL_TOP) * 0.5f;
}
static float panelX(int ci) { int k = CC[ci].k; return hasPos(posX[k]) ? canvasX() + posX[k] : defX(ci); }
static float panelY(int ci) { int k = CC[ci].k; return hasPos(posY[k]) ? canvasY() + posY[k] : defY(ci); }
static void toFront(int ci) {
    int at = -1;
    for (int i = 0; i < zCount; i++) if (zord[i] == ci) at = i;
    if (at < 0) return;
    for (int i = at; i < zCount - 1; i++) zord[i] = zord[i + 1];
    zord[zCount - 1] = ci;
}

static bool modShown(const Module& m) { return !search[0] || containsCI(m.name, search); }
static bool settingShown(const Setting& s) { return s.visible && s.type != T_SLOTS && s.type != T_PRIO; }
static bool hasSettings(const Module& m) {
    if (!m.fixed) return true;                                     // always has the Keybind row
    for (int k = 0; k < m.setCount; k++) if (settingShown(SS[m.set[k]])) return true;
    return false;
}
static int modeRows(const Setting& s) { int n = s.optCount < 1 ? 1 : s.optCount; return (n + 2) / 3; }
static float settingH(const Setting& s) {
    switch (s.type) {
    case T_NUM: case T_RANGE: return SLIDER_H;
    case T_ENUM: return 28 + modeRows(s) * 34;
    case T_TEXT: case T_ITEMS: return 54;
    default: return TOGGLE_H;
    }
}
static float settingsHeight(const Module& m) {
    float h = 4 + RESET_H + 8;
    if (!m.fixed) h += TOGGLE_H;                                   // Keybind
    for (int k = 0; k < m.setCount; k++) if (settingShown(SS[m.set[k]])) h += settingH(SS[m.set[k]]);
    return h;
}

// ---------------------------------------------------------------- one setting row
static void sliderGeom(float cx, float sy, float* tx, float* tw) { *tx = cx; *tw = INNER_W; (void)sy; }

static void drawSetting(int si, float cx, float sy, bool isBind) {
    Setting& s = SS[si];
    char vb[48], vb2[24];
    if (isBind) {
        T(FACE_PIX, "keybind", cx, sy, TOGGLE_H, F_LABEL, K_TEXT);
        const char* kn = (focused == si && bindListen) ? "..." : lc(keyName((int)s.v, vb));
        float kw = TW(FACE_PIX, kn, F_SMALL) + 14;
        framed(cx + INNER_W - kw, sy + 6, kw, 20, focused == si && bindListen ? ACC : K_HEAD);
        T(FACE_PIX, kn, cx + INNER_W - kw + 7, sy + 6, 20, F_SMALL, K_WHITE);
        return;
    }
    switch (s.type) {
    case T_NUM: case T_RANGE: {
        float range = s.mx - s.mn; if (range <= 0) range = 1;
        float t1 = clampf((s.v - s.mn) / range, 0, 1), t2 = clampf((s.v2 - s.mn) / range, 0, 1);
        const char* u = shortUnit(s.unit);
        fmtVal(s, s.v, vb);
        if (s.type == T_RANGE) {
            fmtVal(s, s.v2, vb2);
            if (s.v2 != s.v) { scat(vb, " - ", 48); scat(vb, vb2, 48); }
        }
        if (u[0]) { if (!seq(u, "%")) scat(vb, " ", 48); scat(vb, u, 48); }
        T(FACE_PIX, lc(s.name), cx, sy + 6, 18, F_LABEL, K_TEXT);
        float vw = TW(FACE_PIX, vb, F_LABEL);
        T(FACE_PIX, vb, cx + INNER_W - vw, sy + 6, 18, F_LABEL, K_WHITE);
        float ty = sy + 34;
        box(cx, ty, INNER_W, 4, K_TRACK);
        float a0 = s.type == T_RANGE ? t1 : 0, a1 = s.type == T_RANGE ? t2 : t1;
        box(cx + roundf_(a0 * INNER_W), ty, roundf_((a1 - a0) * INNER_W), 4, ACC);
        float ths[2] = { t1, t2 };
        for (int h = 0; h < (s.type == T_RANGE ? 2 : 1); h++) {
            float thumbX = cx + 4 + roundf_(ths[h] * (INNER_W - 8)) - 4;
            box(thumbX, ty - 6, 8, 16, K_INK);
            box(thumbX + 2, ty - 4, 4, 12, K_WHITE);
        }
        break; }
    case T_BOOL: {
        T(FACE_PIX, lc(s.name), cx, sy, TOGGLE_H, F_LABEL, K_TEXT);
        bool on = s.v != 0;
        float bx = cx + INNER_W - 18, by = sy + 7;
        box(bx, by, 18, 18, on ? ACC : K_BOXOFF);
        box(bx + 2, by + 2, 14, 14, on ? ACC : K_BOXIN);
        if (on) {
            static const int px[5][2] = { {2, 7}, {4, 9}, {6, 7}, {8, 5}, {10, 3} };
            for (auto& q : px) box(bx + 2 + q[0], by + 2 + q[1], 2, 2, K_WHITE);
        }
        break; }
    case T_ENUM: {
        T(FACE_PIX, lc(s.name), cx, sy + 6, 18, F_LABEL, K_TEXT);
        int n = s.optCount;
        for (int i = 0; i < n; i++) {
            int row = i / 3, inRow = (n - row * 3) < 3 ? n - row * 3 : 3, col = i % 3;
            float bw = (INNER_W - 2 * (inRow - 1)) / inRow;
            float bx = cx + col * (bw + 2), w = col == inRow - 1 ? cx + INNER_W - bx : bw;
            float by = sy + 28 + row * 34;
            bool act = (int)s.v == i;
            framed(bx, by, w, 32, act ? ACC : K_HEAD);
            const char* o = lc(s.opts[i]);
            float tw = TW(FACE_PIX, o, F_SMALL);
            if (tw > w - 6) textClip(FACE_PIX, o, bx + 3, by + 16, F_SMALL, act ? K_WHITE : K_TEXT, bx + w - 3);
            else T(FACE_PIX, o, bx + (w - tw) * 0.5f, by, 32, F_SMALL, act ? K_WHITE : K_TEXT);
        }
        break; }
    case T_TEXT: case T_ITEMS: {
        T(FACE_PIX, lc(s.name), cx, sy + 4, 18, F_LABEL, K_TEXT);
        bool f = focused == si && textEdit;
        framed(cx, sy + 24, INNER_W, 26, f ? ACC : K_HEAD);
        const char* v = f ? editBuf : s.text;
        const char* shown = v;
        while (*shown && TW(FACE_PIX, shown, F_SMALL) > INNER_W - 16) shown++;
        float tw = 0;
        if (*v || f) tw = TW(FACE_PIX, shown, F_SMALL), T(FACE_PIX, shown, cx + 7, sy + 24, 26, F_SMALL, K_WHITE);
        else T(FACE_PIX, s.hint && s.hint[0] ? s.hint : "...", cx + 7, sy + 24, 26, F_SMALL, K_CHEV);
        if (f && ((int)(openAnim * 1000) % 1000) < 600) box(cx + 8 + tw, sy + 30, 2, 14, K_WHITE);
        break; }
    default: break;
    }
}

static int listRows(int kind, float ix, float* yy, float y0, float clipBottom, bool draw, int press, float px, float py);
/** Module rows (+ their open settings) from *yy down. Returns true if a press was used. */
static bool modRows(const int* mods, int count, float ix, float* yy, float y0, float clipBottom, bool draw, int press, float px, float py) {
    float y = *yy;
    for (int k = 0; k < count; k++) {
        int mi = mods[k];
        Module& m = MM[mi];
        if (!modShown(m)) continue;
        bool on = !m.fixed && SS[m.sToggle].v != 0;
        bool hasSet = hasSettings(m);
        bool vis = y + ROW_H > y0 && y < clipBottom;
        if (press && vis && py >= y0 && py < clipBottom) {
            if (inside(px, py, ix, y, PANEL_W, ROW_H)) {
                bool chev = px >= ix + PANEL_W - ROW_H;
                if (press == 2 || (chev && hasSet) || (m.fixed && press == 1)) { if (hasSet) m.open = !m.open; }
                else if (press == 1) { Setting& t = SS[m.sToggle]; t.v = t.v != 0 ? 0 : 1; }
                return true;
            }
        }
        if (draw && vis) {
            bool hov = inside(mx, my, ix, y, PANEL_W, ROW_H) && my >= y0;
            u32 bg = on ? ACC : (hov ? mixc(K_PANEL, K_HEAD, 0.6f) : K_PANEL);
            box(ix, y, PANEL_W, ROW_H, bg);
            textClip(FACE_PIX, lc(m.name), ix + PAD, y + ROW_H * 0.5f, F_ROW, on ? K_WHITE : K_TEXT, ix + PANEL_W - ROW_H - 30);
            int key = m.fixed ? -1 : (int)SS[m.sBind].v;
            if (key >= 0) {
                char kb[16]; const char* kn = lc(keyName(key, kb));
                u32 kc = on ? mixc(ACC, K_WHITE, 0.85f) : mixc(K_PANEL, K_TEXT, 0.85f);
                float kw = TW(FACE_PIX, kn, F_SMALL);
                T(FACE_PIX, kn, ix + PANEL_W - ROW_H - 4 - kw, y, ROW_H, F_SMALL, kc);
            }
            if (hasSet) chevron(ix + PANEL_W - ROW_H * 0.5f, y + ROW_H * 0.5f, m.open, on ? K_WHITE : K_CHEV);
        }
        y += ROW_H;

        if (m.openA > 0.001f && hasSet) {
            float total = settingsHeight(m);
            float shown = total * easeOut(m.openA);                 // the settings slide open / closed
            bool settled = m.openA >= 0.999f;
            float setBottom = fminf_(clipBottom, y + shown);
            if (draw && y + shown > y0 && y < clipBottom) box(ix, y, PANEL_W, shown, K_DEEP);
            if (draw) setClip(ix, fmaxf_(y0, y), ix + PANEL_W, setBottom);
            float cx = ix + PAD, sy = y + 4;
            int rows[MAX_MOD_SETTINGS + 1]; int rc = 0;
            if (!m.fixed) rows[rc++] = -1;                           // Keybind
            for (int q = 0; q < m.setCount; q++) if (settingShown(SS[m.set[q]])) rows[rc++] = m.set[q];
            for (int r = 0; r < rc; r++) {
                bool isBind = rows[r] < 0;
                int si = isBind ? m.sBind : rows[r];
                float h = isBind ? TOGGLE_H : settingH(SS[si]);
                bool rv = sy + h > y0 && sy < setBottom;
                if (press && settled && rv && py >= y0 && py < clipBottom && inside(px, py, ix, sy, PANEL_W, h)) {
                    Setting& s = SS[si];
                    int button = press - 1;
                    if (isBind || s.type == T_BIND) {
                        if (button == 1) s.v = -1; else { focused = si; bindListen = true; }
                    } else switch (s.type) {
                    case T_BOOL: if (button == 0) s.v = s.v != 0 ? 0 : 1; break;
                    case T_NUM: case T_RANGE: {
                        if (button != 0 || py < sy + 22) break;
                        dragX = cx + 4; dragW = INNER_W - 8; focused = si;
                        if (s.type == T_NUM) dragHandle = 0;
                        else {
                            float v = s.mn + clampf((px - dragX) / dragW, 0, 1) * (s.mx - s.mn);
                            float dl = fabsf_(v - s.v), dh = fabsf_(v - s.v2);
                            dragHandle = dl < dh ? 0 : dl > dh ? 1 : (v < s.v ? 0 : 1);
                        }
                        break; }
                    case T_ENUM: {
                        if (py < sy + 28) { if (s.optCount) s.v = (float)(((int)s.v + (button == 1 ? s.optCount - 1 : 1)) % s.optCount); break; }
                        int n = s.optCount, row = (int)((py - sy - 28) / 34);
                        int inRow = (n - row * 3) < 3 ? n - row * 3 : 3;
                        if (inRow <= 0) break;
                        float bw = (INNER_W - 2 * (inRow - 1)) / inRow;
                        int col = (int)((px - cx) / (bw + 2)); if (col < 0) col = 0; if (col >= inRow) col = inRow - 1;
                        s.v = (float)(row * 3 + col);
                        break; }
                    case T_TEXT: case T_ITEMS:
                        if (button == 0) { focused = si; textEdit = true; scpy(editBuf, s.text, 160); }
                        break;
                    default: break;
                    }
                    return true;
                }
                if (draw && rv) drawSetting(si, cx, sy, isBind);
                sy += h;
            }
            // Reset
            float lw = TW(FACE_PIX, "reset", F_SMALL), rw = lw + 22, rx = cx + INNER_W - rw;
            if (press && settled && inside(px, py, rx, sy + 6, rw, 28) && py >= y0 && py < clipBottom) { pushAction("reset", m.name); return true; }
            if (draw && sy + 40 > y0 && sy < setBottom) {
                bool rh = inside(mx, my, rx, sy + 6, rw, 28);
                framed(rx, sy + 6, rw, 28, rh ? mixc(K_HEAD, K_WHITE, 0.08f) : K_HEAD);
                T(FACE_PIX, "reset", rx + 11, sy + 6, 28, F_SMALL, K_RESET);
            }
            if (draw) setClip(ix, y0, ix + PANEL_W, clipBottom);
            y += shown;
        }
        if (draw && y > y0 - 1 && y < clipBottom) box(ix, y, PANEL_W, 1, K_INK);
        y += 1;
    }
    *yy = y;
    return false;
}

// ---------------------------------------------------------------- a whole panel (draw or hit-test)
// press: 0 none, else 1 + mouse button. Returns true if the press was used.
static bool panel(int ci, bool draw, int press, float px, float py) {
    Cat& c = CC[ci];
    float outerX = panelX(ci), ix = outerX + 2;
    float PT = panelY(ci), top = PT + 2;
    float clipBottom = VH - 8;
    bool open = catOpen[c.k];
    float pa = easeOut(c.openA);                                 // 0 folded .. 1 open (slight slide)
    if (press && inside(px, py, ix, top, PANEL_W, HEAD_H)) {
        toFront(ci);
        if (press == 2) { catOpen[c.k] = !open; c.scroll = 0; return true; }      // right click: fold now
        dragPanel = ci; dragMoved = false;                                         // left: drag, or fold on release
        dragOffX = px - outerX; dragOffY = py - PT; dragStartX = px; dragStartY = py;
        return true;
    }
    if (draw) {
        bool hh = inside(mx, my, ix, top, PANEL_W, HEAD_H);
        box(ix, top, PANEL_W, HEAD_H, hh ? mixc(K_HEAD, K_WHITE, 0.04f) : K_HEAD);
        box(ix, top + HEAD_H - 2, PANEL_W, 2, ACC);
        T(FACE_PIXB, c.title, ix + PAD, top, HEAD_H - 2, F_TITLE, K_TITLE, 1);
        chevron(ix + PANEL_W - ROW_H * 0.5f, top + (HEAD_H - 2) * 0.5f, open, hh ? K_WHITE : K_CHEV);
    }
    if (c.openA <= 0.001f) {
        if (draw) {
            float bottom = top + HEAD_H + 2;
            box(outerX, PT, 220, 2, K_INK); box(outerX, PT, 2, bottom - PT, K_INK);
            box(outerX + 218, PT, 2, bottom - PT, K_INK); box(outerX, bottom - 2, 220, 2, K_INK);
        }
        return false;
    }
    float y0 = top + HEAD_H, y = y0 - c.scroll;
    if (pa < 0.999f) clipBottom = fminf_(clipBottom, y0 + c.fullH * pa);   // opening / folding: reveal from the top
    if (press && pa < 0.999f) return false;
    if (draw) setClip(ix, y0, ix + PANEL_W, clipBottom);
    if (modRows(c.mods, c.count, ix, &y, y0, clipBottom, draw, press, px, py)) return true;
    bool used = false;
    float contentH = y + c.scroll - y0;
    if (draw) c.fullH = contentH;
    float room = (VH - 8) - y0 - 2;
    c.scrollMax = contentH > room ? contentH - room : 0;
    if (c.scroll > c.scrollMax) c.scroll = c.scrollMax;
    if (draw) {
        noClip();
        float bottom = (y < clipBottom ? y : clipBottom) + 2;
        box(outerX, PT, 220, 2, K_INK);
        box(outerX, PT, 2, bottom - PT, K_INK);
        box(outerX + 218, PT, 2, bottom - PT, K_INK);
        box(outerX, bottom - 2, 220, 2, K_INK);
        if (c.scrollMax > 0) {                                      // little scroll bar
            float vh = clipBottom - y0, bh = fmaxf_(20, vh * room / contentH);
            float by = y0 + (vh - bh) * (c.scroll / c.scrollMax);
            box(ix + PANEL_W - 3, by, 2, bh, aa(K_WHITE, 0.35f));
        }
    }
    (void)used;
    return false;
}


// ---------------------------------------------------------------- Configs / Friends (rows in the Misc panel)
static bool listOpen[2];
static int pinKind = -1;                 // which list's name box has focus
static int listRows(int kind, float ix, float* yy, float y0, float clipBottom, bool draw, int press, float px, float py) {
    const char* title = kind == 0 ? "configs" : "friends";
    float y = *yy;
    bool vis = y + ROW_H > y0 && y < clipBottom;
    if (press && vis && py >= y0 && py < clipBottom && inside(px, py, ix, y, PANEL_W, ROW_H)) {
        listOpen[kind] = !listOpen[kind]; return 1;
    }
    if (draw && vis) {
        bool hov = inside(mx, my, ix, y, PANEL_W, ROW_H) && my >= y0;
        box(ix, y, PANEL_W, ROW_H, hov ? mixc(K_PANEL, K_HEAD, 0.6f) : K_PANEL);
        T(FACE_PIX, title, ix + PAD, y, ROW_H, F_ROW, K_TEXT);
        if (listCount[kind]) {
            char nb[8]; itoa_(listCount[kind], nb);
            float nw = TW(FACE_PIX, nb, F_SMALL);
            T(FACE_PIX, nb, ix + PANEL_W - ROW_H - 4 - nw, y, ROW_H, F_SMALL, mixc(K_PANEL, K_TEXT, 0.85f));
        }
        chevron(ix + PANEL_W - ROW_H * 0.5f, y + ROW_H * 0.5f, listOpen[kind], K_CHEV);
    }
    y += ROW_H;
    if (listOpen[kind]) {
        int n = listCount[kind];
        float total = 4 + (n ? n * 30 : 30) + 4 + 26 + 10;
        if (draw && y + total > y0 && y < clipBottom) box(ix, y, PANEL_W, total, K_DEEP);
        float cx = ix + PAD, sy = y + 4;
        if (!n && draw) T(FACE_PIX, kind == 0 ? "no configs yet" : "no friends yet", cx, sy, 30, F_LABEL, K_CHEV);
        for (int i = 0; i < n; i++) {
            const char* it = listItems[kind][i];
            bool act = kind == 0 && seq(it, activeCfg);
            bool rv = sy + 30 > y0 && sy < clipBottom;
            if (press && rv && py >= y0 && inside(px, py, ix, sy, PANEL_W, 30)) {
                bool del = press == 2 || px >= cx + INNER_W - 18;
                if (kind == 0) pushAction(del ? "cfg_delete" : "cfg_load", it);
                else if (del) pushAction("friend_del", it);
                return 1;
            }
            if (draw && rv) {
                bool hov = inside(mx, my, ix, sy, PANEL_W, 30) && my >= y0;
                if (act) box(cx, sy + 11, 4, 8, ACC);
                textClip(FACE_PIX, it, cx + (act ? 10 : 0), sy + 15, F_LABEL, act ? K_WHITE : (hov ? K_WHITE : K_TEXT), cx + INNER_W - 22);
                bool xh = hov && mx >= cx + INNER_W - 18;
                u32 xc = xh ? K_WHITE : K_CHEV;
                float xc0 = cx + INNER_W - 6, yc0 = sy + 15;
                seg(xc0 - 3.5f, yc0 - 3.5f, xc0 + 3.5f, yc0 + 3.5f, 1.6f, xc); seg(xc0 - 3.5f, yc0 + 3.5f, xc0 + 3.5f, yc0 - 3.5f, 1.6f, xc);
            }
            sy += 30;
        }
        if (!n) sy += 30;
        sy += 4;
        // name box + Save / Add
        const char* bl = kind == 0 ? "save" : "add";
        float bw = TW(FACE_PIX, bl, F_SMALL) + 22, fw = INNER_W - bw - 4;
        bool focus = pinFocus && pinKind == kind;
        if (press && inside(px, py, cx, sy, fw, 26) && py >= y0) { pinFocus = true; pinKind = kind; return 1; }
        if (press && inside(px, py, cx + fw + 4, sy, bw, 26) && py >= y0) {
            if (pin[0] && pinKind == kind) { pushAction(kind == 0 ? "cfg_save" : "friend_add", pin); pin[0] = 0; }
            pinFocus = false; return 1;
        }
        if (draw && sy + 26 > y0 && sy < clipBottom) {
            framed(cx, sy, fw, 26, focus ? mixc(K_HEAD, ACC, 0.25f) : K_HEAD);
            float tw = 0;
            if (focus && pin[0]) tw = TW(FACE_PIX, pin, F_SMALL), T(FACE_PIX, pin, cx + 7, sy, 26, F_SMALL, K_WHITE);
            else if (!focus) T(FACE_PIX, kind == 0 ? "name..." : "player...", cx + 7, sy, 26, F_SMALL, K_CHEV);
            if (focus && ((int)(openAnim * 1000) % 1000) < 600) box(cx + 8 + tw, sy + 6, 2, 14, K_WHITE);
            bool bh = inside(mx, my, cx + fw + 4, sy, bw, 26);
            framed(cx + fw + 4, sy, bw, 26, bh ? mixc(ACC, K_WHITE, 0.15f) : ACC);
            T(FACE_PIX, bl, cx + fw + 4 + 11, sy, 26, F_SMALL, K_WHITE);
        }
        y += total;
    }
    if (draw && y > y0 - 1 && y < clipBottom) box(ix, y, PANEL_W, 1, K_INK);
    y += 1;
    *yy = y;
    return 0;
}


// ---------------------------------------------------------------- settings gear (bottom right): Configs + Friends
static bool setOpen;
static float setOpenA, setScroll, setScrollMax;
#define GEAR 34.0f
static float gearX() { return VW - GEAR - 16; }
static float gearY() { return VH - GEAR - 16; }
static void drawGear() {
    float x = gearX(), y = gearY();
    bool hov = inside(mx, my, x, y, GEAR, GEAR);
    framed(x, y, GEAR, GEAR, setOpen ? ACC : hov ? mixc(K_HEAD, K_WHITE, 0.07f) : K_HEAD);
    u32 c = setOpen || hov ? K_WHITE : K_TEXT;
    float cx = x + GEAR * 0.5f, cy = y + GEAR * 0.5f;
    static const float CS[8][2] = { {1, 0}, {0.7071f, 0.7071f}, {0, 1}, {-0.7071f, 0.7071f},
                                    {-1, 0}, {-0.7071f, -0.7071f}, {0, -1}, {0.7071f, -0.7071f} };
    for (int i = 0; i < 8; i++) {                                   // teeth
        float ca = CS[i][0], sa = CS[i][1];
        seg(cx + ca * 6.5f, cy + sa * 6.5f, cx + ca * 9.5f, cy + sa * 9.5f, 3.2f, c);
    }
    ring(cx, cy, 6.0f, 2.6f, c);
    ring(cx, cy, 2.2f, 1.6f, c);
}
/** The Settings panel above the gear: HUD + Configs + Friends. Returns true if a press was used. */
static bool setPanelBox(float* x0, float* pt, float* h) {
    float hy = 0;
    modRows(setMods, setModCount, 0, &hy, -1e9f, 1e9f, false, 0, 0, 0);
    listRows(0, 0, &hy, -1e9f, 1e9f, false, 0, 0, 0);
    listRows(1, 0, &hy, -1e9f, 1e9f, false, 0, 0, 0);
    float bottom = gearY() - 8, room = bottom - 8 - 2 - HEAD_H - 2;
    setScrollMax = hy > room ? hy - room : 0;
    if (setScroll > setScrollMax) setScroll = setScrollMax;
    if (setScroll < 0) setScroll = 0;
    *h = 2 + HEAD_H + (hy > room ? room : hy) + 2;
    *x0 = gearX() + GEAR - 220; *pt = bottom - *h;
    return true;
}
static bool settingsPanel(bool draw, int press, float px, float py) {
    if (setOpenA <= 0.001f) return false;
    float ix0, PT, full; setPanelBox(&ix0, &PT, &full);
    float ix = ix0 + 2, e = easeOut(setOpenA);
    float bottom = gearY() - 8;
    float top = PT + 2, y0 = top + HEAD_H;
    float clipTop = bottom - full * e;                                // slides up out of the gear
    float rowsTop = fmaxf_(y0, clipTop), rowsBottom = bottom - 2;
    float y = y0 - setScroll;
    if (press && py < clipTop) return false;
    if (press && !inside(px, py, ix0, PT, 220, full)) return false;
    if (press && e < 0.999f) return true;
    if (draw) {
        setClip(ix0, clipTop, ix0 + 220, bottom);
        box(ix, top, PANEL_W, full - 4, K_PANEL);
        box(ix, top, PANEL_W, HEAD_H, K_HEAD);
        box(ix, top + HEAD_H - 2, PANEL_W, 2, ACC);
        T(FACE_PIXB, "SETTINGS", ix + PAD, top, HEAD_H - 2, F_TITLE, K_TITLE, 1);
        setClip(ix, rowsTop, ix + PANEL_W, rowsBottom);
    }
    if (press && py < y0) return true;                                // the header
    if (modRows(setMods, setModCount, ix, &y, rowsTop, rowsBottom, draw, press, px, py)) return true;
    if (draw) setClip(ix, rowsTop, ix + PANEL_W, rowsBottom);
    for (int kind = 0; kind < 2; kind++)
        if (listRows(kind, ix, &y, rowsTop, rowsBottom, draw, press, px, py)) return true;
    if (draw) {
        setClip(ix0, clipTop, ix0 + 220, bottom);
        box(ix0, PT, 220, 2, K_INK); box(ix0, PT, 2, full, K_INK);
        box(ix0 + 218, PT, 2, full, K_INK); box(ix0, bottom - 2, 220, 2, K_INK);
        if (setScrollMax > 0) {
            float vh = rowsBottom - y0, bh = fmaxf_(20, vh * vh / (vh + setScrollMax));
            float by = y0 + (vh - bh) * (setScroll / setScrollMax);
            box(ix + PANEL_W - 3, by, 2, bh, aa(K_WHITE, 0.35f));
        }
        noClip();
    }
    return press != 0;                                                // a click inside the panel is used
}
// ---------------------------------------------------------------- the design's backdrop + HUD
static void dirt() {
    const u32 c1 = 0xFF201912u, c2 = 0xFF1C1612u;              // #4b3b2a / #43352a under 58% black
    float ox = canvasX(), oy = canvasY();
    box(-2, -2, VW + 4, VH + 4, c2);
    int q0x = (int)floorf_((0 - ox) / 32) - 1, q0y = (int)floorf_((0 - oy) / 32) - 1;
    for (int qy = q0y; oy + qy * 32 < VH; qy++)
        for (int qx = q0x; ox + qx * 32 < VW; qx++) {
            bool right = ((qx % 2) + 2) % 2 == 1, bottom = ((qy % 2) + 2) % 2 == 1;
            if (right != bottom) box(ox + qx * 32, oy + qy * 32, 32, 32, c1);
        }
}
static HudEl* hudEl(const char* label) {
    for (int i = 0; i < heCount; i++) if (seq(HE[i].label, label)) return &HE[i];
    return nullptr;
}
static void tagShape(float x, float y, float w, bool right) {
    const u32 c = 0xFF0D0D10u;
    if (right) { box(x + 8, y, w - 8, 24, c); box(x + 4, y + 4, 4, 20, c); box(x, y + 8, 4, 16, c); }
    else { box(x, y, w - 8, 24, c); box(x + w - 8, y + 4, 4, 20, c); box(x + w - 4, y + 8, 4, 16, c); }
}
/** Watermark + array list drawn the same way the in-game HUD draws them, where they are on screen. */
static void designHud() {
    if (HudEl* w = hudEl("Watermark")) {
        float x = w->x / S, y = w->y / S;
        text(FACE_PIXB, "Zalard", x + 2, y + 16, F_BRAND, 0xFF3A3A3Au);
        float bw = text(FACE_PIXB, "Zalard", x, y + 14, F_BRAND, K_WHITE);
        text(FACE_PIX, " 1.0", x + bw, y + 17, F_LABEL, 0xFFBFC4D0u);
    }
    HudEl* a = hudEl("ArrayList");
    if (!a) return;
    int idx[MAX_M]; float wd[MAX_M]; int n = 0;
    for (int i = 0; i < mCount; i++) {
        Module& m = MM[i];
        if (m.fixed || seq(m.name, "HUD") || SS[m.sToggle].v == 0) continue;
        idx[n] = i; wd[n] = textW(FACE_PIX, lc(m.name), F_ROW); n++;
    }
    for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++)
        if (wd[j] > wd[i]) { float t = wd[i]; wd[i] = wd[j]; wd[j] = t; int u = idx[i]; idx[i] = idx[j]; idx[j] = u; }
    float ax = a->x / S, ay = a->y / S, aw = a->w / S;
    bool right = ax + aw * 0.5f > VW * 0.5f;
    for (int i = 0; i < n; i++) {
        float w = 16 + wd[i] + 10 + 6, x = right ? ax + aw - w : ax, ty = ay + i * 26;
        tagShape(x, ty, w, right);
        text(FACE_PIX, lc(MM[idx[i]].name), x + 17, ty + 13, F_ROW, 0xFF1A1A1Au);
        text(FACE_PIX, lc(MM[idx[i]].name), x + 16, ty + 12, F_ROW, K_WHITE);
        box(right ? x + w - 6 : x, ty, 6, 24, ACC);
    }
}

// ---------------------------------------------------------------- frame
static void frame(float wpx, float hpx, float mxPx, float myPx, float dt) {
    if (dt > 0.1f) dt = 0.1f;
    curDt = dt;
    S = fmaxf_(0.6f, fminf_(hpx / 760.0f, wpx / 1280.0f));
    VW = wpx / S; VH = hpx / S;
    mx = mxPx / S; my = myPx / S;
    openAnim += dt;
    page = 0;
    if (!panelsBuilt) buildPanels();
    ACC = K_ACCENTS[accentIdx()];

    // slight open / fold animation: ~0.15 s, eased
    float stepA = dt / 0.15f;
    for (int i = 0; i < cCount; i++) {
        float want = catOpen[CC[i].k] ? 1.f : 0.f;
        CC[i].openA = CC[i].openA < want ? fminf_(want, CC[i].openA + stepA) : fmaxf_(want, CC[i].openA - stepA);
    }
    for (int i = 0; i < mCount; i++) {
        float want = MM[i].open ? 1.f : 0.f;
        MM[i].openA = MM[i].openA < want ? fminf_(want, MM[i].openA + stepA) : fmaxf_(want, MM[i].openA - stepA);
    }
    setOpenA = setOpen ? fminf_(1.f, setOpenA + stepA) : fmaxf_(0.f, setOpenA - stepA);

    // dragging a panel by its header (starts after 3 px so a click still folds it)
    if (dragPanel >= 0) {
        if (!dragMoved && fabsf_(mx - dragStartX) + fabsf_(my - dragStartY) > 3) dragMoved = true;
        if (dragMoved) {
            int k = CC[dragPanel].k;
            float nx = clampf(mx - dragOffX, 0, VW - 220), ny = clampf(my - dragOffY, 0, VH - HEAD_H - 4);
            posX[k] = nx - canvasX(); posY[k] = ny - canvasY();
        }
    }

    noClip();
    gA = clampf(openAnim / 0.12f, 0, 1);                        // quick fade in
    drawHud();                                                 // hover outlines: drag HUD elements
    for (int z = 0; z < zCount; z++) panel(zord[z], true, 0, 0, 0);
    noClip();
    settingsPanel(true, 0, 0, 0);
    noClip();
    drawGear();
    gA = 1;
}

// ---------------------------------------------------------------- input
static void commitText() { if (focused >= 0 && textEdit) scpy(SS[focused].text, editBuf, 160); }
static void unfocus() { commitText(); focused = -1; bindListen = false; textEdit = false; dragHandle = -1; }

static void dragTo(float px, float py) {
    (void)py;
    if (focused < 0 || dragHandle < 0) return;
    Setting& s = SS[focused];
    if (s.type != T_NUM && s.type != T_RANGE) return;
    changeSerial++;
    float f = clampf((px - dragX) / dragW, 0, 1);
    float v = s.mn + f * (s.mx - s.mn);
    v = clampf(roundf_((v - s.mn) / s.step) * s.step + s.mn, s.mn, s.mx);
    if (s.type == T_NUM) s.v = v;
    else if (dragHandle == 0) s.v = fminf_(v, s.v2);
    else s.v2 = fmaxf_(v, s.v);
}

static void mouseDownImpl(float pxp, float pyp, int button) {
    float px = pxp / S, py = pyp / S;
    mx = px; my = py;
    if (focused >= 0) {
        bool listening = bindListen;
        if (listening && button >= 2 && button <= 4) {                            // bind the middle mouse button
            SS[focused].v = (float)(-100 + button);
            unfocus(); return;
        }
        unfocus();
        if (listening) return;                                     // clicking away cancels the bind
    }
    pinFocus = false;
    int press = button == 0 ? 1 : button == 1 ? 2 : 0;
    if (!press) return;
    if (inside(px, py, gearX(), gearY(), GEAR, GEAR)) { setOpen = !setOpen; return; }
    if (settingsPanel(false, press, px, py)) return;
    for (int z = zCount - 1; z >= 0; z--) {
        int i = zord[z];
        float ox = panelX(i);
        if (px >= ox && px < ox + 220 && py >= panelY(i)) {
            if (panel(i, false, press, px, py)) {
                if (dragHandle >= 0) dragTo(px, py);
                return;
            }
        }
    }
    if (button == 0) hudPress(px, py);                             // not on a panel: HUD elements
}
static void mouseDown(float pxp, float pyp, int button) { mouseDownImpl(pxp, pyp, button); changeSerial++; }
static void mouseUp() {
    if (dragHandle >= 0) { focused = -1; dragHandle = -1; }
    if (dragPanel >= 0) {
        Cat& c = CC[dragPanel];
        if (!dragMoved) { catOpen[c.k] = !catOpen[c.k]; c.scroll = 0; }     // a click: fold / open
        else {                                                             // a drag: Java saves the spot
            char buf[64]; scpy(buf, c.name, 64); scat(buf, "|", 64);
            char nb[16]; itoa_((int)roundf_(posX[c.k]), nb); scat(buf, nb, 64); scat(buf, "|", 64);
            itoa_((int)roundf_(posY[c.k]), nb); scat(buf, nb, 64);
            pushAction("panel", buf);
        }
        dragPanel = -1; dragMoved = false;
    }
    heDrag = -1;
    changeSerial++;
}

static int keyDownImpl(int key, int mods) {
    (void)mods;
    if (focused >= 0 && bindListen) {
        SS[focused].v = (key == 256 || key == 259 || key == 261) ? -1 : (float)key;
        unfocus();
        return 1;
    }
    if (focused >= 0 && textEdit) {
        if (key == 257 || key == 335 || key == 256) { unfocus(); return 1; }
        if (key == 259) { int n = slen(editBuf); if (n) editBuf[n - 1] = 0; return 1; }
        return 1;
    }
    if (pinFocus) {
        char* b = pin;
        if (key == 259) { int n = slen(b); if (n) b[n - 1] = 0; return 1; }
        if (key == 257 || key == 335) {
            if (pin[0] && pinKind >= 0) { pushAction(pinKind == 0 ? "cfg_save" : "friend_add", pin); pin[0] = 0; }
            searchFocus = pinFocus = false; return 1;
        }
        if (key == 256) { searchFocus = pinFocus = false; return 1; }
        return 1;
    }
    if (key >= 49 && key <= 52) {                                  // 1-4: accent color
        int a = accentSetting();
        if (a >= 0) SS[a].v = (float)(key - 49); else accentLocal = key - 49;
        return 1;
    }
    if (key == 256 || key == guiKey) return 2;
    return 0;
}
static int keyDown(int key, int mods) { int r = keyDownImpl(key, mods); changeSerial++; return r; }

static int charTyped(int cp) {
    if (pinFocus) {
        char c = (char)cp;
        bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
        int n = slen(pin);
        if (ok && n < 32) { pin[n] = c; pin[n + 1] = 0; }
        return 1;
    }
    if (focused >= 0 && textEdit) {
        if (cp < 32 || cp > 126) return 1;
        int n = slen(editBuf);
        if (n < 158) { editBuf[n] = (char)cp; editBuf[n + 1] = 0; }
        return 1;
    }
    return 0;
}

/** Mouse wheel scrolls the panel under the mouse (or the Configs / Friends list). */
static void wheel(float pyp, float dy) {
    (void)pyp;
    if (setOpenA > 0.99f) {
        float x0, pt, h; setPanelBox(&x0, &pt, &h);
        if (inside(mx, my, x0, pt, 220, h)) { setScroll = clampf(setScroll - dy * 36, 0, setScrollMax); return; }
    }
    for (int z = zCount - 1; z >= 0; z--) {
        int i = zord[z];
        float ox = panelX(i);
        if (mx >= ox && mx < ox + 220 && my >= panelY(i)) { CC[i].scroll = clampf(CC[i].scroll - dy * 36, 0, CC[i].scrollMax); return; }
    }
}
// ---------------------------------------------------------------- ESP overlay
/** Anti-aliased line in raw framebuffer pixels (used for ESP, drawn under the GUI). */
static void segPx(float ax, float ay, float bx, float by, float thick, u32 c) {
    float t = thick * 0.5f;
    float p[4] = { ax, ay, bx, by };
    quad(fminf_(ax, bx) - t - 1, fminf_(ay, by) - t - 1, fmaxf_(ax, bx) + t + 1, fmaxf_(ay, by) + t + 1, t, 0, t, 0, p, c, c, 2);
}

#ifdef MAKO_HARNESS
static void addDemo(int m, const char* n, int type, float v, float v2, float mn, float mx_, float step, const char* unit) {
    int i = newSetting(n, type); Setting& s = SS[i]; s.v = v; s.v2 = v2; s.mn = mn; s.mx = mx_; s.step = step; s.unit = unit;
    MM[m].set[MM[m].setCount++] = i;
}
static void buildModules() {
    sCount = mCount = 0; panelsBuilt = false;
    const char* combat[] = { "Aim Assist", "Silent Aim", "Trigger Bot", "Auto Crystal", "Auto Anchor", "Auto Totem",
        "Mace Swap", "Movement Reset", "Player Freeze", "Backtrack", "Anti Knockback", "Wind Charge Stack",
        "Fake Elytra", "Elytra Fly", "Pearl Catch", "Auto Elytra", "Auto Pot", "Shield Breaker", "Stun Slam", "Humanizer" };
    for (const char* n : combat) newModule(n, "Combat", false);
    newModule("Auto Bridge", "Movement", false);
    newModule("ESP", "Render", false); newModule("HUD", "Render", false); newModule("X-Ray", "Render", false);
    int sp = newModule("Client Spoofer", "Misc", true);
    SS[MM[2].sToggle].v = 1; SS[MM[0].sToggle].v = 1; SS[MM[21].sToggle].v = 1;
    addDemo(2, "Delay", T_RANGE, 1, 4, 0, 10, 1, "ticks");
    addDemo(2, "Crit Timing", T_BOOL, 1, 0, 0, 1, 1, "");
    addDemo(2, "Players Only", T_BOOL, 0, 0, 0, 1, 1, "");
    addDemo(2, "Target Lock", T_BOOL, 1, 0, 0, 1, 1, "");
    int i = newSetting("Combo Tap", T_ENUM); SS[i].opts[0] = "W-Tap"; SS[i].opts[1] = "S-Tap"; SS[i].opts[2] = "Off"; SS[i].optCount = 3;
    MM[7].set[MM[7].setCount++] = i;
    addDemo(7, "Tap Chance", T_NUM, 90, 0, 1, 100, 1, "%");
    addDemo(7, "Jump Delay", T_RANGE, 0, 0, 0, 3, 1, "ticks");
    int t = newSetting("Display Name", T_TEXT); scpy(SS[t].text, "Notch", 160); MM[sp].set[MM[sp].setCount++] = t;
    MM[2].open = true; MM[2].openA = 1; MM[7].open = true; MM[7].openA = 1;
    SS[MM[2].sBind].v = 82;
}
#endif

// ---------------------------------------------------------------- JNI
// the native logic (logic/zalard.h, included at the end) hooks into the GUI's open / frame / close
namespace z { void guiFrameHook(JNIEnv*); void guiOpenHook(JNIEnv*); void guiCloseHook(JNIEnv*); }
#define JFN(ret, name) static ret JNICALL mn_##name

static bool inited;

static const char* jintern(JNIEnv* env, jstring js) {
    if (!js) return "";
    const char* c = env->GetStringUTFChars(js, nullptr);
    const char* r = intern(c);
    env->ReleaseStringUTFChars(js, c);
    return r;
}
static void jstr(JNIEnv* e, jstring js, char* out, int cap) {
    out[0] = 0; if (!js) return;
    const char* c = e->GetStringUTFChars(js, nullptr); scpy(out, c, cap); e->ReleaseStringUTFChars(js, c);
}
static bool validS(jint i) { return i >= 0 && i < sCount; }
static bool validM(jint i) { return i >= 0 && i < mCount; }

JFN(void, init)(JNIEnv*, jclass) {
    if (inited) return;
    sCount = mCount = pCount = 0; poolUsed = 0; panelsBuilt = false;
    focused = -1; dragPanel = -1; heDrag = -1;
    noClip();
    inited = true;
}
JFN(jint, version)(JNIEnv*, jclass) { return 4; }

// ---- registration
/** `desc` carries the category ("Combat", "Render", ...); "|fixed" after it = always-on module (no toggle). */
JFN(jint, addModule)(JNIEnv* env, jclass, jstring name, jstring desc) {
    char d[64]; jstr(env, desc, d, 64);
    bool fixed = false;
    for (int i = 0; d[i]; i++) if (d[i] == '|') { fixed = containsCI(d + i, "fixed"); d[i] = 0; break; }
    const char* cat = d[0] ? intern(d) : "Misc";
    panelsBuilt = false;
    return newModule(jintern(env, name), cat, fixed);
}
/** type: 0 bool, 1 bind, 2 number, 3 range, 4 enum, 5 item list, 6 slots, 8 text. */
JFN(jint, addSetting)(JNIEnv* env, jclass, jint mod, jstring section, jstring name, jint type,
                      jfloat v, jfloat v2, jfloat mn, jfloat mx_, jfloat step, jstring unit, jstring options) {
    (void)section;
    if (!validM(mod) || mod != mCount - 1) return -1;          // register modules in order
    Module& m = MM[mod];
    if (m.setCount >= MAX_MOD_SETTINGS) return -1;
    int i = newSetting(jintern(env, name), type);
    if (i < 0) return -1;
    Setting& s = SS[i];
    s.v = v; s.v2 = v2; s.mn = mn; s.mx = mx_ > mn ? mx_ : mn + 1; s.step = step > 0 ? step : 1;
    s.unit = jintern(env, unit);
    if (type == T_ENUM && options) {
        const char* o = jintern(env, options);
        int start = 0;
        for (int k = 0;; k++) {
            if (o[k] == '\n' || o[k] == 0) {
                if (k > start && s.optCount < MAX_OPTS) s.opts[s.optCount++] = intern(o + start, k - start);
                if (!o[k]) break;
                start = k + 1;
            }
        }
        if (!s.optCount) { s.opts[0] = "-"; s.optCount = 1; }
        if ((int)s.v >= s.optCount) s.v = 0;
    }
    m.set[m.setCount++] = i;
    return i;
}
JFN(void, setHint)(JNIEnv* env, jclass, jint i, jstring hint) { if (validS(i)) SS[i].hint = jintern(env, hint); }
JFN(void, setVisible)(JNIEnv*, jclass, jint i, jboolean vis) { if (validS(i)) SS[i].visible = vis; }
JFN(jint, moduleSetting)(JNIEnv*, jclass, jint m, jint which) {   // 0 toggle, 1 bind, 2 visible
    if (!validM(m)) return -1;
    return which == 0 ? MM[m].sToggle : which == 1 ? MM[m].sBind : MM[m].sVisible;
}

// ---- values
JFN(jint, changeSerial)(JNIEnv*, jclass) { return (jint)changeSerial; }
JFN(jfloat, getValue)(JNIEnv*, jclass, jint i) { return validS(i) ? SS[i].v : 0.f; }
JFN(jfloat, getValue2)(JNIEnv*, jclass, jint i) { return validS(i) ? SS[i].v2 : 0.f; }
JFN(void, setValue)(JNIEnv*, jclass, jint i, jfloat v) {
    if (!validS(i)) return;
    Setting& s = SS[i];
    if (s.type == T_ENUM) s.v = clampf(v, 0, (float)(s.optCount - 1));
    else s.v = v;
}
JFN(void, setValue2)(JNIEnv*, jclass, jint i, jfloat v) { if (validS(i)) SS[i].v2 = v; }
JFN(jstring, getText)(JNIEnv* env, jclass, jint i) { return validS(i) ? env->NewStringUTF(SS[i].text) : nullptr; }
JFN(void, setText)(JNIEnv* env, jclass, jint i, jstring t) {
    if (!validS(i) || !t) return;
    jstr(env, t, SS[i].text, 160);
}

// ---- frame / input
JFN(void, onOpen)(JNIEnv* env, jclass) { openAnim = 0; focused = -1; dragPanel = -1; heDrag = -1; bindListen = textEdit = false; z::guiOpenHook(env); }
JFN(void, onClose)(JNIEnv* env, jclass) { unfocus(); dragPanel = -1; heDrag = -1; changeSerial++; z::guiCloseHook(env); }
/** Starts a new frame's vertex list (ESP lines, then optionally the GUI). */
JFN(void, beginOverlay)(JNIEnv*, jclass) { vcount = 0; gA = 1; noClip(); }
JFN(void, espLine)(JNIEnv*, jclass, jfloat ax, jfloat ay, jfloat bx, jfloat by, jfloat width, jint argb) {
    gA = 1; noClip();
    segPx(ax, ay, bx, by, width + 2.0f, ((u32)argb & 0xFF000000u) >> 1 & 0xFF000000u);
    segPx(ax, ay, bx, by, width, (u32)argb);
}
JFN(jint, vertexCount)(JNIEnv*, jclass) { return vcount; }
JFN(jint, frame)(JNIEnv* env, jclass, jfloat w, jfloat h, jfloat x, jfloat y, jfloat dt) { z::guiFrameHook(env); if (!mCount) return vcount; frame(w, h, x, y, dt); return vcount; }
JFN(jobject, vertexBuffer)(JNIEnv* env, jclass) { return env->NewDirectByteBuffer(VB, (jlong)sizeof VB); }
JFN(jint, vertexFloats)(JNIEnv*, jclass) { return (jint)(sizeof(Vtx) / 4); }
JFN(jobject, atlasPixels)(JNIEnv* env, jclass) { return env->NewDirectByteBuffer((void*)ATLAS_PIXELS, (jlong)sizeof ATLAS_PIXELS); }
JFN(jint, atlasWidth)(JNIEnv*, jclass) { return ATLAS_W; }
JFN(jint, atlasHeight)(JNIEnv*, jclass) { return ATLAS_H; }
JFN(void, mouseDown)(JNIEnv*, jclass, jfloat x, jfloat y, jint b) { if (mCount) mouseDown(x, y, b); }
JFN(void, mouseUp)(JNIEnv*, jclass, jfloat, jfloat, jint) { mouseUp(); }
JFN(void, mouseMove)(JNIEnv*, jclass, jfloat x, jfloat y) { mx = x / S; my = y / S; dragTo(x / S, y / S); hudDrag(x / S, y / S); }
JFN(void, wheel)(JNIEnv*, jclass, jfloat y, jfloat dy) { wheel(y, dy); }
JFN(jint, keyDown)(JNIEnv*, jclass, jint key, jint mods) { return keyDown(key, mods); }
JFN(jint, charTyped)(JNIEnv*, jclass, jint cp) { int r = charTyped(cp); changeSerial++; return r; }
JFN(jint, guiKey)(JNIEnv*, jclass) { return guiKey; }
JFN(void, setGuiKey)(JNIEnv*, jclass, jint k) { guiKey = k; }

// ---- top bar pages: Modules / Configs / Friends
static void splitList(int kind, const char* src) {
    char* b = listBuf[kind]; scpy(b, src, 4096); listCount[kind] = 0;
    if (!*b) return;
    listItems[kind][listCount[kind]++] = b;
    for (char* c = b; *c && listCount[kind] < 64; c++) if (*c == '\n') { *c = 0; if (c[1]) listItems[kind][listCount[kind]++] = c + 1; }
}
JFN(jint, page)(JNIEnv*, jclass) { return page; }
JFN(void, setPage)(JNIEnv*, jclass, jint pg) { page = pg >= 0 && pg <= 2 ? pg : 0; }
JFN(void, setList)(JNIEnv* e, jclass, jint kind, jstring joined) {
    if (kind < 0 || kind > 1) return; static char tmp[4096]; jstr(e, joined, tmp, 4096); splitList(kind, tmp);
}
JFN(void, setActiveConfig)(JNIEnv* e, jclass, jstring n) { jstr(e, n, activeCfg, 64); }
JFN(jstring, pollAction)(JNIEnv* e, jclass) {
    if (actHead == actTail) return nullptr;
    jstring r = e->NewStringUTF(actQ[actHead]); actHead = (actHead + 1) % 16; return r;
}
JFN(void, setOverlayItems)(JNIEnv*, jclass, jint, jintArray) {}

// ---- ESP / HUD drawing in framebuffer pixels
JFN(void, espRect)(JNIEnv*, jclass, jfloat x, jfloat y, jfloat w, jfloat h, jfloat r, jint argb) {
    float sv = S; S = 1; gA = 1; noClip();
    rrect(x, y, w, h, r, (u32)argb);
    S = sv;
}
JFN(void, espText)(JNIEnv* e, jclass, jstring js, jfloat x, jfloat cy, jfloat size, jint argb, jint align) {
    char buf[128]; jstr(e, js, buf, 128);
    float sv = S; S = 1; gA = 1; noClip();
    float w = textW(FACE_SEMI, buf, size);
    float tx = align == 1 ? x - w * 0.5f : align == 2 ? x - w : x;
    u32 shadow = (((u32)argb >> 24) * 3 / 5) << 24;
    text(FACE_SEMI, buf, tx + size * 0.07f, cy + size * 0.07f, size, shadow);
    text(FACE_SEMI, buf, tx, cy, size, (u32)argb);
    S = sv;
}
JFN(jfloat, espTextWidth)(JNIEnv* e, jclass, jstring js, jfloat size) {
    char buf[128]; jstr(e, js, buf, 128);
    return textW(FACE_SEMI, buf, size);
}

JFN(void, pixText)(JNIEnv* e, jclass, jstring js, jfloat x, jfloat cy, jfloat size, jint argb, jint align, jint bold, jfloat sh, jint shArgb) {
    char buf[128]; jstr(e, js, buf, 128);
    float sv = S; S = 1; gA = 1; noClip();
    int face = bold ? FACE_PIXB : FACE_PIX;
    float w = textW(face, buf, size);
    float tx = align == 1 ? x - w * 0.5f : align == 2 ? x - w : x;
    if (sh > 0) text(face, buf, tx + sh, cy + sh, size, (u32)shArgb);
    text(face, buf, tx, cy, size, (u32)argb);
    S = sv;
}
JFN(jfloat, pixTextWidth)(JNIEnv* e, jclass, jstring js, jfloat size, jint bold) {
    char buf[128]; jstr(e, js, buf, 128);
    float sv = S; S = 1;                                   // framebuffer px, same as pixText
    float w = textW(bold ? FACE_PIXB : FACE_PIX, buf, size);
    S = sv;
    return w;
}

/** Saved panel spot (design-canvas units, -1 = default) for a category: "Combat", "Movement", ... */
JFN(void, setPanelPos)(JNIEnv* e, jclass, jstring js, jfloat x, jfloat y) {
    char buf[32]; jstr(e, js, buf, 32);
    for (int k = 0; k < NCAT; k++) if (seq(CAT_ORDER[k], buf)) { posX[k] = x; posY[k] = y; }
}

// ---- HUD elements: drag them around while the GUI is open
JFN(void, hudClear)(JNIEnv*, jclass) { if (heDrag < 0) heCount = 0; }
JFN(void, hudAdd)(JNIEnv* e, jclass, jstring label, jfloat x, jfloat y, jfloat w, jfloat h) {
    if (heDrag >= 0 || heCount >= 16) return;
    HudEl& el = HE[heCount++]; jstr(e, label, el.label, 32); el.x = x; el.y = y; el.w = w; el.h = h;
}
JFN(jint, hudMoveIndex)(JNIEnv*, jclass) { return moveIdx; }
JFN(jfloat, hudMoveX)(JNIEnv*, jclass) { return moveX; }
JFN(jfloat, hudMoveY)(JNIEnv*, jclass) { return moveY; }
JFN(void, hudMoveAck)(JNIEnv*, jclass) { moveIdx = -1; }

#include "logic/zalard.h"
#include "logic/jni_table.h"
