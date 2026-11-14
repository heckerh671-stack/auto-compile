// Zalard native logic: runtime (no CRT): math, random numbers, strings, number formatting.
#pragma once

namespace z {

typedef long long i64;
typedef unsigned long long u64;

static const double PI = 3.14159265358979323846;
static inline double dabs(double v) { return v < 0 ? -v : v; }
static inline double dmin(double a, double b) { return a < b ? a : b; }
static inline double dmax(double a, double b) { return a > b ? a : b; }
static inline double dclamp(double v, double a, double b) { return v < a ? a : v > b ? b : v; }
static inline float fclamp(float v, float a, float b) { return v < a ? a : v > b ? b : v; }
static inline float fabs_(float v) { return v < 0 ? -v : v; }
static inline float fmin_(float a, float b) { return a < b ? a : b; }
static inline float fmax_(float a, float b) { return a > b ? a : b; }
static inline int imin(int a, int b) { return a < b ? a : b; }
static inline int imax(int a, int b) { return a > b ? a : b; }
static inline double dsqrt(double v) { return v <= 0 ? 0 : __builtin_sqrt(v); }
static inline double dfloor(double v) { i64 i = (i64)v; return (double)(v < (double)i ? i - 1 : i); }
static inline double dceil(double v) { double f = dfloor(v); return f < v ? f + 1 : f; }
static inline int ifloor(double v) { return (int)dfloor(v); }
/** Java's Math.round(float): floor(x + 0.5). */
static inline int iround(double v) { return (int)dfloor(v + 0.5); }
static inline double dsignum(double v) { return v > 0 ? 1 : v < 0 ? -1 : 0; }
static inline double dfmod(double a, double b) { return a - b * (double)(i64)(a / b); }
static inline double toRad(double d) { return d * (PI / 180.0); }
static inline double toDeg(double r) { return r * (180.0 / PI); }

static double dsin(double x) {
    x = dfmod(x, 2 * PI);
    if (x > PI) x -= 2 * PI; else if (x < -PI) x += 2 * PI;
    if (x > PI / 2) x = PI - x; else if (x < -PI / 2) x = -PI - x;
    double x2 = x * x, t = x, s = x;
    for (int n = 1; n < 10; n++) { t *= -x2 / ((2 * n) * (2 * n + 1)); s += t; }
    return s;
}
static inline double dcos(double x) { return dsin(x + PI / 2); }

static double datan(double x) {
    bool neg = x < 0; if (neg) x = -x;
    bool inv = x > 1; if (inv) x = 1 / x;
    bool shift = x > 0.2679491924311227;                 // tan(15 deg)
    const double R3 = 1.7320508075688772;
    if (shift) x = (x * R3 - 1) / (R3 + x);
    double x2 = x * x, t = x, s = x;
    for (int n = 1; n < 14; n++) { t *= -x2; s += t / (2 * n + 1); }
    if (shift) s += PI / 6;
    if (inv) s = PI / 2 - s;
    return neg ? -s : s;
}
static double datan2(double y, double x) {
    if (x > 0) return datan(y / x);
    if (x < 0) return y >= 0 ? datan(y / x) + PI : datan(y / x) - PI;
    return y > 0 ? PI / 2 : y < 0 ? -PI / 2 : 0;
}
static inline double dhypot(double a, double b) { return dsqrt(a * a + b * b); }

static double dexp(double x) {
    if (x > 700) x = 700; if (x < -700) return 0;
    const double LN2 = 0.6931471805599453;
    int k = (int)dfloor(x / LN2 + 0.5);
    double r = x - k * LN2, t = 1, s = 1;
    for (int n = 1; n < 18; n++) { t *= r / n; s += t; }
    union { double d; u64 u; } v; v.d = s;
    v.u += (u64)(i64)k << 52;
    return v.d;
}
static double dlog(double x) {
    if (x <= 0) return -1e300;
    union { double d; u64 u; } v; v.d = x;
    int e = (int)((v.u >> 52) & 0x7FF) - 1023;
    v.u = (v.u & 0x000FFFFFFFFFFFFFull) | 0x3FF0000000000000ull;   // mantissa in [1,2)
    double m = v.d;
    if (m > 1.4142135623730951) { m *= 0.5; e++; }
    double y = (m - 1) / (m + 1), y2 = y * y, t = y, s = y;
    for (int n = 1; n < 20; n++) { t *= y2; s += t / (2 * n + 1); }
    return 2 * s + e * 0.6931471805599453;
}
static inline double dpow(double a, double b) { return a <= 0 ? 0 : dexp(b * dlog(a)); }

/** Minecraft's Mth.wrapDegrees: into [-180, 180). */
static inline float wrapDeg(float v) {
    float r = (float)dfmod(v, 360.0);
    if (r >= 180.f) r -= 360.f;
    if (r < -180.f) r += 360.f;
    return r;
}

// ---------------------------------------------------------------- random numbers (xorshift64*)
struct Rng {
    u64 s = 0x9E3779B97F4A7C15ull;
    bool haveG = false; double g2 = 0;
    void seed(u64 v) { s = v ? v * 0x9E3779B97F4A7C15ull | 1 : 0x2545F4914F6CDD1Dull; for (int i = 0; i < 8; i++) next(); }
    u64 next() { s ^= s >> 12; s ^= s << 25; s ^= s >> 27; return s * 0x2545F4914F6CDD1Dull; }
    double nextDouble() { return (double)(next() >> 11) * (1.0 / 9007199254740992.0); }
    float nextFloat() { return (float)nextDouble(); }
    int nextInt(int n) { return n <= 0 ? 0 : (int)(nextDouble() * n); }
    bool nextBool() { return (next() >> 63) != 0; }
    double gaussian() {
        if (haveG) { haveG = false; return g2; }
        double u, v, q;
        do { u = nextDouble() * 2 - 1; v = nextDouble() * 2 - 1; q = u * u + v * v; } while (q >= 1 || q == 0);
        double m = dsqrt(-2 * dlog(q) / q);
        g2 = v * m; haveG = true;
        return u * m;
    }
};
static Rng RNG;

// ---------------------------------------------------------------- strings
static inline int len(const char* s) { int n = 0; while (s && s[n]) n++; return n; }
static inline bool eq(const char* a, const char* b) {
    if (!a || !b) return a == b;
    while (*a && *a == *b) { a++; b++; }
    return *a == *b;
}
static inline char lc_(char c) { return (c >= 'A' && c <= 'Z') ? (char)(c + 32) : c; }
static bool eqi(const char* a, const char* b) {
    if (!a || !b) return false;
    while (*a && lc_(*a) == lc_(*b)) { a++; b++; }
    return lc_(*a) == lc_(*b);
}
static bool startsWith(const char* s, const char* p) { while (*p) if (*s++ != *p++) return false; return true; }
static bool endsWith(const char* s, const char* suf) { int a = len(s), b = len(suf); return a >= b && eq(s + a - b, suf); }
static bool contains(const char* s, const char* sub) {
    int n = len(sub);
    for (int i = 0; s[i]; i++) { int j = 0; while (j < n && s[i + j] == sub[j]) j++; if (j == n) return true; }
    return n == 0;
}
static void copy(char* d, const char* s, int cap) { int i = 0; for (; s && s[i] && i < cap - 1; i++) d[i] = s[i]; d[i] = 0; }

/** Appending writer into a fixed buffer. */
struct Str {
    char* b; int cap; int n;
    Str(char* buf, int c) : b(buf), cap(c), n(0) { b[0] = 0; }
    Str& s(const char* t) { while (t && *t && n < cap - 1) b[n++] = *t++; b[n] = 0; return *this; }
    Str& c(char ch) { if (n < cap - 1) { b[n++] = ch; b[n] = 0; } return *this; }
    Str& i(i64 v) {
        char t[24]; int k = 0; bool neg = v < 0; u64 u = neg ? (u64)(-v) : (u64)v;
        do { t[k++] = (char)('0' + u % 10); u /= 10; } while (u);
        if (neg) c('-');
        while (k) c(t[--k]);
        return *this;
    }
    /** Fixed decimals, like String.format("%.Nf"). */
    Str& f(double v, int dec) {
        if (v != v) return s("NaN");
        if (v < 0) { c('-'); v = -v; }
        double scale = 1; for (int k = 0; k < dec; k++) scale *= 10;
        u64 whole = (u64)(v * scale + 0.5);
        i((i64)(whole / (u64)scale));
        if (dec > 0) {
            c('.');
            u64 frac = whole % (u64)scale;
            char t[24]; for (int k = dec - 1; k >= 0; k--) { t[k] = (char)('0' + frac % 10); frac /= 10; }
            for (int k = 0; k < dec; k++) c(t[k]);
        }
        return *this;
    }
    /** Like Java's Float.toString for config values: shortest form that round-trips well enough. */
    Str& g(double v) {
        if (v == (double)(i64)v && dabs(v) < 1e15) { i((i64)v); return s(".0"); }
        // up to 6 decimals, trailing zeros trimmed
        int start = n; f(v, 6);
        while (n > start + 1 && b[n - 1] == '0' && b[n - 2] != '.') b[--n] = 0;
        return *this;
    }
};

/** Parses a number from s (leading spaces ok). Returns false if there isn't one. */
static bool parseNum(const char* s, double* out, const char** end = nullptr) {
    while (*s == ' ' || *s == '\t') s++;
    bool neg = false;
    if (*s == '-' || *s == '+') { neg = *s == '-'; s++; }
    if (!((*s >= '0' && *s <= '9') || *s == '.')) return false;
    double v = 0;
    while (*s >= '0' && *s <= '9') v = v * 10 + (*s++ - '0');
    if (*s == '.') { s++; double sc = 0.1; while (*s >= '0' && *s <= '9') { v += (*s++ - '0') * sc; sc *= 0.1; } }
    if (*s == 'e' || *s == 'E') {
        s++; bool en = false; if (*s == '-' || *s == '+') { en = *s == '-'; s++; }
        int e = 0; while (*s >= '0' && *s <= '9') e = e * 10 + (*s++ - '0');
        double m = 1; for (int k = 0; k < e; k++) m *= 10;
        v = en ? v / m : v * m;
    }
    *out = neg ? -v : v;
    if (end) *end = s;
    return true;
}

struct Vec3 {
    double x, y, z;
    Vec3 operator+(const Vec3& o) const { return { x + o.x, y + o.y, z + o.z }; }
    Vec3 operator-(const Vec3& o) const { return { x - o.x, y - o.y, z - o.z }; }
    double dist(const Vec3& o) const { return dsqrt(distSq(o)); }
    double distSq(const Vec3& o) const { double a = x - o.x, b = y - o.y, c = z - o.z; return a * a + b * b + c * c; }
};

struct Uuid { u64 hi, lo; bool operator==(const Uuid& o) const { return hi == o.hi && lo == o.lo; } bool null() const { return !hi && !lo; } };

} // namespace z
