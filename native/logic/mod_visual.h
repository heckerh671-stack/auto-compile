// Zalard native logic: Xray, No Overlay, Visuals. These hold state only; the actual render changes
// happen in the Java mixins, which read this state back through Z.q() each frame. Keeping the toggles
// here means they live in the same click GUI / config as everything else and stay obfuscated.
#pragma once

namespace z {

// ================================================================ Xray
// See-through terrain: the XrayBlockMixin hides non-ore block faces and shows ores while this is on.
// (The ore set lives in the mixin; the native side only needs the toggle. Toggling usually needs a
// chunk reload, F3+A, for vanilla to rebuild the chunk meshes.)
namespace xray {
    static int M = -1;
    static void reg() { M = mod(XS("Xray"), XS("Render")); }
}

// ================================================================ No Overlay
// Removes the full-screen gunk vanilla paints over your view. Each toggle is a bit the mixins read via
// Z.q(Q_NO_OVERLAY); the mixins cancel the matching render when its bit is set.
namespace nooverlay {
    static int M = -1;
    static SBool fire, water, pumpkin, portal, powderSnow, hurtTilt, blindness;
    enum { B_FIRE = 1, B_WATER = 2, B_PUMPKIN = 4, B_PORTAL = 8, B_POWDER = 16, B_HURT = 32, B_BLIND = 64 };
    static int bits() {
        if (M < 0 || !isOn(M)) return 0;
        int b = 0;
        if (fire())       b |= B_FIRE;
        if (water())      b |= B_WATER;
        if (pumpkin())    b |= B_PUMPKIN;
        if (portal())     b |= B_PORTAL;
        if (powderSnow()) b |= B_POWDER;
        if (hurtTilt())   b |= B_HURT;
        if (blindness())  b |= B_BLIND;
        return b;
    }
    static void reg() {
        M = mod(XS("No Overlay"), XS("Render"));
        fire       = sBool(XS("Fire"), true);
        water      = sBool(XS("Water"), true);
        pumpkin    = sBool(XS("Pumpkin"), true);
        portal     = sBool(XS("Portal"), true);
        powderSnow = sBool(XS("Powder Snow"), true);
        hurtTilt   = sBool(XS("Hurt Tilt"), false);
        blindness  = sBool(XS("Blindness"), false);
    }
}

// ================================================================ Visuals
// Sky color changer + fullbright + no fog. The mixins read the chosen sky ARGB via Z.q(Q_SKY_COLOR)
// (0 = leave vanilla) and the flag bits via Z.q(Q_VISUAL_FLAGS).
namespace visuals {
    static int M = -1;
    static SBool skyOn; static SEnum skyColor; static SBool fullbright, noFog;
    static const u32 SKY[7] = { 0xFF0B0B14u, 0xFF2A0D3Au, 0xFF0D2A3Au, 0xFF3A0D0Du, 0xFF0D3A1Au, 0xFFB46A00u, 0xFF000000u };
    enum { F_FULLBRIGHT = 1, F_NOFOG = 2 };
    static int skyARGB() {
        if (M < 0 || !isOn(M) || !skyOn()) return 0;   // 0 -> mixin leaves the vanilla sky alone
        int i = skyColor.idx();
        return (int)SKY[i < 0 ? 0 : i > 6 ? 6 : i];
    }
    static int flags() {
        if (M < 0 || !isOn(M)) return 0;
        return (fullbright() ? F_FULLBRIGHT : 0) | (noFog() ? F_NOFOG : 0);
    }
    static void reg() {
        M = mod(XS("Visuals"), XS("Render"));
        skyOn = sBool(XS("Sky Color"), false);
        const char* COLS[] = { XS("Midnight"), XS("Purple"), XS("Cyan"), XS("Crimson"), XS("Green"), XS("Amber"), XS("Black") };
        skyColor  = sEnum(XS("Sky"), 0, COLS, 7);
        fullbright = sBool(XS("Fullbright"), false);
        noFog      = sBool(XS("No Fog"), false);
    }
}

// ================================================================ Hitboxes
// Forces entity hitbox outlines on (no F3+B needed), with an optional Expand that inflates the drawn
// box — the HitboxMixin reads Z.q(Q_HITBOX): 0 = off, else 1 + expand*1000.
namespace hitbox {
    static int M = -1;
    static SNum expand;
    static int q() {
        if (M < 0 || !isOn(M)) return 0;
        int e = iround(expand() * 1000.f);
        return 1 + (e < 0 ? 0 : e);
    }
    static void reg() {
        M = mod(XS("Hitboxes"), XS("Render"));
        expand = sNum(XS("Expand"), 0, 0, 0.6f, "blocks");
    }
}

// ================================================================ Camera Clip
// Lets the third-person (F5) camera pass through blocks instead of being shoved up against the wall.
// The CameraMixin reads Q_CAM_NOCLIP and skips the collision clip on the camera zoom.
namespace camclip {
    static int M = -1;
    static void reg() { M = mod(XS("Camera Clip"), XS("Render")); }
}

// ================================================================ Drip (self cosmetic)
// Client-only fake armor / held item: renders a different item on YOUR own model (only you see it).
// Each slot's item id is pushed to Java (T_SET_DRIP) when it changes; the drip render mixins swap the
// rendered ItemStack for your fake one. Blank = show the real item.
namespace drip {
    static int M = -1;
    static SText helm, chest, legs, boots, main, off;
    static char last[6][64];
    static void pushSlot(int i, const char* want) {
        if (eq(want, last[i])) return;
        char s[2] = { (char)('0' + i), 0 };
        T(T_SET_DRIP, s, want);
        copy(last[i], want, 64);
    }
    static void tick() {
        const char* vals[6] = { helm(), chest(), legs(), boots(), main(), off() };
        for (int i = 0; i < 6; i++) pushSlot(i, vals[i]);
    }
    static void disable() { for (int i = 0; i < 6; i++) pushSlot(i, ""); }   // clear -> real items render
    static void reg() {
        M = mod(XS("Drip"), XS("Render"));
        helm  = sText(XS("Helmet"), "", "item id, blank = real");
        chest = sText(XS("Chestplate"), "", "item id, blank = real");
        legs  = sText(XS("Leggings"), "", "item id, blank = real");
        boots = sText(XS("Boots"), "", "item id, blank = real");
        main  = sText(XS("Main Hand"), "", "item id, blank = real");
        off   = sText(XS("Off Hand"), "", "item id, blank = real");
        LG[M].tick = tick; LG[M].disable = disable;
    }
}

// ================================================================ Spotify
// Now-playing on your HUD. Enabling the module tells Java to connect (T_SPOTIFY_CONNECT): it reconnects
// silently if a refresh token is cached, otherwise opens Spotify's consent page in the browser. The HUD
// line is pulled from Java each frame (R_SPOTIFY_NOW). See SpotifyService.java for the one-time Client ID.
namespace spotify {
    static int M = -1;
    static void onEnable() { T(T_SPOTIFY_CONNECT, ""); }
    static void hud() {
        char now[256];
        if (!R(R_SPOTIFY_NOW, nullptr, now, sizeof now) || !now[0]) return;
        float S_ = designS();
        float pad = 6 * S_, h = 20 * S_, dot = 8 * S_;
        float tw = fbTextW(FACE_PIX, now, 14 * S_);
        float x = 8 * S_, y = (float)SF[SF_FB_H] - h - 8 * S_;
        fbRect(x, y, tw + pad * 2 + 14 * S_, h, 4 * S_, 0xCC0E1014u);
        fbRect(x + pad, y + (h - dot) * 0.5f, dot, dot, 2 * S_, 0xFF1DB954u);   // Spotify-green dot
        fbText(FACE_PIX, now, x + pad + 14 * S_, y + h * 0.5f + 5 * S_, 14 * S_, 0xFFFFFFFFu);
    }
    static void reg() {
        M = mod(XS("Spotify"), XS("Render"));
        LG[M].enable = onEnable; LG[M].hud = hud;
    }
}

} // namespace z
