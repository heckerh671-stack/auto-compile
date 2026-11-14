package dev.zalard.update;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verifies signed Zalard release manifests before an update is installed (Priority 8).
 *
 * Trust anchor: the publisher's Ed25519 PUBLIC key, embedded in {@link #PUBLISHER_KEY}. The matching
 * private key stays with the publisher and is never shipped, so a jar can't be made to trust a forged
 * update. Verification uses the JDK's built-in Ed25519 — no native dependency — and the manifests are
 * produced by {@code tools/sign_release.py} (libsodium); the two are byte-for-byte compatible (RFC 8032).
 *
 * A manifest is accepted only if ALL hold: the signature verifies under the publisher key; the version is
 * strictly newer than what's installed (rollback protection); and every listed file's SHA-256 matches.
 * Install is staged then committed, so a failed/interrupted verification leaves the current version intact.
 */
public final class UpdateVerifier {
    private UpdateVerifier() {}

    /** Publisher Ed25519 public key, 64 hex chars. Set this from keygen_release.py for your release. */
    public static final String PUBLISHER_KEY = "fa4f36814b07e84da4267d58e382109348fdff7ce59620af9a886e9c331fdf86";

    public record Manifest(String version, String created, Map<String, String> files) {}

    /** Parse + verify a manifest's signature. Throws SecurityException on a bad signature or malformed
     *  input; returns the parsed manifest (file paths validated as safe relative paths). */
    public static Manifest verifyManifest(String manifestText, byte[] publisherKey) throws Exception {
        if (publisherKey == null || publisherKey.length != 32)
            throw new SecurityException("bad publisher key");
        final String MARKER = "-----SIGNATURE-----\n";
        int i = manifestText.indexOf(MARKER);
        if (i < 0) throw new SecurityException("no signature marker");
        byte[] signed = manifestText.substring(0, i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sigB64 = manifestText.substring(i + MARKER.length()).trim();
        byte[] sig = java.util.Base64.getDecoder().decode(sigB64);

        Signature v = Signature.getInstance("Ed25519");
        v.initVerify(edPublicKey(publisherKey));
        v.update(signed);
        if (!v.verify(sig)) throw new SecurityException("manifest signature invalid");

        // parse the verified header
        String version = null, created = null;
        Map<String, String> files = new LinkedHashMap<>();
        for (String line : manifestText.substring(0, i).split("\n")) {
            if (line.startsWith("version ")) version = line.substring(8).trim();
            else if (line.startsWith("created ")) created = line.substring(8).trim();
            else if (line.startsWith("file ")) {
                String[] parts = line.substring(5).trim().split("\\s+");
                if (parts.length != 2) throw new SecurityException("bad file line");
                String path = parts[0];
                if (!isSafeRelPath(path)) throw new SecurityException("unsafe path in manifest: " + path);
                files.put(path, parts[1].toLowerCase());
            }
        }
        if (version == null || version.isEmpty()) throw new SecurityException("manifest has no version");
        return new Manifest(version, created, files);
    }

    /** True if a file's SHA-256 equals the expected lowercase hex. */
    public static boolean fileMatches(Path f, String sha256hex) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[65536];
        try (var in = Files.newInputStream(f)) {
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        }
        byte[] h = md.digest();
        StringBuilder sb = new StringBuilder(h.length * 2);
        for (byte b : h) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString().equalsIgnoreCase(sha256hex);
    }

    /** Numeric-dotted version compare: true iff candidate is strictly newer than current. */
    public static boolean isNewer(String candidate, String current) {
        String[] a = candidate.split("[.-]"), b = current.split("[.-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length ? parse(a[i]) : 0, y = i < b.length ? parse(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;   // equal -> not newer (blocks re-install/rollback to same)
    }

    /** The version currently active under {@code versionsRoot} (the text in {@code versionsRoot/current}),
     *  or null if none is active yet. */
    public static String activeVersion(Path versionsRoot) throws Exception {
        Path cur = versionsRoot.resolve("current");
        if (!Files.isRegularFile(cur)) return null;
        String v = Files.readString(cur).trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * Stage a new version: verify the manifest signature, enforce rollback vs the currently active version,
     * verify every staged file's hash, and write the files into {@code versionsRoot/<newVersion>/}. Does NOT
     * activate it, and does NOT touch the active version — so a failure or crash here leaves the running
     * install completely intact. Returns the new version.
     */
    public static String stageVersion(String manifestText, byte[] publisherKey, String currentVersion,
                                      Path stagingDir, Path versionsRoot) throws Exception {
        Manifest m = verifyManifest(manifestText, publisherKey);                 // bad sig -> throw
        if (!isNewer(m.version(), currentVersion))
            throw new SecurityException("rollback blocked: " + m.version() + " <= " + currentVersion);
        // verify EVERY file before writing the version dir
        for (Map.Entry<String, String> e : m.files().entrySet()) {
            Path f = stagingDir.resolve(e.getKey());
            if (!Files.isRegularFile(f)) throw new SecurityException("missing staged file: " + e.getKey());
            if (!fileMatches(f, e.getValue())) throw new SecurityException("hash mismatch: " + e.getKey());
        }
        // write into a fresh per-version directory (never overwrites the active version's files)
        Path verDir = versionsRoot.resolve(m.version());
        if (Files.exists(verDir)) deleteRec(verDir);               // clear a previous incomplete attempt
        for (Map.Entry<String, String> e : m.files().entrySet()) {
            Path dst = verDir.resolve(e.getKey());
            Files.createDirectories(dst.getParent() == null ? verDir : dst.getParent());
            Files.copy(stagingDir.resolve(e.getKey()), dst, StandardCopyOption.REPLACE_EXISTING);
        }
        return m.version();
    }

    /** ATOMICALLY make {@code version} the active one by flipping the single {@code current} pointer file.
     *  This one rename is the commit point: before it, the old version is active; after it, the new one is.
     *  There is no window where a half-set of files is live.
     *
     *  The temp file is created IN {@code versionsRoot} (same directory as the pointer), so the rename is
     *  always same-filesystem and atomic on any mainstream FS. If the filesystem genuinely can't do an
     *  atomic rename, this throws rather than silently doing a non-atomic replace — we never claim atomic
     *  activation we didn't perform. The current pointer is left as it was (old version stays active). */
    public static void activate(Path versionsRoot, String version) throws Exception {
        Files.createDirectories(versionsRoot);
        Path tmp = Files.createTempFile(versionsRoot, "current", ".tmp");   // same dir => same filesystem
        try {
            Files.writeString(tmp, version + "\n");
            Files.move(tmp, versionsRoot.resolve("current"),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.deleteIfExists(tmp);
            throw new java.io.IOException("atomic activation unsupported on the filesystem at " + versionsRoot
                    + "; refusing a non-atomic commit to avoid a partially-activated update", e);
        } catch (Exception e) {
            Files.deleteIfExists(tmp);     // on any failure, leave the existing pointer untouched
            throw e;
        }
    }

    /**
     * Full update = {@link #stageVersion} then {@link #activate}. Atomic and crash-safe: a failure before the
     * activate() flip leaves the current version live (recovery); the flip itself is a single atomic rename,
     * so the install is never partially replaced. Returns the new version.
     */
    public static String applyUpdate(String manifestText, byte[] publisherKey, String currentVersion,
                                     Path stagingDir, Path versionsRoot) throws Exception {
        String v = stageVersion(manifestText, publisherKey, currentVersion, stagingDir, versionsRoot);
        activate(versionsRoot, v);
        return v;
    }

    private static void deleteRec(Path p) throws Exception {
        if (Files.isDirectory(p)) { try (var s = Files.list(p)) { for (Path c : s.toList()) deleteRec(c); } }
        Files.deleteIfExists(p);
    }

    // reject absolute paths, drive letters, and any ".." traversal; keep it to simple relative paths
    static boolean isSafeRelPath(String p) {
        if (p.isEmpty() || p.startsWith("/") || p.startsWith("\\") || p.contains(":")) return false;
        for (String seg : p.split("[/\\\\]")) if (seg.equals("..") || seg.isEmpty()) return false;
        return true;
    }

    private static long parse(String s) {
        long n = 0; boolean any = false;
        for (int i = 0; i < s.length() && Character.isDigit(s.charAt(i)); i++) { n = n * 10 + (s.charAt(i) - '0'); any = true; }
        return any ? n : 0;
    }

    /** Build an EdECPublicKey from a 32-byte raw Ed25519 public key (little-endian y, top bit = x sign). */
    private static java.security.PublicKey edPublicKey(byte[] raw32) throws Exception {
        byte[] rev = new byte[32];
        for (int i = 0; i < 32; i++) rev[i] = raw32[31 - i];
        boolean xOdd = (rev[0] & 0x80) != 0;
        rev[0] &= 0x7f;
        BigInteger y = new BigInteger(1, rev);
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, y)));
    }

    static byte[] hex(String s) {
        byte[] o = new byte[s.length() / 2];
        for (int i = 0; i < o.length; i++) o[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return o;
    }
}
