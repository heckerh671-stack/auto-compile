// Zalard native logic: HUD (ArrayList, Target HUD, watermark, coords / FPS / ping), ESP and
// Client Spoofer. Everything is drawn straight into the native overlay in framebuffer pixels.
#pragma once

namespace z {

// ---------------------------------------------------------------- drawing in framebuffer pixels
static void fbRect(float x, float y, float w, float h, float r, u32 c) {
    float sv = S; S = 1; gA = 1; noClip(); rrect(x, y, w, h, r, c); S = sv;
}
static float fbTextW(int face, const char* s, float size) { float sv = S; S = 1; float w = textW(face, s, size); S = sv; return w; }
static void fbText(int face, const char* s, float x, float cy, float size, u32 c, int align = 0, float sh = 0, u32 shc = 0) {
    float sv = S; S = 1; gA = 1; noClip();
    float w = textW(face, s, size);
    float tx = align == 1 ? x - w * 0.5f : align == 2 ? x - w : x;
    if (sh > 0) text(face, s, tx + sh, cy + sh, size, shc);
    text(face, s, tx, cy, size, c);
    S = sv;
}
static u32 withAlpha(u32 c, int a) { a = a < 0 ? 0 : a > 255 ? 255 : a; return ((u32)a << 24) | (c & 0x00FFFFFFu); }
static int lerpRgb(int a, int b, float t) {
    int r = (int)(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
    int g = (int)(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
    int bl = (int)((a & 255) + ((b & 255) - (a & 255)) * t);
    return (r << 16) | (g << 8) | bl;
}
static float guiScaleK() { return (float)(SF[SF_FB_H] / dmax(1, SF[SF_SCREEN_H])); }
static float designS() { return fmax_(0.6f, fmin_((float)SF[SF_FB_H] / 760.f, (float)SF[SF_FB_W] / 1280.f)); }
static bool guiOpenNow;          // the click GUI is open (HUD elements can be dragged)

// ================================================================ HUD
namespace hud {
    static int M = -1;
    static SEnum accent;
    static const u32 ACCENTS[4] = { 0xFF6C4CF0u, 0xFFD6402Fu, 0xFF1F8A5Bu, 0xFFB45F00u };
    static u32 acc() { int i = accent.idx(); return ACCENTS[i < 0 ? 0 : i > 3 ? 3 : i]; }
    static const char* const PANELS[6] = { "Combat", "Movement", "Automation", "Render", "Player", "Misc" };
    static SNum panelX[6], panelY[6];

    struct El { const char* label; SBool on; SNum x, y; int w, h; };
    enum { E_LIST, E_TARGET, E_MARK, E_COORDS, E_FPS, E_PING, E_EFFECTS, E_BINDS, E_N };
    static El els[E_N];

    static void addEl(int i, const char* label, float dx, float dy, bool on, bool shown) {
        El& e = els[i];
        e.label = label; e.w = 60; e.h = 13;
        e.on = sBool(label, on);
        if (!shown && e.on.id >= 0) { SS[e.on.id].visible = false; HIDDEN[e.on.id] = true; }
        static char nx[E_N][32], ny[E_N][32];
        Str(nx[i], 32).s(label).s(" X"); Str(ny[i], 32).s(label).s(" Y");
        e.x = sHidden(nx[i], dx, -1, 4000); e.y = sHidden(ny[i], dy, -1, 4000);
    }
    static void pos(El& e, int* q) {
        int sw = (int)SF[SF_SCREEN_W], sh = (int)SF[SF_SCREEN_H];
        int px = e.x() < 0 ? sw - e.w : iround(e.x()), py = e.y() < 0 ? 5 : iround(e.y());
        q[0] = imax(0, imin(px, sw - e.w)); q[1] = imax(0, imin(py, sh - e.h));
    }
    static void framedBox(float x, float y, float w, float h, float s, u32 fill) {
        float b = fmax_(1, (float)iround(2 * s));
        fbRect(x, y, w, h, 0, 0xFF000000u);
        fbRect(x + b, y + b, w - 2 * b, h - 2 * b, 0, fill);
    }
    static void rr(float x, float y, float w, float h, u32 c) { fbRect((float)iround(x), (float)iround(y), (float)iround(w), (float)iround(h), 0, c); }
    static void tx(const char* s, float x, float cy, float size, u32 c, bool bold) {
        fbText(bold ? FACE_PIXB : FACE_PIX, s, (float)iround(x), (float)iround(cy), size, c);
    }

    // ---- ArrayList: the GUI's notched tags, sliding in / out
    static float anim[MAX_M]; static i64 lastNs;
    static bool listed(int m) { return isOn(m) && m != M && !LG[m].alwaysOn; }
    static void tag(float x, float y, float w, float h, float s, bool right, u32 c) {
        float n = fmax_(1, (float)iround(4 * s));
        if (right) { fbRect(x + 2 * n, y, w - 2 * n, h, 0, c); fbRect(x + n, y + n, n + 1, h - n, 0, c); fbRect(x, y + 2 * n, n + 1, h - 2 * n, 0, c); }
        else { fbRect(x, y, w - 2 * n, h, 0, c); fbRect(x + w - 2 * n - 1, y + n, n + 1, h - n, 0, c); fbRect(x + w - n - 1, y + 2 * n, n + 1, h - 2 * n, 0, c); }
    }
    static void drawList() {
        El& e = els[E_LIST];
        float dt = lastNs == 0 ? 0 : fmin_(0.1f, (float)((NOWNS - lastNs) / 1e9));
        lastNs = NOWNS;
        float k = guiScaleK(), S_ = designS();
        int rows[MAX_M], n = 0; float wd[MAX_M];
        for (int m = 0; m < mCount; m++) {
            float want = listed(m) ? 1.f : 0.f;
            anim[m] = anim[m] < want ? fmin_(want, anim[m] + dt / 0.18f) : fmax_(want, anim[m] - dt / 0.18f);
            if (anim[m] > 0.001f) { rows[n] = m; wd[n] = fbTextW(FACE_PIX, lc(MM[m].name), 16 * S_) / S_; n++; }
        }
        for (int i = 1; i < n; i++) for (int j = i; j > 0 && wd[j] > wd[j - 1]; j--) {   // widest first
            float tw = wd[j]; wd[j] = wd[j - 1]; wd[j - 1] = tw; int tr = rows[j]; rows[j] = rows[j - 1]; rows[j - 1] = tr;
        }
        float maxW = 0; int listedN = 0;
        for (int i = 0; i < n; i++) if (listed(rows[i])) { listedN++; if (wd[i] > maxW) maxW = wd[i]; }
        float wD = 16 + maxW + 10 + 6, hD = fmax_(24, listedN * 26 - 2.f);
        e.w = (int)dceil(wD * S_ / k); e.h = (int)dceil(hD * S_ / k);
        int q[2]; pos(e, q);
        float X = q[0] * k, Y = q[1] * k, W = e.w * k;
        bool right = q[0] + e.w / 2 > (int)SF[SF_SCREEN_W] / 2;
        u32 ac = acc();
        float ty = Y;
        for (int i = 0; i < n; i++) {
            float a = anim[rows[i]], ez = 1 - (1 - a) * (1 - a) * (1 - a);
            float rw = (float)iround((16 + wd[i] + 10 + 6) * S_);
            float slide = (1 - ez) * (rw + 8 * S_);
            float x = (float)iround(right ? X + W - rw + slide : X - slide), y = (float)iround(ty);
            int alpha = iround(255 * fmin_(1, a * 1.5f));
            float rh = (float)iround(24 * S_), bw = (float)iround(6 * S_);
            tag(x, y, rw, rh, S_, right, withAlpha(0xFF0D0D10u, alpha));
            fbRect(right ? x + rw - bw : x, y, bw, rh, 0, withAlpha(ac, alpha));
            fbText(FACE_PIX, lc(MM[rows[i]].name), x + (float)iround(16 * S_), y + (float)iround(12 * S_), 16 * S_,
                   withAlpha(0xFFFFFFFFu, alpha), 0, fmax_(1, (float)iround(S_)), withAlpha(0xFF1A1A1Au, alpha));
            ty += 26 * S_ * ez;
        }
    }
    static void drawMark() {
        El& e = els[E_MARK];
        float k = guiScaleK(), S_ = designS();
        float bw = fbTextW(FACE_PIXB, "Zalard", 22 * S_), vw = fbTextW(FACE_PIX, " 1.0", 14 * S_);
        e.w = (int)dceil((bw + vw + 2 * S_) / k); e.h = (int)dceil(30 * S_ / k);
        float X = (float)iround(16 * S_), Y = (float)iround(11 * S_);
        fbText(FACE_PIXB, "Zalard", X, Y + 14 * S_, 22 * S_, 0xFFFFFFFFu, 0, 2 * S_, 0xFF3A3A3Au);
        fbText(FACE_PIX, " 1.0", X + bw, Y + 17 * S_, 14 * S_, 0xFFBFC4D0u);
    }
    static void drawText(int idx, const char* label, const char* value) {
        El& e = els[idx];
        float k = guiScaleK(), S_ = designS(), size = 14 * S_, gap = 6 * S_;
        float lw = *label ? fbTextW(FACE_PIX, label, size) + gap : 0, vw = fbTextW(FACE_PIX, value, size);
        float H = (float)iround(24 * S_), bar = (float)iround(4 * S_), pl = 10 * S_, pr = 10 * S_;
        float W = (float)iround(bar + pl + lw + vw + pr);
        e.w = (int)dceil(W / k); e.h = (int)dceil(H / k);
        int q[2]; pos(e, q);
        float X = (float)iround(q[0] * k), Y = (float)iround(q[1] * k);
        framedBox(X, Y, W, H, S_, 0xFF0F1014u);
        rr(X, Y, bar, H, acc());
        float cy = Y + H / 2;
        if (lw > 0) tx(label, X + bar + pl, cy, size, 0xFF8A90A0u, false);
        tx(value, X + bar + pl + lw, cy, size, 0xFFFFFFFFu, false);
    }
    // ---- multi-row panel (Potion Effects, Keybinds): left label + right value, framed like Target HUD
    static void drawPanel(int idx, char rowL[][48], char rowR[][24], int n) {
        El& e = els[idx];
        if (n <= 0) { e.w = 0; e.h = 0; return; }
        float k = guiScaleK(), S_ = designS();
        float size = 13 * S_, pad = 10 * S_, bar = (float)iround(4 * S_), rowH = 18 * S_, gap = 16 * S_;
        float maxW = 0;
        for (int i = 0; i < n; i++) {
            float lw = fbTextW(FACE_PIX, rowL[i], size);
            float rw = rowR[i][0] ? fbTextW(FACE_PIX, rowR[i], size) + gap : 0;
            if (lw + rw > maxW) maxW = lw + rw;
        }
        float W = (float)iround(bar + pad + maxW + pad), H = (float)iround(n * rowH + 8 * S_);
        e.w = (int)dceil(W / k); e.h = (int)dceil(H / k);
        int q[2]; pos(e, q);
        float X = (float)iround(q[0] * k), Y = (float)iround(q[1] * k);
        framedBox(X, Y, W, H, S_, 0xFF0F1014u);
        rr(X, Y, bar, H, acc());
        float x0 = X + bar + pad, y = Y + 4 * S_;
        for (int i = 0; i < n; i++) {
            float cy = y + rowH / 2;
            tx(rowL[i], x0, cy, size, 0xFFE8EAF0u, false);
            if (rowR[i][0]) { float rw = fbTextW(FACE_PIX, rowR[i], size); tx(rowR[i], X + W - pad - rw, cy, size, acc(), false); }
            y += rowH;
        }
    }
    static void cpyField(char* dst, const char* src, int cap) { int i = 0; for (; src[i] && i < cap - 1; i++) dst[i] = src[i]; dst[i] = 0; }
    // Potion effects, newest at top, as "Name  M:SS" rows (Java formats the line; we split on the last space).
    static void drawEffects() {
        char buf[512];
        if (!R(R_EFFECTS, nullptr, buf, sizeof buf) || !buf[0]) { els[E_EFFECTS].w = 0; els[E_EFFECTS].h = 0; return; }
        static char L[16][48], Rt[16][24];
        int n = 0; char* p = buf;
        while (*p && n < 16) {
            char* nl = p; while (*nl && *nl != '\n') nl++;
            int len = (int)(nl - p); char line[96]; if (len > 95) len = 95;
            for (int i = 0; i < len; i++) line[i] = p[i]; line[len] = 0;
            int sp = -1; for (int i = 0; i < len; i++) if (line[i] == ' ') sp = i;
            if (sp > 0) { line[sp] = 0; cpyField(L[n], line, 48); cpyField(Rt[n], line + sp + 1, 24); }
            else { cpyField(L[n], line, 48); Rt[n][0] = 0; }
            n++; p = *nl ? nl + 1 : nl;
        }
        drawPanel(E_EFFECTS, L, Rt, n);
    }
    // Active keybinds: every bound module as "Module  [KEY]", key label via the GUI's own keyName().
    static void drawBinds() {
        static char L[MAX_M][48], Rt[MAX_M][24];
        int n = 0;
        for (int m = 0; m < mCount && n < MAX_M; m++) {
            if (m == M) continue;
            char kb[16]; const char* kn = keyName(bindOf(m), kb);
            if (!kn || eq(kn, "None")) continue;
            cpyField(L[n], MM[m].name, 48);
            cpyField(Rt[n], kn, 24);
            n++;
        }
        drawPanel(E_BINDS, L, Rt, n);
    }

    // Target HUD
    static i64 cachedAt = -1; static Uuid cachedId; static float shownFrac = 1; static i64 tLastNs; static Uuid lastId;
    static void drawTarget() {
        El& e = els[E_TARGET];
        i64 wt = (i64)SF[SF_WORLD_TIME];
        if (wt != cachedAt) { cachedAt = wt; Ent t = tg::pickOne(6.0, true); cachedId = t.ok() ? t.uuid() : Uuid{0, 0}; }
        Ent t = entByUuid(cachedId);
        if (!t.ok() && !guiOpenNow) return;
        const char* name = t.ok() ? t.name() : "Target";
        double hpV = t.ok() ? t.health() : 20, maxV = t.ok() ? dmax(1, t.maxHealth()) : 20;
        float frac = (float)dclamp(hpV / maxV, 0, 1);
        float dt = tLastNs == 0 ? 0 : fmin_(0.1f, (float)((NOWNS - tLastNs) / 1e9));
        tLastNs = NOWNS;
        Uuid id = t.ok() ? t.uuid() : Uuid{0, 0};
        if (!(id == lastId)) { shownFrac = frac; lastId = id; }
        shownFrac += (frac - shownFrac) * fmin_(1, dt * 10);
        char hp[32], dist[32], armor[16];
        Str(hp, 32).f(hpV, 1).s(" HP");
        Str(dist, 32).f(t.ok() ? dsqrt(t.distSq(selfEye())) : 3.0, 1).s("m");
        Str(armor, 16).f(t.ok() ? t.armor() : 20, 0);
        float k = guiScaleK(), S_ = designS();
        float W = (float)iround(190 * S_), headH = (float)iround(30 * S_), H = (float)iround(headH + 74 * S_);
        e.w = (int)dceil(W / k); e.h = (int)dceil(H / k);
        int q[2]; pos(e, q);
        float X = (float)iround(q[0] * k), Y = (float)iround(q[1] * k), b = fmax_(1, (float)iround(2 * S_));
        framedBox(X, Y, W, H, S_, 0xFF0F1014u);
        rr(X + b, Y + b, W - 2 * b, headH - b, 0xFF1B1D26u);
        rr(X + b, Y + headH - 2 * S_, W - 2 * b, (float)iround(2 * S_), acc());
        float pad = 12 * S_;
        tx(name, X + pad, Y + headH / 2, 15 * S_, 0xFFE8EAF0u, true);
        float r1 = Y + headH + 16 * S_;
        tx(hp, X + pad, r1, 14 * S_, 0xFFFFFFFFu, false);
        float dw = fbTextW(FACE_PIX, dist, 13 * S_);
        tx(dist, X + W - pad - dw, r1, 13 * S_, 0xFF8A90A0u, false);
        float by = r1 + 13 * S_, bw = W - 2 * pad;
        rr(X + pad, by, bw, (float)iround(4 * S_), 0xFF2B2F3Au);
        rr(X + pad, by, (float)iround(bw * fclamp(shownFrac, 0, 1)), (float)iround(4 * S_), acc());
        float r2 = by + 22 * S_;
        tx("Armor", X + pad, r2, 13 * S_, 0xFF8A90A0u, false);
        float aw = fbTextW(FACE_PIX, armor, 13 * S_);
        tx(armor, X + W - pad - aw, r2, 13 * S_, 0xFFC9CED8u, false);
    }

    // ---- HUD editor: the GUI drags these (framebuffer px <-> GUI-scaled px)
    static int shown[E_N], shownN;
    static void syncEditor() {
        if (!guiOpenNow) { shownN = 0; return; }
        float scale = (float)(SF[SF_FB_W] / dmax(1, SF[SF_SCREEN_W]));
        if (moveIdx >= 0) {
            if (moveIdx < shownN) {
                El& e = els[shown[moveIdx]];
                e.x.set((float)iround(moveX / scale)); e.y.set((float)iround(moveY / scale));
                dirty = true;
            }
            moveIdx = -1;
        }
        if (heDrag >= 0) return;
        heCount = 0; shownN = 0;
        for (int i = 0; i < E_N; i++) {
            if (i == E_MARK || !els[i].on()) continue;
            El& e = els[i];
            int q[2]; pos(e, q);
            shown[shownN++] = i;
            HudEl& h = HE[heCount++];
            copy(h.label, e.label, 32);
            h.x = q[0] * scale; h.y = q[1] * scale; h.w = e.w * scale; h.h = e.h * scale;
        }
    }

    static void render() {
        els[E_MARK].on.set(true);                       // the watermark can't be removed
        if (els[E_LIST].on()) drawList();
        if (els[E_TARGET].on()) drawTarget();
        drawMark();
        char b[64];
        if (els[E_COORDS].on()) { Vec3 p = selfPos(); Str s(b, 64); s.f(p.x, 1).s("  ").f(p.y, 1).s("  ").f(p.z, 1); drawText(E_COORDS, "XYZ", b); }
        if (els[E_FPS].on()) { Str(b, 64).i((i64)SF[SF_FPS]); drawText(E_FPS, "FPS", b); }
        if (els[E_PING].on()) { Str(b, 64).i((i64)SF[SF_PING]).s(" ms"); drawText(E_PING, "Ping", b); }
        if (els[E_EFFECTS].on()) drawEffects();
        if (els[E_BINDS].on()) drawBinds();
        syncEditor();
    }
    static void reg() {
        const char* ACC[] = { XS("Violet"), XS("Red"), XS("Green"), XS("Orange") };
        M = mod(XS("HUD"), XS("Settings"), true);
        accent = sEnum(XS("Accent"), 0, ACC, 4);
        static char px[6][40], py[6][40];
        for (int i = 0; i < 6; i++) {
            Str(px[i], 40).s("Panel ").s(PANELS[i]).s(" Pos X"); Str(py[i], 40).s("Panel ").s(PANELS[i]).s(" Pos Y");
            panelX[i] = sHidden(px[i], -10000, -10000, 8000); panelY[i] = sHidden(py[i], -10000, -10000, 8000);
        }
        addEl(E_LIST, "ArrayList", -1, -1, true, true);
        addEl(E_TARGET, "Target HUD", 10, 120, false, true);
        addEl(E_MARK, "Watermark", 7, 5, true, false);
        addEl(E_COORDS, "Coordinates", 8, 40, false, true);
        addEl(E_FPS, "FPS", 8, 60, false, true);
        addEl(E_PING, "Ping", 8, 80, false, true);
        addEl(E_EFFECTS, "Potion Effects", 8, 120, false, true);
        addEl(E_BINDS, "Keybinds", -1, 120, false, true);
        LG[M].hud = render;
    }
    /** GUI panel spots <- settings (when the GUI opens). */
    static void pushPanels() { for (int i = 0; i < 6; i++) { posX[i] = panelX[i](); posY[i] = panelY[i](); } }
}

// ================================================================ ESP
namespace esp {
    static int M = -1;
    static SBool players, mobs, storage, style3d_unused, nameTags, healthBar, fill, tracers, items; static SEnum style; static SNum range;
    static SText itemFilter; static char lastFilter[160];
    static void drawItems();
    static const float LINE_WIDTH = 1.5f;
    static const int PLAYER = 0xFF4C72FF, FRIEND = 0xFF55FF7F, MOB = 0xFFFFAA44, LOCKED = 0xFFFF4A5A;
    static const int STORE_C[5] = { 0, (int)0xFFE8B04A, (int)0xFFB46CFF, (int)0xFF33D6C8, (int)0xFFB07B4F };
    static double storeCache[120][4]; static int storeN; static i64 lastScan = -1000000;

    static void outline(float x, float y, float w, float h, float t, u32 c) {
        fbRect(x, y, w, t, 0, c); fbRect(x, y + h - t, w, t, 0, c);
        fbRect(x, y + t, t, h - 2 * t, 0, c); fbRect(x + w - t, y + t, t, h - 2 * t, 0, c);
    }
    static void lineBox(double x0, double y0, double z0, double x1, double y1, double z1, int c) {
        double v[8][3] = { {x0,y0,z0},{x1,y0,z0},{x1,y0,z1},{x0,y0,z1},{x0,y1,z0},{x1,y1,z0},{x1,y1,z1},{x0,y1,z1} };
        static const int ed[12][2] = { {0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7} };
        for (int i = 0; i < 12; i++) { const double* a = v[ed[i][0]]; const double* b = v[ed[i][1]];
            D(D_ESP_LINE, a[0], a[1], a[2], b[0], b[1], b[2], (double)(u32)c); }
    }
    static void nameTag(const Ent& e, const double* b, float frac, double hp, int rgb, bool player, float k, float boxTop) {
        if (D(D_PROJECT_POINT, (b[0] + b[3]) * 0.5, b[4] + 0.18, (b[2] + b[5]) * 0.5) != 1) return;
        float hx = (float)OUTB[0], hy = (float)OUTB[1];
        float size = (float)iround(13 * k);
        char hpT[16], dist[16];
        Str(hpT, 16).i((i64)dceil(hp));
        Str(dist, 16).i((i64)dsqrt(e.distSq(selfEye()))).s("m");
        const char* name = e.name();
        float gap = (float)iround(6 * k), pad = (float)iround(7 * k), ph = (float)iround(size + 9 * k), line = fmax_(1, (float)iround(2 * k));
        float small = (float)iround(size * 0.85f);
        float nw = fbTextW(FACE_PIX, name, size), hw = fbTextW(FACE_PIX, hpT, size), dw = fbTextW(FACE_PIX, dist, small);
        float W = (float)iround(nw + gap + hw + gap + dw + pad * 2);
        float px = (float)iround(hx - W / 2), py = (float)iround(fmin_(hy, boxTop) - ph - 4 * k);
        fbRect(px - 1, py - 1, W + 2, ph + 2, 0, 0xFF000000u);
        fbRect(px, py, W, ph, 0, 0xE60F1014u);
        fbRect(px, py + ph - line, W, line, 0, 0xFF000000u | (u32)rgb);
        float cy = (float)iround(py + (ph - line) / 2), x = px + pad;
        fbText(FACE_PIX, name, x, cy, size, player ? 0xFFF2F4F8u : 0xFFE0C080u); x += nw + gap;
        fbText(FACE_PIX, hpT, x, cy, size, 0xFF000000u | (u32)lerpRgb(0xFF4A4A, 0x4CFF7A, frac)); x += hw + gap;
        fbText(FACE_PIX, dist, x, cy, small, 0xFF8A90A0u);
    }
    static void drawEnt(const Ent& e, int color, bool player) {
        const double* b = e.rbox();
        Vec3 eye = selfEye();
        if (tracers()) D(D_ESP_LINE, eye.x, eye.y - 0.2, eye.z, (b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2, (double)(u32)((color & 0x00FFFFFF) | 0xA0000000));
        bool twoD = style.idx() == 0;
        if (!twoD) lineBox(b[0], b[1], b[2], b[3], b[4], b[5], color);
        if (D(D_PROJECT_BOX, b[0], b[1], b[2], b[3], b[4], b[5]) != 1) return;
        float x0 = (float)OUTB[0], y0 = (float)OUTB[1], w = (float)(OUTB[2] - OUTB[0]), h = (float)(OUTB[3] - OUTB[1]);
        float k = fmax_(1, (float)SF[SF_FB_H] / 1080.f);
        if (w < 2 || h < 2) return;
        int rgb = color & 0x00FFFFFF;
        if (twoD) {
            if (fill()) fbRect(x0, y0, w, h, 2 * k, 0x22000000u | (u32)rgb);
            float t = 1.6f * k, o = t + 2 * k;
            outline(x0 - (o - t) / 2, y0 - (o - t) / 2, w + (o - t), h + (o - t), o, 0x90000000u);
            outline(x0, y0, w, h, t, 0xFF000000u | (u32)rgb);
        }
        double hp = e.health(), mx = dmax(1, e.maxHealth());
        float frac = (float)dclamp(hp / mx, 0, 1);
        if (healthBar()) {
            float bw = 2.5f * k, bx = x0 - bw - 4 * k;
            fbRect(bx - k, y0 - k, bw + 2 * k, h + 2 * k, k, 0xA0000000u);
            fbRect(bx, y0 + h * (1 - frac), bw, h * frac, k, 0xFF000000u | (u32)lerpRgb(0xFF4A4A, 0x4CFF7A, frac));
        }
        if (nameTags()) nameTag(e, b, frac, hp, rgb, player, k, y0);
    }
    static void render() {
        D(D_LINE_WIDTH, LINE_WIDTH);
        Vec3 eye = selfEye(); double rSq = range() * range();
        snapEnts();
        for (int i = 0; i < entCount; i++) {
            Ent e = entAt(i);
            if (e.self() || e.dead() || e.invisible() || e.distSq(eye) > rSq) continue;
            if (e.player()) {
                if (!players()) continue;
                bool fr = isFriend(e.name()), lk = !lock::locked.null() && lock::locked == e.uuid();
                drawEnt(e, fr ? FRIEND : lk ? LOCKED : PLAYER, true);
            } else if (mobs()) drawEnt(e, MOB, false);
        }
        if (storage()) {
            i64 t = (i64)SF[SF_WORLD_TIME];
            if (t - lastScan >= 10 || t < lastScan) {
                lastScan = t;
                int n = (int)D(D_STORAGE_NEAR, range());
                storeN = 0;
                for (int i = 0; i < n && i < 120; i++) { for (int k = 0; k < 4; k++) storeCache[storeN][k] = OUTB[i * 4 + k]; storeN++; }
            }
            for (int i = 0; i < storeN; i++) {
                double* s = storeCache[i];
                int kind = (int)s[3];
                lineBox(s[0], s[1], s[2], s[0] + 1, s[1] + 1, s[2] + 1, STORE_C[kind < 0 || kind > 4 ? 0 : kind]);
            }
        }
        if (items()) drawItems();
    }
    // Item ESP: small boxes (+ tracers) on dropped items whose id matches the filter. The filter text is
    // pushed to Java (T_SET_ITEM_FILTER) only when it changes; Java does the id match and returns positions.
    static const int ITEM_C = 0xFFF2C14E;
    static void drawItems() {
        const char* f = itemFilter();
        if (!eq(f, lastFilter)) { T(T_SET_ITEM_FILTER, f); copy(lastFilter, f, 160); }
        int n = (int)D(D_ITEMS_NEAR, range());
        Vec3 eye = selfEye();
        for (int i = 0; i < n; i++) {
            double x = OUTB[i * 3], y = OUTB[i * 3 + 1], z = OUTB[i * 3 + 2];
            if (tracers()) D(D_ESP_LINE, eye.x, eye.y - 0.2, eye.z, x, y + 0.13, z, (double)(u32)((ITEM_C & 0x00FFFFFF) | 0xA0000000));
            lineBox(x - 0.14, y, z - 0.14, x + 0.14, y + 0.28, z + 0.14, ITEM_C);
        }
    }
    static void disable() { storeN = 0; lastScan = -1000000; }
    static void reg() {
        const char* STYLES[] = { XS("2D"), XS("3D") };
        M = mod(XS("ESP"), XS("Render"));
        players = sBool(XS("Players"), true);
        mobs = sBool(XS("Mobs"), false);
        storage = sBool(XS("Storage"), false);
        style = sEnum(XS("Style"), 0, STYLES, 2);
        nameTags = sBool(XS("Name Tags"), true);
        healthBar = sBool(XS("Health Bar"), true);
        fill = sBool(XS("Fill"), true);
        tracers = sBool(XS("Tracers"), false);
        items = sBool(XS("Items"), false);
        itemFilter = sText(XS("Item Filter"), "", "item id, blank = all (e.g. diamond)");
        range = sNum(XS("Range"), 64, 8, 256, "blocks");
        LG[M].render = render; LG[M].disable = disable;
    }
}

// ================================================================ Client Spoofer
// Name / skin / cape on your screen only, plus Ping Spoof. The Java side does the downloading and
// the texture work; this decides what's applied.
namespace spoof {
    static int M = -1;
    static SBool spoofName, spoofSkin, spoofCape, spoofOther, pingSpoof; static SText selfName, selfSource, otherTarget, otherName, otherSource;
    static SEnum cape; static SNum fixedPing;
    static const char* const CAPES[] = { "Migrator", "Vanilla", "Experience", "Pan", "Common", "15th Anniv", "Cherry", "Purple Heart",
        "Follower's", "Copper", "Mojang Office", "MCC 15th", "Menace", "Home", "Yearn", "Crafter", "Builder", "Moonlight", "Twisted",
        "Hero", "Aurora", "Zombie Horse", "Founder's", "Minecon 2011", "Minecon 2012", "Minecon 2013", "Minecon 2015", "Minecon 2016",
        "Mojang Classic", "Mojang", "Mojang Studios", "Mapmaker", "Translator", "Translator CN", "Translator JP", "Moderator", "Cobalt",
        "Scrolls", "Turtle", "Prismarine", "Birthday", "Valentine", "Bacon", "dB", "Document", "Oxeye", "Millionth Sale", "JulianClark", "MrMessiah" };
    static char aName[64], aSkin[64], aOther[160], aCape[64]; static bool appliedOnce; static i64 lastCheck; static int lastFlags = -1;
    static bool visSkin() { return spoofSkin(); }
    static bool visCape() { return spoofCape(); }

    static void tick() {
        if (NOW - lastCheck < 100) return;
        lastCheck = NOW;
        char want[160];
        copy(want, spoofName() ? selfName() : "", 64);
        if (!appliedOnce || !eq(want, aName)) { T(T_SPOOF_NAME, want); copy(aName, want, 64); }
        copy(want, spoofSkin() ? selfSource() : "", 64);
        if (!appliedOnce || !eq(want, aSkin)) { T(T_SPOOF_SKIN, want); copy(aSkin, want, 64); }
        copy(want, spoofCape() ? cape.str() : "", 64);
        if (!appliedOnce || !eq(want, aCape)) { T(T_SPOOF_CAPE, want); copy(aCape, want, 64); }
        if (spoofOther() && *otherTarget()) {
            Str k(want, 160); k.s(otherTarget()).c('|').s(otherName()).c('|').s(otherSource());
            if (!eq(want, aOther)) { T(T_SPOOF_OTHER, otherTarget(), otherName(), otherSource()); copy(aOther, want, 160); }
        }
        appliedOnce = true;
        int flags = (spoofName() ? 1 : 0) | (spoofCape() ? 2 : 0);
        if (flags != lastFlags) { D(D_SPOOF_FLAGS, flags); lastFlags = flags; }
    }
    /** ms to hold the next keep-alive so the tab list shows your Fixed Ping (0 = answer now). */
    static i64 pingDelay(int shown) {
        if (!pingSpoof()) return 0;
        double target = fixedPing(), ms = target;
        if (shown >= 0) ms = 4 * target - 3 * shown;
        return (i64)dclamp(ms, 0, 12000);
    }
    static void reg() {
        M = mod(XS("Client Spoofer"), XS("Misc"), true);
        spoofName = sBool(XS("Spoof Name"), true);
        selfName = sText(XS("Display Name"), "Herobrine", "any text");
        spoofSkin = sBool(XS("Spoof Skin"), false);
        selfSource = vis(sText(XS("Source Skin"), "Notch", "player name"), visSkin);
        spoofCape = sBool(XS("Spoof Cape"), false);
        cape = vis(sEnum(XS("Cape"), 0, CAPES, (int)(sizeof CAPES / sizeof CAPES[0])), visCape);
        spoofOther = sBool(XS("Spoof Other"), false);
        otherTarget = sText(XS("Target Player"), "", "player name");
        otherName = sText(XS("Target New Name"), "", "any text");
        otherSource = sText(XS("Target Skin"), "", "player name");
        pingSpoof = sBool(XS("Ping Spoof"), false);
        fixedPing = sNum(XS("Fixed Ping"), 350, 0, 20000, "ms");
        LG[M].tick = tick;
    }
}

} // namespace z
