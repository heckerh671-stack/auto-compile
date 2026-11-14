// Zalard - Copyright (c) 2026 weksom. All rights reserved. Proprietary; see LICENSE.
package dev.zalard.nativegui;

/**
 * Thin binding to the native decryptor stub (zalard_loader). The stub turns the encrypted payload
 * (ciphertext passed in) into the real native library written at outPath, assembling the plaintext in
 * NATIVE memory so it never exists as a byte[] in the JVM heap (which a heap dump could lift). The stub
 * holds no client logic — it is the one component allowed to ship in the clear. ZalardNative loads the
 * stub and calls this; if the stub is absent or fails, ZalardNative falls back to decrypting in Java.
 */
public final class Loader {
    private Loader() {}

    /** Decrypt {@code enc} (the encrypted payload) with {@code key} and write the plaintext library to
     *  {@code outPath}. Returns true on success. The plaintext is never returned to Java.
     *
     *  {@code key} is the 32-byte payload key fetched at launch from the auth server — it is NOT stored in
     *  the jar, so the stub holds no key of its own; it decrypts only with what it's handed at runtime.
     *
     *  {@code expectHashHex} is the SHA-256 the decrypted library must match (lowercase hex), or "" on an
     *  unsealed dev build to skip the check. When it is non-empty and the decrypted bytes don't match, the
     *  stub wipes them in native memory, writes nothing, and returns false — so the integrity gate runs on
     *  the native path too, with the plaintext never reaching the JVM heap (same place a heap dump lifts it
     *  from on the Java fallback). */
    public static native boolean decryptToFile(byte[] enc, String outPath, String expectHashHex, byte[] key);
}
