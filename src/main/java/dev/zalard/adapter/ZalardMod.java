// Zalard - Copyright (c) 2026 weksom. All rights reserved. Proprietary; see LICENSE.
package dev.zalard.adapter;

import dev.zalard.nat.Z;
import dev.zalard.nativegui.ZalardNative;
import dev.zalard.platform.Platforms;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Fabric entrypoint. Everything Zalard does (modules, settings, configs, friends, keybinds, GUI, ESP,
 * HUD) runs in the native library; this only loads it, installs the Minecraft adapter it calls through
 * (Bridge -> PlatformImpl) and forwards the client tick.
 *
 * Dependencies are kept to Fabric Loader + Mixin only — NO Fabric API — so the mod loads in any
 * Fabric-based environment (vanilla Fabric, Dawn, Lunar Client's Fabric support, Prism/MultiMC, etc.)
 * without requiring Fabric API to be present or a matching version. The per-tick call comes from
 * MinecraftTickMixin (Minecraft.tick HEAD, same point Fabric API's START_CLIENT_TICK fired), and the
 * config save is a JVM shutdown hook instead of a lifecycle event.
 */
public final class ZalardMod implements ClientModInitializer {

    private static boolean nativeOk;

    @Override
    public void onInitializeClient() {
        Platforms.install(new PlatformImpl());
        Bridge.zalardDir = FabricLoader.getInstance().getGameDir().resolve("mako");
        try {
            ZalardNative.load();
            Z.boot(System.currentTimeMillis(), System.nanoTime());
            nativeOk = true;
            // Periodic re-authorization: if the account is banned mid-session, the next heartbeat revokes
            // it and the client disables itself (doesn't wait for a relaunch). Also feeds the "online now"
            // usage count. No-op on a dev build with -Dzalard.devkey and no server session.
            dev.zalard.auth.Auth.startHeartbeat(() -> {
                nativeOk = false;
                System.err.println("[Zalard] session revoked by server; client disabled");
            });
        } catch (Throwable t) {
            System.err.println("[Zalard] native library unavailable, client disabled: " + t);
        }
        // Auto-update (optional): if a release URL + publisher key are configured, check for a newer SIGNED
        // release over HTTPS, verify it, and stage it under <gamedir>/mako/versions/ for the next launch.
        // No-op if not configured; errors are swallowed so an update check never blocks startup.
        try { dev.zalard.update.Updater.startBackgroundCheck(Bridge.zalardDir.resolve("versions")); }
        catch (Throwable ignored) {}
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (nativeOk) try { Z.save(); } catch (Throwable ignored) {}
        }, "zalard-save"));
    }

    /**
     * Driven from MinecraftTickMixin at the START of each client tick — everything a module does (clicks,
     * key presses, slot keys) is read by vanilla this tick, before the movement packet, like your own input.
     */
    public static void tick(Minecraft mc) {
        if (!nativeOk) return;
        // advance the hand-back fade at the START of the tick, so the yaw your movement is computed with
        // this tick is the same yaw that goes out in this tick's packet (Grim simulation)
        if (mc.player != null) RotationBridgeImpl.tickFade(mc.player.getYRot(), mc.player.getXRot());
        else RotationBridgeImpl.tickFade();
        Z.run(Z.TICK);
        Lag.drive(mc);             // keep the inbound-packet-hold handler in step with the native toggle
        ChargeStack.drive(mc);     // wind-launch: hold your explosion-knockback packets, release to launch
        SpoofService.tick();
        MoveFix.tickKeys();        // Silent Aim movement fix: press the keys vanilla needs for the head yaw
        Freecam.tick();            // last: while flying the camera, your player gets no movement keys
    }
}
