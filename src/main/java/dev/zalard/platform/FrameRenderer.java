package dev.zalard.platform;

// per-frame draw surface for esp.
public interface FrameRenderer {

    /** 3D-space wireframe box in world coords (post-camera). */
    void lineBox(double minX, double minY, double minZ,
                 double maxX, double maxY, double maxZ,
                 int argb);

    /** 3D line between two points. */
    void line(McPlatform.Vec3d a, McPlatform.Vec3d b, int argb);
    /** Width in pixels for the lines drawn after this call. */
    default void setLineWidth(float px) {}

    /** Filled quad in world space (used for TargetHUD plates). */
    void quad(McPlatform.Vec3d a, McPlatform.Vec3d b,
              McPlatform.Vec3d c, McPlatform.Vec3d d, int argb);

    /** Horizontal ring at a world point (used for ChinaHat base, JumpCircle). */
    void ring(McPlatform.Vec3d center, double radius, int segments, int argb);

    /** 2D screen-space text (HUD elements). */
    void text(String s, int x, int y, int argb);
    int  textWidth(String s);

    /** 2D screen-space rect for GUI. */
    void rect2d(int x, int y, int w, int h, int argb);

    // ---- screen-space ESP drawing (framebuffer pixels, drawn by the native overlay) ----
    /** Screen rectangle {x0, y0, x1, y1} that a world box covers, or null when it's behind you / off screen. */
    default float[] project(double[] box) { return null; }
    /** Filled rounded rectangle in pixels. */
    default void rectPx(float x, float y, float w, float h, float radius, int argb) {}
    /** Text in pixels (GUI font). align: 0 left, 1 centered, 2 right. y = vertical center. */
    default void textPx(String s, float x, float y, float size, int argb, int align) {}
    default float textWidthPx(String s, float size) { return s.length() * size * 0.55f; }
    /** Screen height in pixels (to scale ESP). */
    default float screenHeightPx() { return 1080f; }
    /** Screen pixel position of a world point this frame, or null if it's behind the camera. */
    default float[] projectPoint(double x, double y, double z) { return null; }
}
