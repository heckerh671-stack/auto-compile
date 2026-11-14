// Zalard native logic: the JNI bridge to Minecraft (dev.zalard.adapter.Bridge) and the game state
// snapshots it fills (self block, entities, inventory).
#pragma once
#include "ops.h"

namespace z {

static JNIEnv* E;                 // env of the thread that called into us (always the client thread)
static jclass BR;
static jmethodID BR_D, BR_T, BR_R;
static bool bridgeOk;

static double OUTB[512];
#define MAX_ENTS 512
struct EntRec {
    int id, flags; u64 hi, lo;
    double x, y, z, px, py, pz, ex, ey, ez;
    float yaw, pitch, health, maxHealth, armor; int hurt;
    double box[6], rbox[6];
    char name[40];
};
static_assert(sizeof(EntRec) == 256, "entity record layout");
struct InvRec { int count, flags, damage, maxDamage, ench[6]; char id[56]; };
static_assert(sizeof(InvRec) == 96, "inventory record layout");
enum { EN_LUNGE, EN_DENSITY, EN_BREACH, EN_WIND_BURST, EN_FLAME };
enum { EF_PLAYER = 1, EF_DEAD = 2, EF_INVIS = 4, EF_GROUND = 8, EF_SHIELD = 16, EF_SELF = 32 };
enum { BF_AIR = 1, BF_REPLACEABLE = 2, BF_SOLID = 4, BF_LIQUID = 8, BF_WATER = 16 };
static EntRec ENTB[MAX_ENTS];
static InvRec INVB[37];

static void bridgeSetup(JNIEnv* env) {
    E = env;
    jclass c = env->FindClass(XS("dev/zalard/adapter/Bridge"));
    if (!c) { env->ExceptionClear(); return; }
    BR = (jclass)env->NewGlobalRef(c);
    BR_D = env->GetStaticMethodID(BR, XS("d"), XS("(IDDDDDDD)D"));
    BR_T = env->GetStaticMethodID(BR, XS("t"), XS("(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)D"));
    BR_R = env->GetStaticMethodID(BR, XS("r"), XS("(ILjava/lang/String;)Ljava/lang/String;"));
    jmethodID setup = env->GetStaticMethodID(BR, XS("setup"), XS("(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)V"));
    if (!BR_D || !BR_T || !BR_R || !setup) { env->ExceptionClear(); return; }
    jobject o = env->NewDirectByteBuffer(OUTB, sizeof OUTB);
    jobject en = env->NewDirectByteBuffer(ENTB, sizeof ENTB);
    jobject iv = env->NewDirectByteBuffer(INVB, sizeof INVB);
    env->CallStaticVoidMethod(BR, setup, o, en, iv);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return; }
    bridgeOk = true;
}

static double D(int op, double a = 0, double b = 0, double c = 0, double d = 0, double e = 0, double f = 0, double g = 0) {
    if (!bridgeOk || !E) return -1;
    double r = E->CallStaticDoubleMethod(BR, BR_D, (jint)op, a, b, c, d, e, f, g);
    if (E->ExceptionCheck()) { E->ExceptionClear(); return -1; }
    return r;
}
static jstring jstr_(const char* s) { return s ? E->NewStringUTF(s) : nullptr; }
static double T(int op, const char* a, const char* b = nullptr, const char* c = nullptr) {
    if (!bridgeOk || !E) return -1;
    jstring ja = jstr_(a), jb = jstr_(b), jc = jstr_(c);
    double r = E->CallStaticDoubleMethod(BR, BR_T, (jint)op, ja, jb, jc);
    if (E->ExceptionCheck()) { E->ExceptionClear(); r = -1; }
    if (ja) E->DeleteLocalRef(ja);
    if (jb) E->DeleteLocalRef(jb);
    if (jc) E->DeleteLocalRef(jc);
    return r;
}
/** String op: copies the answer into out (cap bytes). False if Java returned null. */
static bool R(int op, const char* a, char* out, int cap) {
    out[0] = 0;
    if (!bridgeOk || !E) return false;
    jstring ja = jstr_(a);
    jstring r = (jstring)E->CallStaticObjectMethod(BR, BR_R, (jint)op, ja);
    if (E->ExceptionCheck()) { E->ExceptionClear(); r = nullptr; }
    if (ja) E->DeleteLocalRef(ja);
    if (!r) return false;
    const char* c = E->GetStringUTFChars(r, nullptr);
    copy(out, c, cap);
    E->ReleaseStringUTFChars(r, c);
    E->DeleteLocalRef(r);
    return true;
}
static void chat(const char* m) { T(T_CHAT, m); }
static void logLine(const char* m) { T(T_LOG, m); }

// ---------------------------------------------------------------- self state
static double SF[SF_COUNT];
static bool inGame;
static i64 NOW;              // wall clock ms of the current call
static i64 NOWNS;            // System.nanoTime of the current frame call
static i64 TICKS;            // game ticks since start (counts in a world)
static int phase;            // bumps on every entry, so snapshots refresh once per call

static bool snapSelf() {
    inGame = D(D_SNAP_SELF) == 1;
    if (inGame) for (int i = 0; i < SF_COUNT; i++) SF[i] = OUTB[i];
    return inGame;
}
static inline bool sb(int f) { return SF[f] != 0; }
static inline Vec3 selfPos() { return { SF[SF_X], SF[SF_Y], SF[SF_Z] }; }
static inline Vec3 selfEye() { return { SF[SF_EX], SF[SF_EY], SF[SF_EZ] }; }
static inline float selfYaw() { return (float)SF[SF_YAW]; }
static inline float selfPitch() { return (float)SF[SF_PITCH]; }
static inline bool onGround() { return sb(SF_GROUND); }
static inline double health() { return SF[SF_HEALTH]; }
static inline int selSlot() { return (int)SF[SF_SLOT]; }
static inline Vec3 selfVel() { return { SF[SF_VX], SF[SF_VY], SF[SF_VZ] }; }
static inline float fallDist() { return (float)SF[SF_FALL]; }
static inline float strength() { return (float)SF[SF_STRENGTH]; }
static inline bool usingItem() { return sb(SF_USING); }
static inline bool screenOpen() { return sb(SF_SCREEN); }
static inline bool hasSilent() { return sb(SF_SILENT); }
static inline float silentYaw() { return (float)SF[SF_SYAW]; }
static inline float silentPitch() { return (float)SF[SF_SPITCH]; }
static inline int pendAtk() { return (int)SF[SF_PEND_ATK]; }
static inline int pendUse() { return (int)SF[SF_PEND_USE]; }
static inline bool lookSteered() { return sb(SF_LOOK_STEERED); }
static inline float sensitivity() { return (float)SF[SF_SENS]; }

// ---- actions (the local copy is kept in step so later code this tick sees the change)
static void clickAttack() { D(D_CLICK_ATTACK); SF[SF_PEND_ATK] += 1; }
static void consumeAttack() { D(D_CONSUME_ATTACK); if (SF[SF_PEND_ATK] > 0) SF[SF_PEND_ATK] -= 1; }
static void clickUse() { D(D_CLICK_USE); SF[SF_PEND_USE] += 1; }
static int consumeUse() { int n = (int)D(D_CONSUME_USE); SF[SF_PEND_USE] = 0; return n; }
static void pressHotbar(int s) { if (s >= 0 && s < 9) D(D_PRESS_HOTBAR, s); }
static void pressSwapOffhand() { D(D_PRESS_SWAP_OFFHAND); }
static void swing(bool main = true) { D(D_SWING, main ? 1 : 0); }
static void setSilent(float yaw, float pitch) { D(D_SET_SILENT, yaw, pitch); SF[SF_SILENT] = 1; SF[SF_SYAW] = yaw; SF[SF_SPITCH] = pitch; }
static void clearSilent() { D(D_CLEAR_SILENT); SF[SF_SILENT] = 0; }
static void setRot(float yaw, float pitch) { D(D_SET_ROT, yaw, pitch); SF[SF_YAW] = yaw; SF[SF_PITCH] = pitch; }
static void setVel(double x, double y, double zz) { D(D_SET_VEL, x, y, zz); SF[SF_VX] = x; SF[SF_VY] = y; SF[SF_VZ] = zz; }
static void jump() {
    D(D_JUMP);
    if (D(D_SELF_VEL) == 1) { SF[SF_VX] = OUTB[0]; SF[SF_VY] = OUTB[1]; SF[SF_VZ] = OUTB[2]; }
}
static void setUseKey(bool d) { D(D_SET_USE_KEY, d ? 1 : 0); }
static void restoreUseKey() { D(D_RESTORE_USE_KEY); }
static bool keyDown(int k) { return D(D_KEY_DOWN, k) == 1; }
static int blockFlags(int x, int y, int zz) { int f = (int)D(D_BLOCK_FLAGS, x, y, zz); return f < 0 ? 0 : f; }
static bool blockId(int x, int y, int zz, char* out, int cap) {
    char q[48]; Str s(q, 48); s.i(x).c(' ').i(y).c(' ').i(zz);
    return R(R_BLOCK_ID_AT, q, out, cap);
}
/** {x, y, z, face, hx, hy, hz} of the block under the crosshair. */
static bool crosshairBlock(double* h) {
    if (D(D_CROSSHAIR_BLOCK) != 7) return false;
    for (int i = 0; i < 7; i++) h[i] = OUTB[i];
    return true;
}
static bool raycastBlock(float yaw, float pitch, double reach, double* h) {
    if (D(D_RAYCAST_BLOCK, yaw, pitch, reach) != 7) return false;
    for (int i = 0; i < 7; i++) h[i] = OUTB[i];
    return true;
}
enum Dir { UP, DOWN, NORTH, SOUTH, EAST, WEST };
static void useItemOn(int x, int y, int zz, int face, double hx, double hy, double hz, bool main) {
    D(D_USE_ITEM_ON, x, y, zz, face * 2 + (main ? 1 : 0), hx, hy, hz);
}

// ---------------------------------------------------------------- entities
static int entCount, entPhase = -1;
static void snapEnts() {
    if (entPhase == phase) return;
    entPhase = phase;
    int n = (int)D(D_SNAP_ENTS, 300);
    entCount = n < 0 ? 0 : n > MAX_ENTS ? MAX_ENTS : n;
}
struct Ent {
    const EntRec* r = nullptr;
    bool ok() const { return r != nullptr; }
    int id() const { return r->id; }
    Uuid uuid() const { return { r->hi, r->lo }; }
    bool player() const { return r->flags & EF_PLAYER; }
    bool dead() const { return r->flags & EF_DEAD; }
    bool invisible() const { return r->flags & EF_INVIS; }
    bool ground() const { return r->flags & EF_GROUND; }
    bool shield() const { return r->flags & EF_SHIELD; }
    bool self() const { return r->flags & EF_SELF; }
    Vec3 pos() const { return { r->x, r->y, r->z }; }
    Vec3 prev() const { return { r->px, r->py, r->pz }; }
    Vec3 eye() const { return { r->ex, r->ey, r->ez }; }
    float yaw() const { return r->yaw; }
    float health() const { return r->health; }
    float maxHealth() const { return r->maxHealth; }
    float armor() const { return r->armor; }
    const double* box() const { return r->box; }
    const double* rbox() const { return r->rbox; }
    const char* name() const { return r->name; }
    /** Distance to the nearest point of the hitbox (how vanilla measures reach). */
    double distSq(const Vec3& p) const {
        const double* b = r->box;
        double cx = dclamp(p.x, b[0], b[3]), cy = dclamp(p.y, b[1], b[4]), cz = dclamp(p.z, b[2], b[5]);
        double dx = p.x - cx, dy = p.y - cy, dzz = p.z - cz;
        return dx * dx + dy * dy + dzz * dzz;
    }
};
static Ent entAt(int i) { snapEnts(); return { i >= 0 && i < entCount ? &ENTB[i] : nullptr }; }
static Ent entById(int id) {
    if (id < 0) return { nullptr };
    snapEnts();
    for (int i = 0; i < entCount; i++) if (ENTB[i].id == id) return { &ENTB[i] };
    return { nullptr };
}
static Ent entByUuid(const Uuid& u) {
    if (u.null()) return { nullptr };
    snapEnts();
    for (int i = 0; i < entCount; i++) if (ENTB[i].hi == u.hi && ENTB[i].lo == u.lo) return { &ENTB[i] };
    return { nullptr };
}
static Ent crosshairEnt(double reach) { return entById((int)D(D_CROSSHAIR_ENT, reach)); }
static Ent raycastEnt(float yaw, float pitch, double reach) { return entById((int)D(D_RAYCAST_ENT, yaw, pitch, reach)); }
static bool lineOfSight(const Vec3& a, const Vec3& b) { return D(D_LOS, a.x, a.y, a.z, b.x, b.y, b.z) == 1; }

// ---------------------------------------------------------------- inventory (0-8 hotbar, 9-35 main, 36 offhand)
static int invPhase = -1;
static void snapInv() { if (invPhase == phase) return; invPhase = phase; D(D_SNAP_INV); }
static const InvRec& item(int slot) { snapInv(); return INVB[slot < 0 || slot > 36 ? 36 : slot]; }
static bool isEmpty(const InvRec& it) { return (it.flags & 1) != 0; }
static bool isItem(int slot, const char* id) { const InvRec& it = item(slot); return !isEmpty(it) && eq(it.id, id); }
static const InvRec& mainHand() { return item(selSlot()); }
static const InvRec& offHand() { return item(36); }
static int findHotbar(const char* id) { for (int i = 0; i < 9; i++) if (isItem(i, id)) return i; return -1; }
static int findHotbarSuffix(const char* suf) {
    for (int i = 0; i < 9; i++) { const InvRec& it = item(i); if (!isEmpty(it) && endsWith(it.id, suf)) return i; }
    return -1;
}
static int findInventory(const char* id) { for (int i = 0; i < 36; i++) if (isItem(i, id)) return i; return -1; }

/** New entry from Java: wall clock + fresh snapshots on demand. */
static void enter(JNIEnv* env, i64 nowMs) { E = env; NOW = nowMs; phase++; }

} // namespace z
