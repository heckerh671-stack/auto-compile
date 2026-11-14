// Zalard decryptor stub (zalard_loader).  NON-SENSITIVE: it contains no client logic and no key — only
// the code that turns the encrypted payload (/natives/zalard_gui.bin) into the real native library. It is
// allowed to sit in the jar in the clear, because reversing it yields only the decrypt machinery; the key
// is passed in from Java at runtime (fetched from the auth server) and the jar ships only ciphertext.
//
// Why it exists: the plaintext library is assembled in NATIVE memory and written straight to the
// (RAM-backed, immediately-unlinked) path Java System.loads — so the decrypted library never exists as a
// byte[] in the JVM heap (which a heap dump could lift).
//
// Crypto: libsodium XChaCha20-Poly1305 (authenticated), matching tools/zcrypto.py / tools/seal.py.
// Payload format:  magic "ZLD1" (4) | version u8 (=1) | nonce (24) | ciphertext+Poly1305 tag (len+16).
// The AEAD tag authenticates the payload BEFORE any plaintext is accepted; a tampered .bin, a wrong key,
// or a malformed blob all fail decryption and nothing is written. No custom crypto is implemented here.
//
// Build/link: needs libsodium. Release builds should STATIC-link it (libsodium.a) so end users need
// nothing installed — see tools/build_dll.sh.
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

// libsodium (declared directly so the stub builds without sodium.h present on the dev box; the ABI is
// stable C). Link against libsodium.
extern "C" {
int sodium_init(void);
int crypto_aead_xchacha20poly1305_ietf_decrypt(
    unsigned char* m, unsigned long long* mlen_p, unsigned char* nsec,
    const unsigned char* c, unsigned long long clen,
    const unsigned char* ad, unsigned long long adlen,
    const unsigned char* npub, const unsigned char* k);
int crypto_hash_sha256(unsigned char* out, const unsigned char* in, unsigned long long inlen);
}

static const int ZL_NONCE = 24, ZL_ABYTES = 16, ZL_HEADER = 4 /*magic*/ + 1 /*version*/ + 24 /*nonce*/;

// libsodium must be initialized before any crypto call. sodium_init() returns 0 (ok), 1 (already done),
// or -1 (failure). Cache it; treat <0 as "crypto unavailable" so the client fails closed rather than
// calling into an uninitialized library.
static int g_sodium = -2;
static bool sodiumReady() {
    if (g_sodium == -2) g_sodium = sodium_init();
    return g_sodium >= 0;
}

// File write via the OS (not <stdio.h>): the payload build ships a native/win/stdio.h shim that shadows
// the real header under -Iwin, so the stub avoids <stdio.h> and writes the decrypted library directly.
#if defined(_WIN32)
#include <windows.h>
static bool zlWrite(const char* path, const unsigned char* data, size_t n) {
    // `path` is UTF-8 from JNI (GetStringUTFChars). Convert to UTF-16 and use CreateFileW so a temp dir
    // with non-ASCII characters (e.g. a user folder in another script) works — CreateFileA would mangle it
    // through the ANSI code page. (JNI "modified UTF-8" equals UTF-8 for the BMP characters a path uses.)
    int wlen = MultiByteToWideChar(CP_UTF8, 0, path, -1, nullptr, 0);
    if (wlen <= 0) return false;
    wchar_t* wpath = (wchar_t*)malloc((size_t)wlen * sizeof(wchar_t));
    if (!wpath) return false;
    if (MultiByteToWideChar(CP_UTF8, 0, path, -1, wpath, wlen) <= 0) { free(wpath); return false; }
    HANDLE h = CreateFileW(wpath, GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    free(wpath);
    if (h == INVALID_HANDLE_VALUE) return false;
    DWORD wrote = 0; BOOL ok = WriteFile(h, data, (DWORD)n, &wrote, nullptr);
    CloseHandle(h);
    return ok && wrote == (DWORD)n;
}
#define ZL_EXPORT extern "C" __declspec(dllexport)
#else
#include <fcntl.h>
#include <unistd.h>
static bool zlWrite(const char* path, const unsigned char* data, size_t n) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) return false;
    size_t off = 0;
    while (off < n) { ssize_t w = write(fd, data + off, n - off); if (w <= 0) { close(fd); return false; } off += (size_t)w; }
    close(fd);
    return true;
}
#define ZL_EXPORT extern "C" __attribute__((visibility("default")))
#endif

namespace {
static int unhex(const char* hex, unsigned char* out, int cap) {
    int n = 0; for (const char* p = hex; p[0] && p[1] && n < cap; p += 2) {
        auto v = [](char c)->int { return c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:c>='A'&&c<='F'?c-'A'+10:0; };
        out[n++] = (unsigned char)((v(p[0])<<4) | v(p[1]));
    }
    return n;
}
// Decrypt+authenticate a ZLD1 blob with libsodium. Returns the plaintext length, or -1 on any failure
// (bad header, failed AEAD tag = tamper/wrong key). `out` must have room for clen - ABYTES bytes.
static long aeadOpen(const unsigned char* blob, size_t n, const unsigned char key[32], unsigned char* out) {
    if (n < (size_t)(ZL_HEADER + ZL_ABYTES)) return -1;
    if (memcmp(blob, "ZLD1", 4) != 0 || blob[4] != 1) return -1;
    const unsigned char* nonce = blob + 5;
    const unsigned char* ct = blob + ZL_HEADER;
    unsigned long long clen = (unsigned long long)(n - ZL_HEADER);
    unsigned long long mlen = 0;
    if (crypto_aead_xchacha20poly1305_ietf_decrypt(out, &mlen, nullptr, ct, clen, nullptr, 0, nonce, key) != 0)
        return -1;    // authentication failed — do not accept any plaintext
    return (long)mlen;
}
} // namespace

// Decrypt the ciphertext in native memory and write the plaintext library to outPath. Returns true on
// success. The plaintext is never handed back to Java — only written to the path Java will System.load.
ZL_EXPORT jboolean JNICALL Java_dev_zalard_nativegui_Loader_decryptToFile(JNIEnv* env, jclass, jbyteArray enc, jstring outPath, jstring expectHash, jbyteArray keyArr) {
    if (!sodiumReady()) return JNI_FALSE;               // libsodium init failed — fail closed
    if (!enc || !outPath || !keyArr) return JNI_FALSE;
    if (env->GetArrayLength(keyArr) != 32) return JNI_FALSE;
    jsize n = env->GetArrayLength(enc);
    if (n <= ZL_HEADER + ZL_ABYTES) return JNI_FALSE;
    unsigned char* buf = (unsigned char*)malloc((size_t)n);
    if (!buf) return JNI_FALSE;
    env->GetByteArrayRegion(enc, 0, n, (jbyte*)buf);

    unsigned char key[32];
    env->GetByteArrayRegion(keyArr, 0, 32, (jbyte*)key);

    unsigned char* plain = (unsigned char*)malloc((size_t)n);   // >= plaintext length
    if (!plain) { memset(key, 0, sizeof key); memset(buf, 0, (size_t)n); free(buf); return JNI_FALSE; }
    long pn = aeadOpen(buf, (size_t)n, key, plain);             // AEAD verify + decrypt
    memset(key, 0, sizeof key);
    memset(buf, 0, (size_t)n);
    free(buf);
    if (pn < 0) { free(plain); return JNI_FALSE; }              // tamper / wrong key / malformed

    // Defense-in-depth: optional per-OS plaintext hash pin (the AEAD tag is the primary authentication).
    if (expectHash) {
        const char* eh = env->GetStringUTFChars(expectHash, nullptr);
        if (eh) {
            bool bad = false;
            if (eh[0] != '\0') {
                unsigned char want[32]; int wn = unhex(eh, want, 32);
                unsigned char got[32]; crypto_hash_sha256(got, plain, (unsigned long long)pn);
                bad = (wn != 32) || (memcmp(want, got, 32) != 0);
                memset(want, 0, sizeof want); memset(got, 0, sizeof got);
            }
            env->ReleaseStringUTFChars(expectHash, eh);
            if (bad) { memset(plain, 0, (size_t)pn); free(plain); return JNI_FALSE; }
        }
    }

    const char* path = env->GetStringUTFChars(outPath, nullptr);
    jboolean ok = JNI_FALSE;
    if (path) {
        ok = zlWrite(path, plain, (size_t)pn) ? JNI_TRUE : JNI_FALSE;
        env->ReleaseStringUTFChars(outPath, path);
    }
    memset(plain, 0, (size_t)pn);     // wipe plaintext from native heap
    free(plain);
    return ok;
}

ZL_EXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) { sodiumReady(); return JNI_VERSION_1_6; }

#ifdef ZL_SELFTEST
#include <stdio.h>
// Build: clang++ -DZL_SELFTEST ... zalard_loader.cpp -lsodium -o selftest
// Run:   ./selftest <64-hex-key> <sealed.bin> <out.bin>   — decrypts a tools/seal.py blob with libsodium
// and writes the plaintext, so the build can check native-decrypt == the original library.
int main(int argc, char** argv) {
    if (!sodiumReady()) { printf("sodium init failed\n"); return 3; }
    unsigned char key[32];
    if (argc < 4 || unhex(argv[1], key, 32) != 32) { printf("usage: selftest <64-hex-key> <sealed.bin> <out.bin>\n"); return 1; }
    FILE* f = fopen(argv[2], "rb"); if (!f) { printf("open in\n"); return 1; }
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    unsigned char* blob = (unsigned char*)malloc(n); if (fread(blob, 1, n, f) != (size_t)n) { return 1; } fclose(f);
    unsigned char* out = (unsigned char*)malloc(n);
    long pn = aeadOpen(blob, (size_t)n, key, out);
    if (pn < 0) { printf("DECRYPT FAILED (auth)\n"); return 2; }
    FILE* g = fopen(argv[3], "wb"); fwrite(out, 1, pn, g); fclose(g);
    printf("ok %ld bytes\n", pn);
    return 0;
}
#endif
