package dev.zalard.update;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Download + install integration for signed updates (Priority 8). Fetches the signed manifest and the files
 * it lists, then hands them to {@link UpdateVerifier} — verification is MANDATORY and happens before
 * anything is activated, and the install is atomic (a single pointer flip), so a failed or interrupted
 * download can never leave a partially-updated install.
 *
 * Fail-closed: if no publisher key is configured ({@link UpdateVerifier#PUBLISHER_KEY} empty), updating is
 * refused outright — an unsigned/unverifiable update is never installed.
 *
 * The fetch is abstracted behind {@link Fetcher} so it is testable without a network; {@link HttpFetcher}
 * is the production implementation. A running Fabric mod can't swap itself in memory, so the new version is
 * installed under {@code versionsRoot/<version>/} and a launcher loads whatever {@code current} points at on
 * the next start.
 */
public final class Updater {
    private Updater() {}

    // ====================================================================================================
    // >>> SET YOUR RELEASE UPDATE URL HERE to enable auto-updates (optional feature). <<<
    // Must be https. Leave it as the placeholder and auto-update is simply DISABLED (the client still runs);
    // it is never pointed at the example host. If you DO enable updates, UpdateVerifier.PUBLISHER_KEY must
    // also be set or startBackgroundCheck refuses to run (fail-closed — no unverifiable updates).
    public static final String UPDATE_BASE = "https://zalard-updates.example.workers.dev";   // <-- REPLACE or leave to disable
    // ====================================================================================================
    private static final String UPDATE_PLACEHOLDER = "https://zalard-updates.example.workers.dev";

    /** Fetches a file by its manifest-relative path (or "manifest.txt"). Throws on any failure. */
    public interface Fetcher {
        byte[] get(String path) throws Exception;
    }

    /** True when auto-update is configured for a release (a real https URL + a publisher key). */
    public static boolean updatesEnabled() {
        return UPDATE_BASE.startsWith("https://") && !UPDATE_BASE.equals(UPDATE_PLACEHOLDER)
                && UpdateVerifier.PUBLISHER_KEY != null && !UpdateVerifier.PUBLISHER_KEY.isEmpty();
    }

    /**
     * Production entry point, called once at startup. Does nothing if auto-update isn't configured (the
     * client runs normally). Otherwise it checks for a newer SIGNED release in a daemon thread and stages
     * it under {@code versionsRoot} for the next launch — verification is mandatory and any error is
     * swallowed so a failed update never blocks startup. Returns immediately.
     */
    public static void startBackgroundCheck(Path versionsRoot) {
        if (!updatesEnabled()) {
            System.out.println("[Zalard] auto-update disabled (UPDATE_BASE/PUBLISHER_KEY not set)");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                String v = checkAndApply(UPDATE_BASE, versionsRoot);
                System.out.println(v == null ? "[Zalard] up to date" : "[Zalard] staged update " + v + " for next launch");
            } catch (Throwable e) {
                System.err.println("[Zalard] update check failed (continuing on current version): " + e);
            }
        }, "zalard-update");
        t.setDaemon(true);
        t.start();
    }

    /** Check for and install a newer signed release. Returns the new version, or null if already current.
     *  Verification is mandatory; throws (and leaves the active install untouched) on any security failure. */
    public static String checkAndApply(Fetcher fetcher, byte[] publisherKey, Path versionsRoot) throws Exception {
        if (publisherKey == null || publisherKey.length != 32)
            throw new SecurityException("no publisher key configured — refusing to install an unverifiable update");

        String manifestText = new String(fetcher.get("manifest.txt"), java.nio.charset.StandardCharsets.UTF_8);
        UpdateVerifier.Manifest m = UpdateVerifier.verifyManifest(manifestText, publisherKey);   // mandatory

        String current = UpdateVerifier.activeVersion(versionsRoot);
        if (current == null) current = "0.0.0";
        if (!UpdateVerifier.isNewer(m.version(), current)) return null;      // up to date / no downgrade

        Path staging = Files.createTempDirectory("zalard-dl");
        try {
            for (Map.Entry<String, String> e : m.files().entrySet()) {
                if (!UpdateVerifier.isSafeRelPath(e.getKey()))
                    throw new SecurityException("unsafe path in manifest: " + e.getKey());
                Path dst = staging.resolve(e.getKey());
                Files.createDirectories(dst.getParent() == null ? staging : dst.getParent());
                Files.write(dst, fetcher.get(e.getKey()));
            }
            // verifies signature + rollback + every file hash, then atomically activates
            return UpdateVerifier.applyUpdate(manifestText, publisherKey, current, staging, versionsRoot);
        } finally {
            deleteRec(staging);
        }
    }

    /** Production entry point: HTTPS fetch from {@code baseUrl}, using the embedded publisher key. */
    public static String checkAndApply(String baseUrl, Path versionsRoot) throws Exception {
        if (UpdateVerifier.PUBLISHER_KEY == null || UpdateVerifier.PUBLISHER_KEY.isEmpty())
            throw new SecurityException("UpdateVerifier.PUBLISHER_KEY is empty — set it for a production release");
        if (!baseUrl.startsWith("https://"))
            throw new SecurityException("update base must be https://");
        return checkAndApply(new HttpFetcher(baseUrl), UpdateVerifier.hex(UpdateVerifier.PUBLISHER_KEY), versionsRoot);
    }

    /** HTTPS fetcher with a size cap and status check. */
    public static final class HttpFetcher implements Fetcher {
        private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        private static final long MAX = 64L * 1024 * 1024;   // 64 MB per file cap
        private final String base;
        public HttpFetcher(String base) { this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base; }

        @Override public byte[] get(String path) throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/" + path))
                    .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<byte[]> r = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) throw new java.io.IOException("fetch " + path + " -> HTTP " + r.statusCode());
            byte[] b = r.body();
            if (b == null || b.length > MAX) throw new java.io.IOException("fetch " + path + " too large/empty");
            return b;
        }
    }

    private static void deleteRec(Path p) throws Exception {
        if (Files.isDirectory(p)) { try (var s = Files.list(p)) { for (Path c : s.toList()) deleteRec(c); } }
        Files.deleteIfExists(p);
    }
}
