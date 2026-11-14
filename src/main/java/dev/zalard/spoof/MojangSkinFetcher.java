package dev.zalard.spoof;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Resolves a player name to their skin PNG via Mojang's public API. Async + cached; never blocks
// a frame.
//   name -> api.minecraftservices.com (fallback api.mojang.com) -> uuid
//   uuid -> sessionserver.mojang.com -> base64 "textures" property -> SKIN url (+ slim/wide model)
//   url  -> PNG bytes -> adapter turns them into a texture
//
// Why it didn't work before: the session server answers with pretty-printed JSON
// ("name" : "textures" with spaces), and the parser looked for "name":"textures" with no spaces,
// so it never found the skin and silently gave up.
public final class MojangSkinFetcher {

    private MojangSkinFetcher() {}

    private static final java.util.concurrent.ExecutorService EX = Executors.newFixedThreadPool(2, r -> {
        var t = new Thread(r, "mako-skin-fetch");
        t.setDaemon(true);
        return t;
    });

    public record Profile(UUID uuid, String name, String skinUrl, byte[] skinPng, boolean slim) {}

    private static final ConcurrentHashMap<String, Profile> CACHE_BY_NAME = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID,  Profile> CACHE_BY_UUID = new ConcurrentHashMap<>();

    /** Last failure reason, for the chat message. */
    private static volatile String lastError = "";
    public static String lastError() { return lastError; }

    public static CompletableFuture<Profile> byName(String name) {
        String key = name.toLowerCase();
        var hit = CACHE_BY_NAME.get(key);
        if (hit != null) return CompletableFuture.completedFuture(hit);
        // a skin used before comes straight off the disk: no waiting on Mojang at all
        Profile disk = readDisk(key);
        if (disk != null) {
            CACHE_BY_NAME.put(key, disk);
            CACHE_BY_UUID.put(disk.uuid(), disk);
            return CompletableFuture.completedFuture(disk);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                UUID id = lookupUuid(name);
                if (id == null) { lastError = "no Minecraft account named '" + name + "'"; return null; }
                return fetchProfileFull(id);
            } catch (Exception e) { lastError = e.toString(); return null; }
        }, EX).thenApply(p -> { if (p != null) { CACHE_BY_NAME.put(key, p); writeDisk(key, p); } return p; });
    }

    // ---------- disk cache: .minecraft/mako/skins/<name>.png + <name>.txt ("uuid slim") ----------
    private static java.nio.file.Path skinDir() {
        return java.nio.file.Path.of(System.getProperty("user.dir"), "mako", "skins");
    }
    private static Profile readDisk(String key) {
        try {
            var dir = skinDir();
            var png = dir.resolve(key + ".png"); var meta = dir.resolve(key + ".txt");
            if (!java.nio.file.Files.exists(png) || !java.nio.file.Files.exists(meta)) return null;
            // refresh in the background after a week so changed skins show up eventually
            if (System.currentTimeMillis() - java.nio.file.Files.getLastModifiedTime(png).toMillis() > 7L * 24 * 3600 * 1000) return null;
            String[] m = java.nio.file.Files.readString(meta).trim().split(" ");
            UUID id = UUID.fromString(m[0]);
            boolean slim = m.length > 1 && "slim".equals(m[1]);
            return new Profile(id, key, null, java.nio.file.Files.readAllBytes(png), slim);
        } catch (Exception e) { return null; }
    }
    private static void writeDisk(String key, Profile p) {
        if (p.skinPng() == null || !key.matches("[a-z0-9_]{1,16}")) return;
        try {
            var dir = skinDir();
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve(key + ".png"), p.skinPng());
            java.nio.file.Files.writeString(dir.resolve(key + ".txt"), p.uuid() + " " + (p.slim() ? "slim" : "wide"));
        } catch (Exception ignored) {}
    }

    public static CompletableFuture<Profile> byUuid(UUID id) {
        var hit = CACHE_BY_UUID.get(id);
        if (hit != null) return CompletableFuture.completedFuture(hit);
        return CompletableFuture.supplyAsync(() -> fetchProfileFull(id), EX);
    }

    /** Both Mojang name endpoints at once; whichever answers first wins (one of them is often slow). */
    private static UUID lookupUuid(String name) throws IOException {
        CompletableFuture<UUID> a = CompletableFuture.supplyAsync(() -> idFrom("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name), LOOKUP);
        CompletableFuture<UUID> b = CompletableFuture.supplyAsync(() -> idFrom("https://api.mojang.com/users/profiles/minecraft/" + name), LOOKUP);
        CompletableFuture<UUID> first = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger left = new java.util.concurrent.atomic.AtomicInteger(2);
        for (var f : java.util.List.of(a, b)) f.whenComplete((id, err) -> {
            if (id != null) first.complete(id);
            else if (left.decrementAndGet() == 0) first.complete(null);
        });
        try { return first.get(8, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception e) { return null; }
    }
    private static final java.util.concurrent.ExecutorService LOOKUP = Executors.newFixedThreadPool(2, r -> {
        var t = new Thread(r, "mako-skin-lookup"); t.setDaemon(true); return t;
    });
    private static UUID idFrom(String url) {
        try { String json = httpGet(url); return json == null ? null : parseUuid(str(json, "id")); }
        catch (Exception e) { return null; }
    }

    private static Profile fetchProfileFull(UUID id) {
        try {
            String json = httpGet("https://sessionserver.mojang.com/session/minecraft/profile/" + id.toString().replace("-", ""));
            if (json == null) { lastError = "session server gave no profile"; return null; }
            String textureProp = property(json, "textures");
            if (textureProp == null) { lastError = "profile has no textures"; return null; }
            String decoded = new String(Base64.getDecoder().decode(textureProp), StandardCharsets.UTF_8);
            int skinAt = decoded.indexOf("\"SKIN\"");
            if (skinAt < 0) { lastError = "account uses the default skin"; return null; }
            String skinPart = decoded.substring(skinAt);
            int capeAt = skinPart.indexOf("\"CAPE\"");                 // don't read into the cape block
            if (capeAt > 0) skinPart = skinPart.substring(0, capeAt);
            String skinUrl = str(skinPart, "url");
            if (skinUrl == null) { lastError = "no skin url"; return null; }
            if (skinUrl.startsWith("http://")) skinUrl = "https://" + skinUrl.substring(7);
            boolean slim = skinPart.matches("(?s).*\"model\"\\s*:\\s*\"slim\".*");
            byte[] png = httpBytes(skinUrl);
            if (png == null || png.length == 0) { lastError = "skin download failed"; return null; }
            String real = str(json, "name");
            Profile p = new Profile(id, real, skinUrl, png, slim);
            CACHE_BY_UUID.put(id, p);
            if (real != null) CACHE_BY_NAME.put(real.toLowerCase(), p);
            return p;
        } catch (Exception e) { lastError = e.toString(); return null; }
    }

    /** Fetches `name`'s skin, points `targetReal` at it and hands the PNG to `onReady`. */
    public static void prefetchAndApply(String name, UUID targetReal, OnReady onReady, Consumer<String> onFail) {
        byName(name).thenAccept(p -> {
            if (p == null || p.skinPng == null) { if (onFail != null) onFail.accept(lastError); return; }
            var cur = SpoofRegistry.get(targetReal);
            SpoofRegistry.setOverride(targetReal, cur == null ? null : cur.displayName(), p.uuid);
            SpoofRegistry.setSlim(p.uuid, p.slim);
            onReady.accept(p.uuid, p.skinPng);
        });
    }

    @FunctionalInterface public interface OnReady { void accept(UUID skinOwner, byte[] png); }

    // ---------- minimal HTTP + JSON helpers (no deps, whitespace tolerant) ----------
    private static String httpGet(String u) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(u).toURL().openConnection();
        c.setConnectTimeout(4000); c.setReadTimeout(5000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setRequestProperty("Accept", "application/json");
        if (c.getResponseCode() != 200) return null;
        try (var in = c.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }
    private static byte[] httpBytes(String u) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(u).toURL().openConnection();
        c.setConnectTimeout(4000); c.setReadTimeout(5000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() != 200) return null;
        try (var in = c.getInputStream()) { return in.readAllBytes(); }
    }
    /** First "key" : "value" string in the json. */
    private static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
    /** value of the properties[] entry called `name`. */
    private static String property(String json, String name) {
        Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"" + Pattern.quote(name) + "\"").matcher(json);
        if (!m.find()) return null;
        // the entry's "value" can come before or after its "name"; take the closest one
        int at = m.start();
        Matcher v = Pattern.compile("\"value\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        String best = null; int bestDist = Integer.MAX_VALUE;
        while (v.find()) {
            int d = Math.abs(v.start() - at);
            if (d < bestDist) { bestDist = d; best = v.group(1); }
        }
        return best;
    }
    private static UUID parseUuid(String raw) {
        if (raw == null) return null;
        if (raw.length() == 32) {
            raw = raw.substring(0,8) + "-" + raw.substring(8,12) + "-" +
                  raw.substring(12,16) + "-" + raw.substring(16,20) + "-" + raw.substring(20);
        }
        try { return UUID.fromString(raw); } catch (IllegalArgumentException e) { return null; }
    }
}
