package dev.zalard.nativegui;

import dev.zalard.gui.ZalardScreen;
import net.minecraft.client.Minecraft;

import java.nio.ByteBuffer;

/**
 * Entry point for drawing the native GUI/overlay (called from WindowMixin before every buffer swap).
 * This class owns the backend-independent frame-prep — mouse position, frame delta, and the native
 * {@code frame()}/{@code vertexCount()}/{@code beginOverlay()} calls — then hands the finished per-frame
 * vertex bytes to a {@link GuiRenderBackend} that puts them on screen.
 *
 * Backend choice (see {@link GuiRenderBackend}):
 *  - PRIMARY: {@link PipelineGuiRenderBackend}, routed through Minecraft's Blaze3D GPU abstraction, which
 *    sits above both the OpenGL and the 26.x Vulkan backend. One path for the whole 1.21.11 → 26.2 span,
 *    and the only path that works under Vulkan.
 *  - FALLBACK: {@link GlGuiRenderBackend}, the original raw-OpenGL path. If the pipeline backend can't
 *    initialize (e.g. an API-shape mismatch on a version we couldn't test), we fall back to GL once — it
 *    still works on every GL-backed Minecraft, and simply can't draw under Vulkan.
 *
 * Set {@code -Dzalard.gui.backend=gl} (or {@code pipeline}) to force a backend for debugging.
 */
public final class NativeRenderer {
    private NativeRenderer() {}

    private static GuiRenderBackend backend;
    private static ByteBuffer vertexView;     // direct view of the native vertex array, made once
    private static int stride;
    private static boolean ready, failed, triedGlFallback;
    private static long lastNanos;

    /** Called from WindowMixin before every buffer swap. */
    public static void renderIfOpen() {
        Minecraft mc = Minecraft.getInstance();
        if (failed) return;
        boolean guiOpen = mc.screen instanceof ZalardScreen;
        if (!guiOpen) lastNanos = 0;
        try { if (!guiOpen && ZalardNative.vertexCount() == 0) return; }   // nothing to draw this frame
        catch (Throwable t) { return; }
        try {
            if (!ready) {
                ZalardNative.load();
                stride = ZalardNative.vertexFloats() * 4;
                vertexView = ZalardNative.vertexBuffer();
                ready = true;
            }

            var win = mc.getWindow();
            int fbW = win.getWidth(), fbH = win.getHeight();
            if (fbW <= 0 || fbH <= 0) return;
            double sx = fbW / (double) Math.max(1, win.getScreenWidth());
            double sy = fbH / (double) Math.max(1, win.getScreenHeight());
            float mouseX = (float) (mc.mouseHandler.xpos() * sx);
            float mouseY = (float) (mc.mouseHandler.ypos() * sy);

            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1f / 60f : (now - lastNanos) / 1e9f;
            lastNanos = now;

            int count;
            if (guiOpen) {
                ZalardNative.mouseMove(mouseX, mouseY);
                count = ZalardNative.frame(fbW, fbH, mouseX, mouseY, dt);   // GUI appended after ESP lines
            } else {
                count = ZalardNative.vertexCount();                         // ESP lines only
            }
            ZalardNative.beginOverlay();                                    // next frame starts empty
            if (count <= 0) return;
            ByteBuffer verts = vertexView;
            verts.clear().limit(count * stride);

            if (backend == null) backend = defaultBackend();
            backend.draw(mc, fbW, fbH, verts, count, stride);
        } catch (Throwable t) {
            // One-time degrade: if the pipeline backend blew up (an API-shape mismatch we couldn't test on
            // this MC version), drop to the proven GL path and try again next frame. If GL also fails — or
            // we're already on GL — disable the overlay for the session rather than spam every frame.
            if (!triedGlFallback && !(backend instanceof GlGuiRenderBackend)) {
                triedGlFallback = true;
                try { if (backend != null) backend.close(); } catch (Throwable ignored) {}
                backend = new GlGuiRenderBackend();
                System.err.println("[Mako] native GUI pipeline backend failed, falling back to GL: " + t);
            } else {
                failed = true;
                System.err.println("[Mako] native GUI renderer disabled: " + t);
                t.printStackTrace();
            }
        }
    }

    private static GuiRenderBackend defaultBackend() {
        String forced = System.getProperty("zalard.gui.backend", "").trim().toLowerCase();
        if (forced.equals("gl")) { triedGlFallback = true; return new GlGuiRenderBackend(); }
        if (forced.equals("pipeline")) return new PipelineGuiRenderBackend();
        //? if >=26 {
        /*// 26.x: renderer-agnostic pipeline backend (works on the GL AND Vulkan renderers). If it can't
        // initialize on this version, renderIfOpen() degrades to the GL backend once.
        return new PipelineGuiRenderBackend();
        *///?} else {
        // pre-26 (e.g. 1.21.11): the game is OpenGL-only, so the raw-GL backend is the correct and only
        // path needed. Pipeline/Vulkan has nothing to run against here.
        triedGlFallback = true;
        return new GlGuiRenderBackend();
        //?}
    }
}
