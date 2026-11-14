// Zalard native logic: cheap anti-reversing helpers.
//  - XS("...")  compile-time string obfuscation. The characters go through a user-defined literal as
//    template parameters, are stored XOR'd, and are decrypted once (through a volatile read, so the
//    compiler can't fold it back to plaintext) into a per-string static buffer. `strings` on the DLL
//    shows only cipher bytes, never "minecraft:totem_of_undying", "config.json", class names, etc.
//  - a per-release token, baked into the DLL and the jar, that binds the two halves together.
//  - a debugger check that reads the PEB directly (no imports, freestanding-friendly).
#pragma once

// --- string obfuscation lives at global scope so XS works inside and outside namespace z (e.g. JNI_OnLoad)
namespace zobf {
static constexpr unsigned char xk(int i, int n) { return (unsigned char)(0xB7 ^ (i * 41 + n * 101 + 0x5D)); }
template <int...> struct Idx {};
template <int N, int... I> struct Mk : Mk<N - 1, N - 1, I...> {};
template <int... I> struct Mk<0, I...> { using type = Idx<I...>; };

// Decrypted strings are never cached. Each XS use decrypts into the next slot of a small ring that
// is wiped as it comes round again, so a RAM dump catches at most RING recent strings (which keep
// churning), not every string the client ever touched. All XS uses are transient (an id compared,
// a path passed to a call) and consumed well within RING calls, so a live pointer is never clobbered.
static const int RING = 64, SLOT = 112;
static char ring[RING][SLOT];
static unsigned ringAt;
static char* slot() {
    // Advance the index atomically: XS() can be hit from more than one thread (e.g. the render thread and
    // the client-tick thread), and an unsynchronized read-modify-write on ringAt is a data race that could
    // hand two callers the same slot or a torn index. __atomic_fetch_add lowers to a lock xadd — no libc,
    // so it stays freestanding. Each caller still gets a distinct, freshly-wiped slot (the "consumed within
    // RING calls" invariant is unchanged).
    unsigned idx = __atomic_fetch_add(&ringAt, 1u, __ATOMIC_RELAXED) % RING;
    char* b = ring[idx];
    for (int i = 0; i < SLOT; i++) b[i] = 0;                  // wipe the slot we're about to reuse
    return b;
}

template <class, char...> struct Enc;
template <int... I, char... Cs> struct Enc<Idx<I...>, Cs...> {
    static constexpr int N = sizeof...(Cs);
    static constexpr char data[N + 1] = { (char)((unsigned char)Cs ^ xk(I, N + 1))..., (char)(0 ^ xk(N, N + 1)) };
    static const char* dec() {
        char* b = slot();                                    // fresh, wiped slot every time
        const volatile unsigned char* vd = (const volatile unsigned char*)data;   // volatile = no constant-folding back to plaintext
        int n = N < SLOT - 1 ? N : SLOT - 1;
        for (int i = 0; i <= n; i++) b[i] = (char)((unsigned char)vd[i] ^ xk(i, N + 1));
        b[n] = 0;
        return b;
    }
};
template <int... I, char... Cs> constexpr char Enc<Idx<I...>, Cs...>::data[];
} // namespace zobf

template <typename T, T... Cs> constexpr zobf::Enc<typename zobf::Mk<sizeof...(Cs)>::type, Cs...> operator""_sx() { return {}; }

// Every obfuscated literal: the plaintext exists only as template chars at compile time; the binary
// holds the ciphered bytes and a decrypt buffer, never the readable string.
#define XS(lit) ((lit##_sx).dec())

namespace z {

// ---- integrity token (regenerated per release by tools/gen_guard.py; must match dev.zalard.nat.Z)
#include "guard_token.h"
static bool tokenOk(u64 hi, u64 lo) { return hi == ZAL_TOKEN_HI && lo == ZAL_TOKEN_LO; }

// ---- debugger detection, per-OS, import-free (freestanding).
#if defined(MAKO_HARNESS)
static bool debuggerPresent() { return false; }
#elif defined(_WIN32)
// Windows x64: PEB.BeingDebugged + NtGlobalFlag.
static inline unsigned long long peb_base() {
    unsigned long long v;
    __asm__ __volatile__("movq %%gs:0x60, %0" : "=r"(v));
    return v;
}
static bool debuggerPresent() {
    unsigned char* peb = (unsigned char*)peb_base();
    if (!peb) return false;
    if (peb[2]) return true;                                  // PEB.BeingDebugged
    unsigned flags = *(unsigned*)(peb + 0xBC);                // PEB.NtGlobalFlag
    return (flags & 0x70) != 0;                               // heap tails/checks a debugger turns on
}
#elif defined(__linux__) && defined(__x86_64__)
// Linux x86_64: /proc/self/status TracerPid != 0 means a debugger is attached. Raw syscalls
// (open=2, read=0, close=3) keep it freestanding and import-free.
static inline long zsys3(long n, long a, long b, long c) {
    long r;
    __asm__ __volatile__("syscall" : "=a"(r) : "a"(n), "D"(a), "S"(b), "d"(c) : "rcx", "r11", "memory");
    return r;
}
static bool debuggerPresent() {
    char buf[512];
    long fd = zsys3(2, (long)"/proc/self/status", 0, 0);     // open, O_RDONLY
    if (fd < 0) return false;
    long n = zsys3(0, fd, (long)buf, (long)sizeof buf);      // read
    zsys3(3, fd, 0, 0);                                      // close
    for (long i = 0; i + 11 < n; i++)
        if (buf[i]=='T'&&buf[i+1]=='r'&&buf[i+2]=='a'&&buf[i+3]=='c'&&buf[i+4]=='e'
            &&buf[i+5]=='r'&&buf[i+6]=='P'&&buf[i+7]=='i'&&buf[i+8]=='d'&&buf[i+9]==':') {
            long j = i + 10; while (j < n && (buf[j]==' ' || buf[j]=='\t')) j++;
            return j < n && buf[j] >= '1' && buf[j] <= '9';
        }
    return false;
}
#elif defined(__APPLE__)
// macOS: KERN_PROC / KERN_PROC_PID sysctl, test the P_TRACED flag. Raw syscalls keep it import-free in the
// freestanding (-nostdlib) build, matching the Linux path. FAIL-SAFE: any error or doubt returns false
// (never a false "debugged", which would wrongly disable the client for a legit user).
// TODO(mac): compile + test on a Mac — this branch can't be built or run from the Linux dev box. Confirm
// the kinfo_proc p_flag offset (32 on 64-bit) and the syscall ABI before relying on it.
static inline long mac_sc(long n, long a, long b, long c, long d, long e, long f) {
#if defined(__x86_64__)
    long ret;
    register long r10 __asm__("r10") = d, r8 __asm__("r8") = e, r9 __asm__("r9") = f;
    __asm__ __volatile__("syscall" : "=a"(ret)
        : "a"(0x2000000L | n), "D"(a), "S"(b), "d"(c), "r"(r10), "r"(r8), "r"(r9) : "rcx", "r11", "memory");
    return ret;
#elif defined(__aarch64__)
    register long x16 __asm__("x16") = n, x0 __asm__("x0") = a, x1 __asm__("x1") = b,
                  x2 __asm__("x2") = c, x3 __asm__("x3") = d, x4 __asm__("x4") = e, x5 __asm__("x5") = f;
    __asm__ __volatile__("svc #0x80" : "+r"(x0)
        : "r"(x16), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory", "cc");
    return x0;
#else
    (void)n; (void)a; (void)b; (void)c; (void)d; (void)e; (void)f; return -1;
#endif
}
static bool debuggerPresent() {
    long pid = mac_sc(20, 0, 0, 0, 0, 0, 0);                  // getpid
    if (pid <= 0) return false;
    int mib[4] = { 1 /*CTL_KERN*/, 14 /*KERN_PROC*/, 1 /*KERN_PROC_PID*/, (int)pid };
    unsigned char info[700];                                 // >= sizeof(kinfo_proc) on 64-bit
    long size = (long)sizeof(info);
    long r = mac_sc(202, (long)mib, 4, (long)info, (long)&size, 0, 0);   // sysctl
    if (r != 0 || size < 36) return false;                   // fail-safe on any error
    unsigned flag; __builtin_memcpy(&flag, info + 32, 4);    // kinfo_proc.kp_proc.p_flag @ offset 32 (64-bit)
    return (flag & 0x800u) != 0;                             // P_TRACED
}
#else
// Other platforms: anti-debug is a no-op — the VM guard and the encrypted payload still protect.
static bool debuggerPresent() { return false; }
#endif

} // namespace z
