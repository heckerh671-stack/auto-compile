package dev.zalard.adapter;

import dev.zalard.spoof.SpoofRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Shared helpers for the Name Spoof / Skin Spoof mixins. */
public final class SpoofText {
    private SpoofText() {}

    // real name -> spoofed name, rebuilt at most every 250 ms (these hooks run for every nametag)
    private static volatile Map<String, String> names = Map.of();
    private static volatile java.util.regex.Pattern namePattern;
    private static volatile long namesAt;
    private static final Map<Component, Component> DONE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Real name -> spoofed name for every active override. */
    private static Map<String, String> nameMap() {
        long now = System.currentTimeMillis();
        if (now - namesAt < 250) return names;
        namesAt = now;
        Map<String, String> out = new LinkedHashMap<>();
        var mc = Minecraft.getInstance();
        var conn = mc.getConnection();
        if (conn != null) {
            SpoofRegistry.nameOverrides().forEach((uuid, spoofed) -> {
                String real = null;
                var info = conn.getPlayerInfo(uuid);
                if (info != null && info.getProfile() != null) real = info.getProfile().name();
                // plugin servers often hide / rename your own tab entry: fall back to your account name
                if (real == null && mc.player != null && uuid.equals(mc.player.getUUID())) real = mc.player.getGameProfile().name();
                if (real != null && !real.isEmpty() && !real.equals(spoofed)) out.put(real, spoofed);
            });
        }
        if (!out.equals(names)) {
            DONE.clear();
            StringBuilder re = new StringBuilder();
            for (String r : out.keySet()) { if (re.length() > 0) re.append('|'); re.append(java.util.regex.Pattern.quote(r)); }
            // whole names only: "Bob" must not turn "Bobby" into "Alexby"
            namePattern = out.isEmpty() ? null
                    : java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])(?:" + re + ")(?![A-Za-z0-9_])");
            names = out;
        }
        return names;
    }

    /** Any name override live right now (cheap: the hooks skip all work when not). */
    public static boolean namesActive() { return !nameMap().isEmpty(); }

    /** Rebuilds a component with real names swapped out, keeping styles. Same object back if nothing changed. */
    public static Component replaceNames(Component msg) {
        Map<String, String> map = nameMap();
        if (map.isEmpty() || msg == null) return msg;
        Component cached = DONE.get(msg);
        if (cached != null) return cached;
        String plain = msg.getString();
        var pat = namePattern;
        Component out = pat != null && pat.matcher(plain).find() ? rebuild(msg, map) : msg;
        if (DONE.size() > 2048) DONE.clear();
        DONE.put(msg, out);
        return out;
    }

    private static Component rebuild(Component c, Map<String, String> map) {
        MutableComponent out;
        var contents = c.getContents();
        if (contents instanceof PlainTextContents p) {
            out = Component.literal(swap(p.text(), map));
        } else if (contents instanceof TranslatableContents t) {
            Object[] args = t.getArgs().clone();
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Component ac) args[i] = rebuild(ac, map);
                else if (args[i] instanceof String s) args[i] = swap(s, map);
            }
            out = Component.translatableWithFallback(t.getKey(), t.getFallback(), args);
        } else {
            out = MutableComponent.create(contents);
        }
        out.setStyle(c.getStyle());
        for (Component sib : c.getSiblings()) out.append(rebuild(sib, map));
        return out;
    }

    private static String swap(String s, Map<String, String> map) {
        var pat = namePattern;
        if (pat == null || s == null) return s;
        var m = pat.matcher(s);
        if (!m.find()) return s;
        StringBuilder sb = new StringBuilder();
        do { m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(map.getOrDefault(m.group(), m.group()))); } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    /** Returns the skin with the spoofed body texture / cape, or null to leave it alone. */
    public static PlayerSkin spoofSkin(UUID real, PlayerSkin orig, boolean model) {
        if (orig == null) return null;
        PlayerSkin out = null;
        out = spoofBody(real, orig);
        // Cape Spoof: your own cape (and elytra, like a real cape) on the player model
        if (model && SpoofService.capeOn()) {
            var mc = Minecraft.getInstance();
            String cp = SpoofRegistry.capePath();
            if (cp != null && mc.player != null && real.equals(mc.player.getUUID())) {
                Identifier loc = Identifier.tryParse(cp);
                if (loc != null && textureReady(loc)) {
                    PlayerSkin base = out != null ? out : orig;
                    var cape = new ClientAsset.ResourceTexture(loc, loc);
                    out = new PlayerSkin(base.body(), cape, cape, base.model(), base.secure());
                } else if (loc != null) repairCapeLater(cp);
            }
        }
        return out;
    }

    private static long capeRepairAt;
    private static void repairCapeLater(String path) {
        long now = System.currentTimeMillis();
        if (now - capeRepairAt < 2000) return;
        capeRepairAt = now;
        PlatformExtImpl.repairCape(path);
    }

    private static PlayerSkin spoofBody(UUID real, PlayerSkin orig) {
        UUID src = SpoofRegistry.skinSource(real);
        if (src == null) return null;
        String path = SpoofRegistry.cachedSkinPath(src);
        if (path == null) return null;
        Identifier loc = Identifier.tryParse(path);
        if (loc == null) return null;
        // A skin pointed at a texture that isn't loaded draws the missing texture, and its face
        // corner is black: that was the black head. Not there (yet / any more) -> real skin for now,
        // and our own downloaded skins get registered again.
        if (!textureReady(loc)) { dev.zalard.platform.Platforms.get().repairSkinTexture(src, path); return null; }
        // id + explicit texture path, so the registered dynamic texture is used as-is.
        // Arms follow the SOURCE skin (slim/wide), otherwise an Alex skin on a Steve model looks broken.
        Boolean slim = SpoofRegistry.isSlim(src);
        var armModel = slim == null ? orig.model() : pickModel(orig.model(), slim ? "SLIM" : "WIDE");
        return new PlayerSkin(new ClientAsset.ResourceTexture(loc, loc), orig.cape(), orig.elytra(), armModel, orig.secure());
    }

    private static boolean textureReady(Identifier loc) {
        try {
            var tm = Minecraft.getInstance().getTextureManager();
            var t = ((dev.zalard.adapter.mixinopt.TextureManagerAccessor) tm).zalard$byPath().get(loc);
            return t != null && t.getTexture() != null && !t.getTexture().isClosed();
        } catch (Throwable e) {
            return true;                                    // accessor missing: old behaviour
        }
    }

    /** Same enum constant by name, without naming the enum class (it moved between versions). */
    private static <E extends Enum<E>> E pickModel(E current, String name) {
        if (current == null) return null;
        for (E c : current.getDeclaringClass().getEnumConstants()) if (c.name().equals(name)) return c;
        return current;
    }
}
