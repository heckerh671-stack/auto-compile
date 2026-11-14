package dev.zalard.nativegui;

import net.minecraft.client.Minecraft;

import java.nio.ByteBuffer;

/**
 * Draws the native GUI/overlay vertex list on top of the frame. There are two implementations:
 *
 *  - {@link GlGuiRenderBackend}: a self-contained raw-OpenGL pipeline. Works on any GL-backed Minecraft
 *    (1.21.x and 26.x while the player is on the OpenGL renderer). It CANNOT draw under 26.x's Vulkan
 *    renderer, because there is no GL context to issue its calls against.
 *  - {@link PipelineGuiRenderBackend}: routes through Minecraft's Blaze3D GPU abstraction
 *    ({@code RenderPipeline}/{@code GpuDevice}), which sits above both the GL and the Vulkan backend.
 *    This is the renderer-agnostic path and the one that gives Vulkan support on 26.x. It needs the
 *    Blaze3D GPU API that exists from the 1.21.5/1.21.6 rework onward (so it covers 1.21.11 → 26.2).
 *
 * {@link NativeRenderer} owns the frame-prep (mouse, dt, native frame() call) and picks a backend; the
 * backend is handed the finished per-frame vertex bytes and only has to put them on screen.
 */
public interface GuiRenderBackend {

    /**
     * Draw {@code count} vertices (each {@code stride} bytes, in the native GUI vertex layout) as a
     * top-most screen-space overlay for this frame. Implementations allocate their GPU resources lazily
     * on the first call, and MUST leave any global GPU state they touch exactly as they found it, so
     * Minecraft's own renderer is undisturbed. Throwing signals the backend is unusable for this run —
     * the caller may fall back to another backend or disable the overlay.
     *
     * @param verts a buffer positioned at 0 with limit = {@code count * stride}; for the GL backend this
     *              is a direct view straight into native memory, so it must be consumed synchronously.
     */
    void draw(Minecraft mc, int fbW, int fbH, ByteBuffer verts, int count, int stride) throws Throwable;

    /** Release GPU resources. Default no-op; called if a backend is swapped out at runtime. */
    default void close() {}
}
