// Zalard - Copyright (c) 2026 weksom. All rights reserved. Proprietary; see LICENSE.
package dev.zalard.adapter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Spotify now-playing via OAuth Authorization Code + PKCE — the "click Connect, approve on Spotify's
 * page, done" flow. ONE-TIME SETUP by the client author: register an app at developer.spotify.com,
 * paste its Client ID into CLIENT_ID below, and add the redirect URI http://127.0.0.1:7846/callback.
 * No client secret is needed (PKCE is built for distributed/public clients). Every end user just clicks
 * Connect and approves; the refresh token is cached so later launches reconnect silently.
 *
 * Native side: T_SPOTIFY_CONNECT -> connect(); R_SPOTIFY_NOW -> nowLine(); R_SPOTIFY_STATE -> state().
 */
public final class SpotifyService {
    private SpotifyService() {}

    // >>> set this to YOUR Spotify app's Client ID (developer.spotify.com/dashboard) <<<
    private static final String CLIENT_ID = "PUT_YOUR_SPOTIFY_CLIENT_ID_HERE";
    private static final int    PORT      = 7846;
    private static final String REDIRECT  = "http://127.0.0.1:" + PORT + "/callback";
    private static final String SCOPES    = "user-read-currently-playing user-read-playback-state";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static volatile String nowLine = "", state = "off";
    private static volatile String accessToken, refreshToken, verifier;
    private static volatile long expiresAt;
    private static volatile boolean polling;

    public static String nowLine() { return nowLine == null ? "" : nowLine; }
    public static String state()   { return state; }

    /** Called from the Spotify module being enabled. Reconnects silently if a refresh token is cached,
     *  otherwise opens the browser consent flow. Safe to call repeatedly. */
    public static synchronized void connect() {
        if (CLIENT_ID.startsWith("PUT_YOUR")) { state = "error"; System.out.println("[Zalard] Spotify: set CLIENT_ID in SpotifyService.java"); return; }
        if (polling) return;
        String cached = readToken();
        if (cached != null && !cached.isEmpty()) { refreshToken = cached; startPolling(); return; }
        startAuth();
    }

    // ---- OAuth: open the consent page, catch the loopback redirect, exchange the code --------------
    private static void startAuth() {
        state = "connecting";
        new Thread(() -> {
            HttpServer server = null;
            try {
                verifier = randomUrl(64);
                String challenge = b64url(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
                String auth = "https://accounts.spotify.com/authorize?response_type=code"
                        + "&client_id=" + CLIENT_ID
                        + "&scope=" + enc(SCOPES)
                        + "&redirect_uri=" + enc(REDIRECT)
                        + "&code_challenge_method=S256&code_challenge=" + challenge;

                final String[] code = { null };
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
                final HttpServer fs = server;
                server.createContext("/callback", ex -> {
                    String q = ex.getRequestURI().getQuery();
                    code[0] = param(q, "code");
                    byte[] body = ("<html><body style='font-family:sans-serif;background:#0d0d10;color:#eee'>"
                            + "<h2>Zalard connected to Spotify.</h2>You can close this tab.</body></html>").getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                    ex.sendResponseHeaders(200, body.length);
                    ex.getResponseBody().write(body); ex.getResponseBody().close();
                    new Thread(() -> fs.stop(0)).start();
                });
                server.start();
                openBrowser(auth);

                // wait up to ~2 min for the user to approve
                for (int i = 0; i < 240 && code[0] == null; i++) Thread.sleep(500);
                if (code[0] == null) { state = "error"; return; }

                String form = "grant_type=authorization_code&code=" + enc(code[0])
                        + "&redirect_uri=" + enc(REDIRECT) + "&client_id=" + CLIENT_ID
                        + "&code_verifier=" + verifier;
                JsonObject tok = postToken(form);
                if (tok == null || !tok.has("access_token")) { state = "error"; return; }
                applyToken(tok);
                if (tok.has("refresh_token")) { refreshToken = tok.get("refresh_token").getAsString(); writeToken(refreshToken); }
                startPolling();
            } catch (Throwable t) {
                state = "error"; System.out.println("[Zalard] Spotify auth failed: " + t);
            } finally {
                if (server != null) try { server.stop(0); } catch (Throwable ignored) {}
            }
        }, "zalard-spotify-auth").start();
    }

    // ---- polling: currently-playing every few seconds ---------------------------------------------
    private static void startPolling() {
        if (polling) return;
        polling = true;
        state = "connected";
        new Thread(() -> {
            while (polling) {
                try {
                    if (System.currentTimeMillis() > expiresAt - 5000) refresh();
                    pollNow();
                } catch (Throwable t) { /* transient; keep going */ }
                try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
            }
        }, "zalard-spotify-poll").start();
    }

    private static void pollNow() throws Exception {
        if (accessToken == null) return;
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("https://api.spotify.com/v1/me/player/currently-playing"))
                .header("Authorization", "Bearer " + accessToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 204 || r.body() == null || r.body().isEmpty()) { nowLine = ""; return; }
        if (r.statusCode() == 401) { refresh(); return; }
        if (r.statusCode() != 200) return;
        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        boolean playing = o.has("is_playing") && o.get("is_playing").getAsBoolean();
        JsonObject item = o.getAsJsonObject("item");
        if (item == null) { nowLine = ""; return; }
        String song = item.get("name").getAsString();
        StringBuilder artists = new StringBuilder();
        var arr = item.getAsJsonArray("artists");
        for (int i = 0; arr != null && i < arr.size(); i++) {
            if (i > 0) artists.append(", ");
            artists.append(arr.get(i).getAsJsonObject().get("name").getAsString());
        }
        long prog = o.has("progress_ms") ? o.get("progress_ms").getAsLong() : 0;
        long dur  = item.has("duration_ms") ? item.get("duration_ms").getAsLong() : 0;
        String time = fmt(prog) + "/" + fmt(dur);
        // ASCII only — the native HUD font is a pixel face without multibyte glyphs.
        nowLine = (playing ? "" : "|| ") + song + " - " + artists + "  " + time;
    }

    private static void refresh() throws Exception {
        if (refreshToken == null) return;
        JsonObject tok = postToken("grant_type=refresh_token&refresh_token=" + enc(refreshToken) + "&client_id=" + CLIENT_ID);
        if (tok != null && tok.has("access_token")) {
            applyToken(tok);
            if (tok.has("refresh_token")) { refreshToken = tok.get("refresh_token").getAsString(); writeToken(refreshToken); }
        }
    }

    private static void applyToken(JsonObject tok) {
        accessToken = tok.get("access_token").getAsString();
        long ttl = tok.has("expires_in") ? tok.get("expires_in").getAsLong() : 3600;
        expiresAt = System.currentTimeMillis() + ttl * 1000;
    }

    private static JsonObject postToken(String form) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("https://accounts.spotify.com/api/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) { System.out.println("[Zalard] Spotify token http " + r.statusCode()); return null; }
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    // ---- helpers ----------------------------------------------------------------------------------
    private static Path tokenFile() { return Bridge.zalardDir == null ? Path.of("spotify.tok") : Bridge.zalardDir.resolve("spotify.tok"); }
    private static void writeToken(String t) { try { Files.writeString(tokenFile(), t); } catch (Throwable ignored) {} }
    private static String readToken() { try { Path f = tokenFile(); return Files.exists(f) ? Files.readString(f).trim() : null; } catch (Throwable t) { return null; } }

    private static String fmt(long ms) { long s = ms / 1000; return (s / 60) + ":" + String.format("%02d", s % 60); }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String b64url(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static byte[] sha256(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-256").digest(b); }
    private static String randomUrl(int n) { byte[] b = new byte[n]; new SecureRandom().nextBytes(b); return b64url(b).substring(0, n); }
    private static String param(String query, String key) {
        if (query == null) return null;
        for (String kv : query.split("&")) { int i = kv.indexOf('='); if (i > 0 && kv.substring(0, i).equals(key)) return URLDecode(kv.substring(i + 1)); }
        return null;
    }
    private static String URLDecode(String s) { return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8); }

    private static void openBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI.create(url)); return;
            }
        } catch (Throwable ignored) {}
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            String[] cmd = os.contains("win") ? new String[]{ "rundll32", "url.dll,FileProtocolHandler", url }
                    : os.contains("mac") ? new String[]{ "open", url } : new String[]{ "xdg-open", url };
            new ProcessBuilder(cmd).start();
        } catch (Throwable t) {
            System.out.println("[Zalard] Spotify: open this URL to connect: " + url);
        }
    }
}
