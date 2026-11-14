package dev.zalard.auth;

import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Free-login gate for the payload key. On launch the client authenticates to the Zalard auth server
 * (a Cloudflare Worker, see /auth-server) and the server returns the 32-byte payload decryption key —
 * which is NOT in the jar. The shipped jar carries only ciphertext; without a valid, non-banned account
 * on this machine there is no key, and nothing decrypts. A leaked build is cut off by banning the account.
 *
 * Production endpoint: {@link #AUTH_BASE} is the compiled-in server URL. In a PRODUCTION build it is used
 * verbatim and a stray {@code -Dzalard.auth.base} / config {@code base=} CANNOT redirect authentication
 * (anti-footgun). HTTPS is required. If {@code AUTH_BASE} is still the placeholder, the client fails closed.
 * Only a DEV build ({@code -Dzalard.dev=true}) may override the base (and allow http://localhost) for testing.
 *
 * Credentials: {@code -Dzalard.auth.user}/{@code -Dzalard.auth.pass}, or {@code zalard-auth.properties}
 * (username, password) in the game dir (or {@code -Dzalard.auth.config=<path>}).
 */
public final class Auth {
    private Auth() {}

    // ====================================================================================================
    // >>> SET YOUR REAL AUTH SERVER URL HERE before a production build. <<<
    // This is the ONLY place to enter it. Leave it as the placeholder and production FAILS CLOSED
    // (validateBase throws; the release CI gate `AuthConfigTest --release` also fails). It must be https.
    // No URL is invented for you.
    public static final String AUTH_BASE = "https://zalard-auth.heckerh671.workers.dev";   // deployed Worker
    // ====================================================================================================
    private static final String PLACEHOLDER = "https://zalard-auth.example.workers.dev";

    /** Re-auth outcome. OK = carry on; RENEW = session lapsed/aged, re-login (not a revocation);
     *  REVOKED = banned/device mismatch, stop; NETWORK = couldn't reach the server, keep trying. */
    public enum Beat { OK, RENEW, REVOKED, NETWORK }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).version(HttpClient.Version.HTTP_2).build();
    private static final Pattern FIELD = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");

    private static volatile String sessionToken, sessionBase, sessionHwid;

    private static boolean isDev() { return "true".equalsIgnoreCase(System.getProperty("zalard.dev", "")); }

    /** Resolve + validate the auth base URL. Pure function (no system state) so it is unit-testable.
     *  PROD: always the compiled URL; must be https and not the placeholder. DEV: override allowed, and
     *  http://localhost permitted. Throws (fail-safe) if the endpoint isn't validly configured. */
    static String validateBase(boolean dev, String compiled, String sysOverride, String fileBase) {
        String base;
        if (dev) base = sysOverride != null ? sysOverride : (fileBase != null ? fileBase : compiled);
        else base = compiled;                           // PROD: ignore overrides entirely
        if (base == null) throw new SecurityException("Zalard auth endpoint not configured");
        base = trimSlash(base);
        if (!dev) {
            if (base.equals(PLACEHOLDER))
                throw new SecurityException("Zalard auth endpoint not configured (AUTH_BASE is still the placeholder) — set it for the release build");
            if (!base.startsWith("https://"))
                throw new SecurityException("Zalard auth endpoint must be https:// in production");
        } else if (!(base.startsWith("https://") || base.startsWith("http://localhost") || base.startsWith("http://127."))) {
            throw new SecurityException("dev auth base must be https:// or http://localhost");
        }
        return base;
    }

    private static String resolveBase(Props p) {
        return validateBase(isDev(), AUTH_BASE, System.getProperty("zalard.auth.base"), p.fileBase);
    }

    /** Authenticate and return the 32-byte payload key. Throws if login fails — the client then stays
     *  disabled (no key, nothing to decrypt), which is the whole point. */
    public static byte[] fetchKey() throws Exception {
        Props p = loadProps();
        String base = resolveBase(p);
        String hwid = hwid();

        String loginResp = post(base + "/login", "{\"username\":" + jstr(p.user) + ",\"password\":" + jstr(p.pass)
                + ",\"hwid\":\"" + hwid + "\"}");
        String token = field(loginResp, "token");
        if (token == null) throw new SecurityException("Zalard login failed: " + errOf(loginResp));

        String keyResp = post(base + "/key", "{\"token\":\"" + token + "\",\"hwid\":\"" + hwid + "\"}");
        String keyHex = field(keyResp, "key");
        if (keyHex == null || keyHex.length() != 64)
            throw new SecurityException("Zalard key fetch failed: " + errOf(keyResp));
        sessionToken = token; sessionBase = base; sessionHwid = hwid;
        return unhex(keyHex);
    }

    /** One re-authorization ping. Distinguishes renew (lapsed/aged) from revoked (banned) from a network
     *  blip — a transient error never kills a legit session. */
    public static Beat heartbeat() {
        try {
            if (sessionToken == null) return Beat.OK;
            String flat = post(sessionBase + "/heartbeat",
                    "{\"token\":\"" + sessionToken + "\",\"hwid\":\"" + sessionHwid + "\"}").replaceAll("\\s", "");
            if (flat.contains("\"revoked\":true")) return Beat.REVOKED;
            if (flat.contains("\"renew\":true")) return Beat.RENEW;
            if (flat.contains("\"ok\":true")) return Beat.OK;
            return Beat.NETWORK;                        // garbled/unexpected -> treat as transient
        } catch (Exception e) {
            return Beat.NETWORK;
        }
    }

    /** Re-login to obtain a fresh session token (after a RENEW). Returns OK on success, REVOKED on an
     *  explicit auth failure (banned/invalid/locked), NETWORK otherwise (keep trying). */
    private static Beat relogin() {
        try {
            Props p = loadProps();
            String resp = post(sessionBase + "/login", "{\"username\":" + jstr(p.user) + ",\"password\":"
                    + jstr(p.pass) + ",\"hwid\":\"" + sessionHwid + "\"}");
            String token = field(resp, "token");
            if (token != null) { sessionToken = token; return Beat.OK; }
            String e = field(resp, "error");
            if (e != null && (e.contains("banned") || e.contains("invalid") || e.contains("locked") || e.contains("device")))
                return Beat.REVOKED;
            return Beat.NETWORK;
        } catch (Exception e) {
            return Beat.NETWORK;
        }
    }

    /** Start a daemon that re-authorizes about every 90s. Renews the sliding session, re-logs-in when it
     *  ages out, and runs {@code onRevoked} once if the server revokes the account. A network outage is
     *  survived (it keeps trying), so only a real revocation disables the client. */
    public static void startHeartbeat(Runnable onRevoked) {
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(90_000); } catch (InterruptedException ie) { return; }
                Beat b = heartbeat();
                if (b == Beat.REVOKED) { fire(onRevoked); return; }
                if (b == Beat.RENEW && relogin() == Beat.REVOKED) { fire(onRevoked); return; }
                // OK or NETWORK -> keep going (NETWORK retries next cycle)
            }
        }, "zalard-heartbeat");
        t.setDaemon(true);
        t.start();
    }

    private static void fire(Runnable r) { try { r.run(); } catch (Throwable ignored) {} }

    // --- HTTP ---------------------------------------------------------------------------------------
    private static String post(String url, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 500) throw new java.io.IOException("server error " + r.statusCode());
        String b = r.body() == null ? "" : r.body();
        if (b.length() > 65536) throw new java.io.IOException("oversized response");   // sanity bound
        return b;
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile(String.format(FIELD.pattern(), name)).matcher(json == null ? "" : json);
        return m.find() ? m.group(1) : null;
    }
    private static String errOf(String json) {
        String e = field(json, "error");
        return e != null ? e : "unreachable or malformed response";
    }

    // --- machine id ---------------------------------------------------------------------------------
    /** Stable-ish per-machine id: SHA-256 of the MAC addresses + OS user + OS name, as hex. Not spoof-proof
     *  (nothing client-side is), but enough to bind an account to a device and to revoke. */
    public static String hwid() {
        try {
            StringBuilder sb = new StringBuilder();
            boolean gotMac = false;
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (ni.isLoopback() || ni.isVirtual()) continue;
                byte[] mac = ni.getHardwareAddress();
                if (mac != null && mac.length > 0) { for (byte b : mac) sb.append(String.format("%02x", b)); gotMac = true; }
            }
            // No fixed fallback: if we can't read a real hardware id, FAIL rather than bind every such
            // machine to one shared constant (the old "0000…" fallback was a device-binding hole).
            if (!gotMac) throw new SecurityException("no usable network interface for a hardware id");
            sb.append('|').append(System.getProperty("user.name", "?"));
            sb.append('|').append(System.getProperty("os.name", "?"));
            byte[] h = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : h) out.append(String.format("%02x", b));
            return out.toString();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("hwid computation failed", e);
        }
    }

    // --- credentials --------------------------------------------------------------------------------
    private static final class Props { String user, pass, fileBase; }

    private static Props loadProps() throws Exception {
        Props p = new Props();
        p.user = System.getProperty("zalard.auth.user");
        p.pass = System.getProperty("zalard.auth.pass");
        String cfg = System.getProperty("zalard.auth.config", "zalard-auth.properties");
        Path path = Path.of(cfg);
        if (Files.isRegularFile(path)) {
            Properties f = new Properties();
            try (var in = Files.newInputStream(path)) { f.load(in); }
            if (p.user == null) p.user = f.getProperty("username");
            if (p.pass == null) p.pass = f.getProperty("password");
            p.fileBase = f.getProperty("base");          // honoured only in dev (see validateBase)
        }
        if (p.user == null || p.pass == null)
            throw new SecurityException("no Zalard credentials — set zalard-auth.properties (username/password) "
                    + "or -Dzalard.auth.user/-Dzalard.auth.pass");
        return p;
    }

    // --- small helpers ------------------------------------------------------------------------------
    private static String trimSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
    private static String jstr(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
    private static byte[] unhex(String h) {
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
