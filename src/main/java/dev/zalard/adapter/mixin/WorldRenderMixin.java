package dev.zalard.adapter.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.zalard.adapter.FrameRendererImpl;
import dev.zalard.platform.Platforms;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ESP pass. HEAD: capture this frame's camera position and the real view +
 * projection matrices (args 5 and 7). TAIL: run module render(), which projects
 * its boxes through those matrices into the native screen overlay.
 */
@Mixin(LevelRenderer.class)
public abstract class WorldRenderMixin {

    private static final String RENDER_LEVEL =
            "renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V";

    @Inject(method = RENDER_LEVEL, at = @At("HEAD"))
    private void zalard$captureMatrices(GraphicsResourceAllocator alloc, DeltaTracker delta, boolean outline, Camera camera,
                                      Matrix4f view, Matrix4f basicProjection, Matrix4f projection,
                                      GpuBufferSlice fog, Vector4f fogColor, boolean sky, CallbackInfo ci) {
        try {
            var p = camera.position();
            FrameRendererImpl.captureFrame(view, projection, p.x, p.y, p.z);
        } catch (Throwable ignored) {}
    }

    @Inject(method = RENDER_LEVEL, at = @At("TAIL"))
    private void zalard$esp(CallbackInfo ci) {
        try {
            FrameRendererImpl.setCollecting(true);
            dev.zalard.nat.Z.run(dev.zalard.nat.Z.RENDER_WORLD);
        } catch (Throwable ignored) {
        } finally {
            FrameRendererImpl.setCollecting(false);
        }
    }
}
