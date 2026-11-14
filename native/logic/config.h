// Zalard native logic: config.json (same format as before, so old configs load), named configs,
// friends.txt, and the GUI's Configs / Friends / Reset / panel actions.
#pragma once

namespace z {

static char IOBUF[256 * 1024];

static void jsonStr(Str& o, const char* s) {
    o.c('"');
    for (; *s; s++) {
        if (*s == '"' || *s == '\\') { o.c('\\'); o.c(*s); }
        else if (*s == '\n') o.s("\\n");
        else if ((unsigned char)*s < 0x20) o.c(' ');
        else o.c(*s);
    }
    o.c('"');
}
static void rangeStr(Str& o, float v) {
    if (v == (float)(i64)v) o.i((i64)v);
    else { float r = (float)iround(v * 100) / 100.f; o.g(r); }
}

static void writeConfig(const char* path) {
    Str o(IOBUF, sizeof IOBUF);
    o.s("{\n");
    for (int m = 0; m < mCount; m++) {
        if (m) o.s(",\n");
        o.s("  "); jsonStr(o, MM[m].name); o.s(": {\n");
        o.s("    \"enabled\": ").s(isOn(m) ? "true" : "false");
        o.s(",\n    \"bind\": ").i(bindOf(m));
        for (int k = 0; k < MM[m].setCount; k++) {
            int i = MM[m].set[k]; Setting& s = SS[i];
            o.s(",\n    "); jsonStr(o, s.name); o.s(": ");
            switch (s.type) {
            case T_BOOL: o.s(s.v != 0 ? "true" : "false"); break;
            case T_NUM: o.g(s.v); break;
            case T_RANGE: { char r[48]; Str rs(r, 48); rangeStr(rs, s.v); rs.c('-'); rangeStr(rs, s.v2); jsonStr(o, r); break; }
            case T_ENUM: jsonStr(o, s.opts[(int)s.v >= 0 && (int)s.v < s.optCount ? (int)s.v : 0]); break;
            case T_TEXT: jsonStr(o, s.text); break;
            default: o.s("null");
            }
        }
        o.s("\n  }");
    }
    o.s("\n}\n");
    T(T_WRITE_FILE, path, IOBUF);
}

// ---- tiny flat JSON reader: { "Module": { "key": value, ... }, ... }
struct JReader {
    const char* p;
    void ws() { while (*p == ' ' || *p == '\n' || *p == '\r' || *p == '\t') p++; }
    bool str(char* out, int cap) {
        ws(); if (*p != '"') return false; p++;
        int n = 0;
        while (*p && *p != '"') {
            char c = *p++;
            if (c == '\\' && *p) { c = *p++; if (c == 'n') c = '\n'; }
            if (n < cap - 1) out[n++] = c;
        }
        out[n] = 0;
        if (*p == '"') p++;
        return true;
    }
    void skipTo(char c) { while (*p && *p != c) p++; if (*p) p++; }
};
static int moduleByName(const char* n) { for (int m = 0; m < mCount; m++) if (eq(MM[m].name, n)) return m; return -1; }
/** Old module names that still load into today's modules. */
static int moduleForConfig(const char* n) {
    int m = moduleByName(n);
    if (m >= 0) return m;
    const char* OLD[][2] = {
        { XS("Trigger Bot"), XS("TBot") }, { XS("Auto Crystal"), XS("Crystal") }, { XS("Auto Anchor"), XS("Anchor") }, { XS("Auto Totem"), XS("Totem") },
        { XS("Spear Lunge"), XS("Lunge") }, { XS("Fly"), XS("Flight") }, { XS("Auto Cart"), XS("Cart") }, { XS("Crossbow Cart"), XS("Cart") },
        { XS("Spoof"), XS("Client Spoofer") }, { XS("Movement Reset"), XS("Combo Assist") }, { XS("Anti Knockback"), XS("Velocity") } };
    for (auto& o : OLD) if (eq(o[0], n)) return moduleByName(o[1]);
    return -1;
}
static int settingIn(int m, const char* n) {
    for (int k = 0; k < MM[m].setCount; k++) if (eq(SS[MM[m].set[k]].name, n)) return MM[m].set[k];
    return -1;
}
static void applyValue(int i, JReader& r) {
    Setting& s = SS[i];
    r.ws();
    char sv[200];
    if (*r.p == '"') {
        r.str(sv, sizeof sv);
        if (s.type == T_ENUM) { for (int o = 0; o < s.optCount; o++) if (eq(s.opts[o], sv)) s.v = (float)o; }
        else if (s.type == T_TEXT) copy(s.text, sv, 160);
        else if (s.type == T_RANGE) {
            double a, b; const char* e;
            if (parseNum(sv, &a, &e)) {
                const char* dash = e; while (*dash == ' ') dash++;
                if (*dash == '-' && parseNum(dash + 1, &b)) { if (b < a) { double t = a; a = b; b = t; } }
                else b = a;
                s.v = fclamp((float)a, s.mn, s.mx); s.v2 = fclamp((float)b, s.mn, s.mx);
            }
        }
        return;
    }
    if (startsWith(r.p, "true")) { r.p += 4; if (s.type == T_BOOL) s.v = 1; return; }
    if (startsWith(r.p, "false")) { r.p += 5; if (s.type == T_BOOL) s.v = 0; return; }
    if (startsWith(r.p, "null")) { r.p += 4; return; }
    double d; const char* e;
    if (parseNum(r.p, &d, &e)) {
        r.p = e;
        if (s.type == T_NUM) s.v = (float)d;
        else if (s.type == T_RANGE) s.v = s.v2 = fclamp((float)d, s.mn, s.mx);
    } else r.p++;
}
static bool readConfig(const char* path) {
    if (!R(R_READ_FILE, path, IOBUF, sizeof IOBUF)) return false;
    JReader r{ IOBUF };
    r.ws(); if (*r.p != '{') return false; r.p++;
    char key[96];
    while (*r.p) {
        r.ws();
        if (*r.p == '}') break;
        if (!r.str(key, 96)) break;
        r.skipTo(':'); r.ws();
        if (*r.p != '{') break;
        r.p++;
        int m = moduleForConfig(key);
        while (*r.p) {
            r.ws();
            if (*r.p == '}') { r.p++; break; }
            char k[96];
            if (!r.str(k, 96)) { r.p++; continue; }
            r.skipTo(':');
            if (m >= 0 && eq(k, "enabled")) { r.ws(); bool on = startsWith(r.p, "true"); r.p += on ? 4 : 5; setOn(m, on); }
            else if (m >= 0 && eq(k, "bind")) { double d; const char* e; r.ws(); if (parseNum(r.p, &d, &e)) { r.p = e; SS[MM[m].sBind].v = (float)d; } }
            else {
                int i = m >= 0 ? settingIn(m, k) : -1;
                if (i >= 0 && (!HIDDEN[i] || endsWith(k, " X") || endsWith(k, " Y"))) applyValue(i, r);
                else { JReader skip = r; skip.ws(); if (*skip.p == '"') { char t[200]; skip.str(t, 200); r = skip; } else { while (*r.p && *r.p != ',' && *r.p != '}') r.p++; } }
            }
            r.ws(); if (*r.p == ',') r.p++;
        }
        r.ws(); if (*r.p == ',') r.p++;
    }
    return true;
}

// ---- friends.txt
static void saveFriends() {
    Str o(IOBUF, sizeof IOBUF);
    for (int i = 0; i < friendCount; i++) o.s(FRIENDS[i]).c('\n');
    T(T_WRITE_FILE, XS("friends.txt"), IOBUF);
}
static void loadFriends() {
    friendCount = 0;
    if (!R(R_READ_FILE, XS("friends.txt"), IOBUF, sizeof IOBUF)) return;
    char line[64]; int n = 0;
    for (const char* c = IOBUF;; c++) {
        if (*c == '\n' || *c == '\r' || !*c) {
            line[n] = 0;
            int a = 0; while (line[a] == ' ') a++;
            int b = n; while (b > a && line[b - 1] == ' ') line[--b] = 0;
            if (line[a]) friendAdd(line + a);
            n = 0;
            if (!*c) break;
        } else if (n < 63) line[n++] = *c;
    }
}

// ---- GUI lists + actions
static char activeConfig[64];
static void refreshLists() {
    char names[4096];
    if (!R(R_LIST_CONFIGS, nullptr, names, sizeof names)) names[0] = 0;
    splitList(0, names);
    scpy(activeCfg, activeConfig, 64);
    Str o(names, sizeof names);
    for (int i = 0; i < friendCount && i < 60; i++) { if (i) o.c('\n'); o.s(FRIENDS[i]); }
    splitList(1, names);
}
static void cleanName(const char* in, char* out) {
    int n = 0;
    for (; *in && n < 32; in++) { char c = *in; if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') out[n++] = c; }
    out[n] = 0;
}
static void setActive(const char* n) { copy(activeConfig, n, 64); scpy(activeCfg, n, 64); T(T_WRITE_FILE, XS("active-config.txt"), n); }
static void saveMain() { writeConfig(XS("config.json")); dirty = false; }

static void guiAction(const char* verb, const char* argRaw) {
    char arg[40]; cleanName(argRaw, arg);
    char path[96];
    if (eq(verb, "reset")) { resetModule(argRaw); saveMain(); return; }
    if (eq(verb, "panel")) {                                 // "Combat|x|y"
        char cat[32]; int n = 0; const char* p = argRaw;
        while (*p && *p != '|' && n < 31) cat[n++] = *p++;
        cat[n] = 0;
        double x, y;
        if (*p == '|' && parseNum(p + 1, &x)) {
            const char* q = p + 1; while (*q && *q != '|') q++;
            if (*q == '|' && parseNum(q + 1, &y))
                for (int i = 0; i < 6; i++) if (eq(hud::PANELS[i], cat)) { hud::panelX[i].set((float)x); hud::panelY[i].set((float)y); }
        }
        saveMain();
        return;
    }
    if (!arg[0]) return;
    if (eq(verb, "cfg_save")) { Str(path, 96).s(XS("configs/")).s(arg).s(XS(".json")); writeConfig(path); setActive(arg); }
    else if (eq(verb, "cfg_load")) {
        Str(path, 96).s(XS("configs/")).s(arg).s(XS(".json"));
        if (readConfig(path)) { setActive(arg); syncStates(); hud::pushPanels(); saveMain(); }
    }
    else if (eq(verb, "cfg_delete")) { Str(path, 96).s(XS("configs/")).s(arg).s(XS(".json")); T(T_DELETE_FILE, path); if (eq(arg, activeConfig)) setActive(""); }
    else if (eq(verb, "friend_add")) { if (friendAdd(arg)) saveFriends(); }
    else if (eq(verb, "friend_del")) { if (friendRemove(argRaw)) saveFriends(); }
    refreshLists();
}
/** Runs the GUI's queued actions (clicks queue them; this has the JNI env to do file work). */
static void drainActions() {
    while (actHead != actTail) {
        char a[200]; scpy(a, actQ[actHead], 200);
        actHead = (actHead + 1) % 16;
        char* c = a; while (*c && *c != ':') c++;
        if (!*c) continue;
        *c = 0;
        guiAction(a, c + 1);
    }
}

} // namespace z
