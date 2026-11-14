// Zalard - Copyright (c) 2026 weksom. All rights reserved. Proprietary; see LICENSE.
package dev.zalard.adapter;

import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chat translation. Set your native language (native side pushes the code via T_SET_NATIVE_LANG); every
 * incoming chat line in another language is translated into it and added below as a greyed line tagged
 * with the detected source language. Lookup is async (online translator) so it never blocks the game.
 */
public final class TranslationService {
    private TranslationService() {}

    private static volatile String nativeLang = "";
    public static volatile boolean suppress = false;   // true while WE add the translated line (skip re-translation)

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "zalard-translate"); t.setDaemon(true); return t;
    });

    public static void setNative(String code) { nativeLang = code == null ? "" : code.trim().toLowerCase(); }
    public static boolean active() { return !nativeLang.isEmpty(); }

    /** Called from the chat mixin for each incoming line. Translates off-thread and adds a tagged line. */
    public static void onChat(String text) {
        final String lang = nativeLang;
        if (lang.isEmpty() || text == null) return;
        final String q = text.strip();
        if (q.length() < 2 || q.length() > 400) return;
        POOL.submit(() -> {
            try {
                String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl="
                        + lang + "&dt=t&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) return;
                var arr = JsonParser.parseString(r.body()).getAsJsonArray();
                String src = arr.size() > 2 && !arr.get(2).isJsonNull() ? arr.get(2).getAsString() : "";
                if (src.equalsIgnoreCase(lang)) return;                  // already in your language
                StringBuilder sb = new StringBuilder();
                for (var seg : arr.get(0).getAsJsonArray()) {
                    var s = seg.getAsJsonArray();
                    if (s.size() > 0 && !s.get(0).isJsonNull()) sb.append(s.get(0).getAsString());
                }
                String tr = sb.toString().strip();
                if (tr.isEmpty() || tr.equalsIgnoreCase(q)) return;
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> {                                       // touch chat on the client thread
                    if (mc.gui == null) return;
                    suppress = true;
                    try { mc.gui.getChat().addMessage(Component.literal("§8[§d" + src + "§8] §7" + tr)); }
                    finally { suppress = false; }
                });
            } catch (Throwable ignored) { /* network/parse hiccup: skip this line */ }
        });
    }
}
