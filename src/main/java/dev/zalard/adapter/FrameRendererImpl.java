package dev.zalard.adapter;

import dev.zalard.nativegui.ZalardNative;
import dev.zalard.platform.FrameRenderer;
import dev.zalard.platform.McPlatform.Vec3d;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * ESP renderer. Projects world-space lines to the screen with the frame's real
 * view/projection matrices (captured at the start of LevelRenderer.renderLevel)
 * and hands them to the native overlay, which draws them anti-aliased on top of
 * the world — so ESP shows through walls and doesn't depend on 1.21.11's line
 * render types (which needed a LineWidth element and can't skip the depth test).
 */
public final class FrameRendererImpl implements FrameRenderer {

    // ---- per-frame camera state, captured at renderLevel HEAD
    private static final Matrix4f VIEW_PROJ = new Matrix4f();
    private static double camX, camY, camZ;
    private static boolean haveFrame;
    private static boolean collecting;

    public static float lineWidth = 2f;
    @Override public void setLineWidth(float px) { lineWidth = Math.max(0.5f, px); }

    /** Called from WorldRenderMixin at renderLevel HEAD. */
    public static void captureFrame(Matrix4f view, Matrix4f projection, double cx, double cy, double cz) {
        VIEW_PROJ.set(projection).mul(view);
        camX = cx; camY = cy; camZ = cz;
        haveFrame = true;
    }
    /** Only the world pass may emit ESP lines (the HUD pass also runs module render()). */
    public static void setCollecting(boolean on) { collecting = on; }

    private final Vector4f a4 = new Vector4f(), b4 = new Vector4f();

    private boolean ready() { return collecting && haveFrame; }

    private void toClip(double x, double y, double z, Vector4f out) {
        out.set((float) (x - camX), (float) (y - camY), (float) (z - camZ), 1f);
        VIEW_PROJ.transform(out);
    }

    /** Projects one world-space segment, clipping it at the near plane, and queues it. */
    private void segment(double x1, double y1, double z1, double x2, double y2, double z2, int argb) {
        toClip(x1, y1, z1, a4);
        toClip(x2, y2, z2, b4);
        final float eps = 0.05f;
        boolean aIn = a4.w > eps, bIn = b4.w > eps;
        if (!aIn && !bIn) return;                          // fully behind the camera
        if (!aIn) { float t = (eps - a4.w) / (b4.w - a4.w); lerp(a4, b4, t, a4); }
        else if (!bIn) { float t = (eps - a4.w) / (b4.w - a4.w); lerp(a4, b4, t, b4); }
        var win = Minecraft.getInstance().getWindow();
        float w = win.getWidth(), h = win.getHeight();
        float ax = (a4.x / a4.w * 0.5f + 0.5f) * w, ay = (0.5f - a4.y / a4.w * 0.5f) * h;
        float bx = (b4.x / b4.w * 0.5f + 0.5f) * w, by = (0.5f - b4.y / b4.w * 0.5f) * h;
        // skip lines entirely off-screen
        if ((ax < 0 && bx < 0) || (ay < 0 && by < 0) || (ax > w && bx > w) || (ay > h && by > h)) return;
        float px = lineWidth * Math.max(1f, h / 1080f);
        try { ZalardNative.espLine(ax, ay, bx, by, px, argb); } catch (Throwable ignored) {}
    }

    private static void lerp(Vector4f a, Vector4f b, float t, Vector4f out) {
        out.set(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t, a.w + (b.w - a.w) * t);
    }

    @Override
    public void lineBox(double x0, double y0, double z0, double x1, double y1, double z1, int argb) {
        if (!ready()) return;
        // bottom
        segment(x0, y0, z0, x1, y0, z0, argb); segment(x1, y0, z0, x1, y0, z1, argb);
        segment(x1, y0, z1, x0, y0, z1, argb); segment(x0, y0, z1, x0, y0, z0, argb);
        // top
        segment(x0, y1, z0, x1, y1, z0, argb); segment(x1, y1, z0, x1, y1, z1, argb);
        segment(x1, y1, z1, x0, y1, z1, argb); segment(x0, y1, z1, x0, y1, z0, argb);
        // verticals
        segment(x0, y0, z0, x0, y1, z0, argb); segment(x1, y0, z0, x1, y1, z0, argb);
        segment(x1, y0, z1, x1, y1, z1, argb); segment(x0, y0, z1, x0, y1, z1, argb);
    }

    @Override public void line(Vec3d a, Vec3d b, int argb) {
        if (ready()) segment(a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), argb);
    }

    @Override public void quad(Vec3d a, Vec3d b, Vec3d c, Vec3d d, int argb) {
        if (!ready()) return;
        line(a, b, argb); line(b, c, argb); line(c, d, argb); line(d, a, argb);
    }

    @Override public void ring(Vec3d c, double r, int segments, int argb) {
        if (!ready()) return;
        int n = Math.max(8, segments);
        for (int i = 0; i < n; i++) {
            double t0 = Math.PI * 2 * i / n, t1 = Math.PI * 2 * (i + 1) / n;
            segment(c.x() + Math.cos(t0) * r, c.y(), c.z() + Math.sin(t0) * r,
                    c.x() + Math.cos(t1) * r, c.y(), c.z() + Math.sin(t1) * r, argb);
        }
    }

    @Override public float[] project(double[] b) {
        if (!ready()) return null;
        var win = Minecraft.getInstance().getWindow();
        float w = win.getWidth(), h = win.getHeight();
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            toClip((i & 1) == 0 ? b[0] : b[3], (i & 2) == 0 ? b[1] : b[4], (i & 4) == 0 ? b[2] : b[5], a4);
            if (a4.w <= 0.05f) return null;                       // a corner behind the camera: skip
            float sx = (a4.x / a4.w * 0.5f + 0.5f) * w, sy = (0.5f - a4.y / a4.w * 0.5f) * h;
            x0 = Math.min(x0, sx); y0 = Math.min(y0, sy); x1 = Math.max(x1, sx); y1 = Math.max(y1, sy);
        }
        if (x1 < 0 || y1 < 0 || x0 > w || y0 > h) return null;
        return new float[]{ x0, y0, x1, y1 };
    }
    @Override public float[] projectPoint(double x, double y, double z) {
        if (!ready()) return null;
        var win = Minecraft.getInstance().getWindow();
        toClip(x, y, z, a4);
        if (a4.w <= 0.05f) return null;
        return new float[]{ (a4.x / a4.w * 0.5f + 0.5f) * win.getWidth(), (0.5f - a4.y / a4.w * 0.5f) * win.getHeight() };
    }
    @Override public void rectPx(float x, float y, float w, float h, float r, int argb) {
        if (ready()) try { ZalardNative.espRect(x, y, w, h, r, argb); } catch (Throwable ignored) {}
    }
    @Override public void textPx(String s, float x, float y, float size, int argb, int align) {
        if (ready()) try { ZalardNative.espText(s, x, y, size, argb, align); } catch (Throwable ignored) {}
    }
    @Override public float textWidthPx(String s, float size) {
        try { return ZalardNative.espTextWidth(s, size); } catch (Throwable t) { return s.length() * size * 0.55f; }
    }
    @Override public float screenHeightPx() { return Minecraft.getInstance().getWindow().getHeight(); }

    @Override public void text(String s, int x, int y, int argb) {}
    @Override public int textWidth(String s) { var mc = Minecraft.getInstance(); return mc.font == null ? 0 : mc.font.width(s); }
    @Override public void rect2d(int x, int y, int w, int h, int argb) {}
}
