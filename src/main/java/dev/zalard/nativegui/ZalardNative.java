// Zalard - Copyright (c) 2026 weksom. All rights reserved. Proprietary; see LICENSE.
package dev.zalard.nativegui;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Bridge to zalard_gui.dll. The DLL owns the GUI: layout, widgets, input,
 * animation and drawing. Java modules register their settings with it at
 * startup (NativeBinding) and read the values back.
 */
public final class ZalardNative {
    private ZalardNative() {}

    private static boolean loaded;

    // SHA-256 of the DECRYPTED zalard_gui.dll shipped with this build (filled by tools/seal.py). Empty =
    // check skipped (dev builds). Binds this jar to this DLL.
    private static final String DLL_SHA256 = "41341f0b9c57bf62a83afc8d75e68661b633aa9b3e7cbc2b5d9241b3c62b1430";
    private static final String SO_SHA256 = "";       // linux  (filled by tools/seal.py)
    private static final String DYLIB_SHA256 = "";    // macOS  (filled by tools/seal.py)
    /** Expected SHA-256 of the decrypted library for THIS OS, or "" on a dev/unsealed build. */
    private static String expectedHash() { return isWindows() ? DLL_SHA256 : isMac() ? DYLIB_SHA256 : SO_SHA256; }
    // The payload key is NOT in the jar. The jar ships the DLL as /natives/zalard_gui.bin (ciphered); the
    // key is fetched at launch from the auth server (dev.zalard.auth.Auth) after a free login, so unzipping
    // the jar hands a leaker only ciphertext — there is no key, split or otherwise, anywhere in it. A valid,
    // non-banned account on this machine gets the key; without it the client simply can't decrypt and stays
    // disabled. For local dev without the server, pass -Dzalard.devkey=<64 hex> to supply the key directly.
    private static byte[] resolveKey() throws Exception {
        String devHex = System.getProperty("zalard.devkey");
        if (devHex != null && !devHex.isEmpty()) return unhex(devHex.trim());
        return dev.zalard.auth.Auth.fetchKey();     // throws if login fails → client stays disabled
    }

    private static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    // Refuse to run under an instrumentation agent (the classic "attach and dump every loaded class").
    // Pair with -XX:+DisableAttachMechanism in the launcher to block external jcmd/jmap attach too.
    private static void antiAgent() {
        try {
            for (String a : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                String s = a.toLowerCase();
                if (s.startsWith("-javaagent") || s.startsWith("-agentlib") || s.startsWith("-agentpath"))
                    throw new SecurityException("instrumentation agent present");
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Throwable ignored) {}
    }

    public static synchronized void load() {
        if (loaded) return;
        antiAgent();
        try {
            boolean[] enc = { false };
            byte[] cipher = readCipher(enc);                  // raw payload: encrypted .bin (release) or plain lib (dev)
            // RAM-backed scratch, written then immediately unlinked (Linux/macOS) so the library on disk
            // lives for milliseconds, not the whole session; Windows keeps it locked until exit.
            Path dir = loadDir();
            Files.createDirectories(dir);
            Path dll = dir.resolve(Long.toHexString(System.nanoTime() ^ ((long) java.util.Arrays.hashCode(cipher) << 21)) + ".tmp");

            String want = expectedHash();
            if (enc[0]) {
                // ENCRYPTED PAYLOAD (release): decrypt ONLY through the native stub (libsodium
                // XChaCha20-Poly1305). The AEAD tag authenticates the payload before any plaintext is
                // accepted, and the plaintext is assembled in native memory — never a byte[] on the JVM heap
                // (which a heap dump could lift). Java can't do XChaCha and MUST NOT see the plaintext, so
                // there is no in-heap fallback: if the stub is missing or decryption fails (tamper, wrong
                // key, missing libsodium), the client fails closed rather than downgrading.
                byte[] key = resolveKey();      // from the auth server (or -Dzalard.devkey); no key ⇒ no start
                boolean wrote = false;
                if (loadStub()) {
                    try { wrote = Loader.decryptToFile(cipher, dll.toAbsolutePath().toString(), want, key); }
                    catch (Throwable stubFail) { wrote = false; }
                }
                java.util.Arrays.fill(cipher, (byte) 0);
                java.util.Arrays.fill(key, (byte) 0);
                if (!wrote)
                    throw new SecurityException("zalard_gui decrypt failed (stub unavailable, bad key, or tampered payload)");
            } else {
                // DEV build: a plain, unencrypted library — just write it. No key, no decryption.
                Files.write(dll, cipher);
                java.util.Arrays.fill(cipher, (byte) 0);
            }
            System.load(dll.toAbsolutePath().toString());
            try {
                Files.delete(dll);                            // Linux/macOS: gone from disk now, still mapped
                Files.deleteIfExists(dir);
            } catch (Exception locked) {
                dll.toFile().deleteOnExit();                  // Windows: locked until exit
                dir.toFile().deleteOnExit();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load zalard_gui", e);
        }
        init();
        loaded = true;
    }

    /** RAM-backed scratch dir when the OS offers one (Linux tmpfs at /dev/shm), else the normal temp dir.
     *  Combined with the immediate unlink in load(), the decrypted library never touches a persistent disk
     *  on Linux — it lives in RAM for the brief moment between write and unlink. */
    private static Path loadDir() {
        String rnd = ".jvm-" + Long.toHexString(System.nanoTime());
        if (!isWindows() && !isMac()) {
            try {
                Path shm = Path.of("/dev/shm");
                if (Files.isDirectory(shm) && Files.isWritable(shm)) return shm.resolve(rnd);
            } catch (Exception ignored) {}
        }
        return Path.of(System.getProperty("java.io.tmpdir"), rnd);
    }

    private static String osName() { return System.getProperty("os.name", "").toLowerCase(); }
    private static boolean isWindows() { return osName().contains("win"); }
    private static boolean isMac() { return osName().contains("mac") || osName().contains("darwin"); }
    /** Native library filename for this OS. */
    private static String libName() { return isWindows() ? "zalard_gui.dll" : isMac() ? "zalard_gui.dylib" : "zalard_gui.so"; }
    /** Encrypted payload resource for this OS. Windows keeps the original name for back-compat. */
    private static String binResource() { return isWindows() ? "/natives/zalard_gui.bin" : "/natives/" + libName() + ".bin"; }

    /** Decryptor-stub library filename for this OS (non-sensitive; ships in the clear). */
    private static String stubName() { return isWindows() ? "zalard_loader.dll" : isMac() ? "zalard_loader.dylib" : "zalard_loader.so"; }

    private static boolean stubLoaded;
    /** Extract and System.load the native decryptor stub. Returns false (→ Java fallback) if it isn't
     *  shipped or can't load. The stub holds no client logic, so keeping it on disk is fine. */
    private static boolean loadStub() {
        if (stubLoaded) return true;
        try (InputStream in = ZalardNative.class.getResourceAsStream("/natives/" + stubName())) {
            if (in == null) return false;
            byte[] b = in.readAllBytes();
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), ".jvl-" + Long.toHexString(System.nanoTime()));
            Files.createDirectories(dir);
            Path lib = dir.resolve(stubName());
            Files.write(lib, b);
            lib.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            System.load(lib.toAbsolutePath().toString());
            stubLoaded = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Raw payload bytes for this OS, NOT decrypted: the encrypted .bin (release; enc[0]=true) or the
     *  plain lib (dev; enc[0]=false). Decryption happens in the native stub (primary) or in Java (fallback). */
    private static byte[] readCipher(boolean[] enc) throws Exception {
        try (InputStream in = ZalardNative.class.getResourceAsStream(binResource())) {
            if (in != null) { enc[0] = true; return in.readAllBytes(); }
        }
        try (InputStream in = ZalardNative.class.getResourceAsStream("/natives/" + libName())) {
            if (in == null) throw new IllegalStateException("zalard_gui native missing from the jar for " + osName());
            enc[0] = false; return in.readAllBytes();
        }
    }

    // ---- lifecycle / drawing
    public static native void init();
    public static native int version();
    public static native void onOpen();
    public static native void onClose();
    /** Builds this frame's vertices (pixel coords). Returns vertex count. */
    public static native int frame(float width, float height, float mouseX, float mouseY, float dt);
    public static native ByteBuffer vertexBuffer();
    public static native int vertexFloats();
    public static native ByteBuffer atlasPixels();
    public static native int atlasWidth();
    public static native int atlasHeight();

    // ---- input (framebuffer pixel coords, GLFW codes)
    public static native void mouseDown(float x, float y, int button);
    public static native void mouseUp(float x, float y, int button);
    public static native void mouseMove(float x, float y);
    public static native void wheel(float y, float dy);
    /** 0 = ignored, 1 = consumed, 2 = close the GUI. */
    public static native int keyDown(int key, int mods);
    public static native int charTyped(int codepoint);
    public static native int guiKey();

    public static native void setGuiKey(int key);

    // ---- ESP overlay (screen-space lines drawn under the GUI)
    public static native void beginOverlay();
    public static native void espLine(float ax, float ay, float bx, float by, float width, int argb);
    public static native int vertexCount();
    /** ESP 2D: filled rounded rect / text in framebuffer pixels (align 0 left, 1 center, 2 right). */
    public static native void espRect(float x, float y, float w, float h, float radius, int argb);
    public static native void espText(String s, float x, float cy, float size, int argb, int align);
    public static native float espTextWidth(String s, float size);
    /** Same as espText / espTextWidth but in the GUI's pixel font; bold = 1 for the bold face, shadow in px (0 = none). */
    public static native void pixText(String s, float x, float cy, float size, int argb, int align, int bold, float shadow, int shadowArgb);
    public static native float pixTextWidth(String s, float size, int bold);
    /** Saved spot of a category panel (design-canvas units, -1 = default). */
    public static native void setPanelPos(String category, float x, float y);

    // ---- footer pages (0 modules, 1 configs, 2 friends, 3 overlays, 4 HUD editor)
    public static native int page();
    public static native void setPage(int page);
    /** kind 0 = configs, 1 = friends; names joined with '\n'. */
    public static native void setList(int kind, String joined);
    public static native void setActiveConfig(String name);
    /** Next queued GUI action ("cfg_save:name", "friend_add:name", ...) or null. */
    public static native String pollAction();
    /** Overlays page: the module's on/off row, then one toggle row per setting id. */
    public static native void setOverlayItems(int module, int[] settingIds);
    public static native void hudClear();
    /** Framebuffer pixels. */
    public static native void hudAdd(String label, float x, float y, float w, float h);
    public static native int hudMoveIndex();
    public static native float hudMoveX();
    public static native float hudMoveY();
    public static native void hudMoveAck();

    // ---- registration (modules must be registered one after another)
    public static native int addModule(String name, String description);
    /** type: 0 bool, 1 bind, 2 number, 3 range, 4 enum, 5 item list, 6 slots, 8 text. */
    public static native int addSetting(int module, String section, String name, int type,
                                        float value, float value2, float min, float max, float step,
                                        String unit, String options);
    public static native void setHint(int setting, String hint);
    public static native void setVisible(int setting, boolean visible);
    /** which: 0 = Toggle, 1 = Bind, 2 = Visible (the built-in Main rows). */
    public static native int moduleSetting(int module, int which);

    // ---- values
    /** Increments whenever the GUI changes something. */
    public static native int changeSerial();
    public static native float getValue(int setting);
    public static native float getValue2(int setting);
    public static native void setValue(int setting, float value);
    public static native void setValue2(int setting, float value);
    public static native String getText(int setting);
    public static native void setText(int setting, String text);
}
