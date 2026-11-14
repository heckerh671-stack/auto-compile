package dev.zalard.adapter.mixin;

import dev.zalard.platform.Platforms;
import dev.zalard.adapter.PlatformExt2Impl;
//? if <26 {
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
//?}
//? if >=26 {
/*import net.minecraft.client.gui.render.GuiRenderer;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// HUD draw hook. 1.21.11: Gui.render(GuiGraphics, DeltaTracker). 26.2: the HUD was split into
// Hud + GuiRenderer, and the whole-GUI draw is GuiRenderer.render()V (no GuiGraphics in the sig) —
// see MULTIVERSION.md "GuiGraphics note". The 1.21.11 branch is the live/vcs branch; the 26.2 branch
// is Stonecutter-commented until you switch active version, so the current build is unchanged.
//? if <26 {
@Mixin(value = Gui.class, remap = true)
public abstract class InGameHudMixin {

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("TAIL"))
    void mako_renderHud(GuiGraphics g, DeltaTracker tracker, CallbackInfo ci) {
        try {
            var ext = PlatformExt2Impl.INSTANCE;
            if (ext == null) return;
            ext.setGui(g);
            dev.zalard.nat.Z.run(dev.zalard.nat.Z.RENDER_HUD);   // HUD, drawn by the native overlay
            ext.setGui(null);
        } catch (Throwable ignored) {}
    }
}
//?}
//? if >=26 {
/*@Mixin(value = GuiRenderer.class, remap = true)
public abstract class InGameHudMixin {

    @Inject(method = "render()V", at = @At("TAIL"))
    void mako_renderHud(CallbackInfo ci) {
        try {
            var ext = PlatformExt2Impl.INSTANCE;
            if (ext == null) return;
            // TODO(26.2): GuiRenderer.render()V has no GuiGraphics param. Wire the frame's
            // GuiGraphics source here (the per-element Hud draw path still carries one — option A
            // in MULTIVERSION.md), then ext.setGui(<that GuiGraphics>). Until wired, HUD native
            // overlay has no canvas on 26.2.
            // ext.setGui(<guiGraphics>);
            dev.zalard.nat.Z.run(dev.zalard.nat.Z.RENDER_HUD);
            ext.setGui(null);
        } catch (Throwable ignored) {}
    }
}
*///?}
