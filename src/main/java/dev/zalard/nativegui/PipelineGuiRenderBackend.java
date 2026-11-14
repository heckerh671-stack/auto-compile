package dev.zalard.nativegui;

import net.minecraft.client.Minecraft;

import java.nio.ByteBuffer;

/**
 * Vulkan-capable GUI backend: draws the native vertex list through Minecraft's Blaze3D GPU abstraction
 * ({@code RenderPipeline} + {@code GpuDevice}/{@code CommandEncoder}/{@code RenderPass}), which sits above
 * BOTH the OpenGL and the 26.x Vulkan backend. One path for the whole 1.21.11 → 26.2 span, and the only
 * path that draws under the Vulkan renderer.
 *
 * STATUS — like the 26.2 mixin branches in MULTIVERSION.md, the real implementation below is written from
 * the Blaze3D GPU API reference and MUST be compiled + tested in your environment: I can't pull the MC
 * 26.2 (or 1.21.11) dependency set or run a Vulkan snapshot from here. The implementation therefore lives
 * entirely inside a Stonecutter {@code //? if >=26} branch, so the live 1.21.11 tree is unaffected (it is
 * GL-only and never selects this backend — NativeRenderer picks GlGuiRenderBackend below 26). When you
 * switch Stonecutter to 26.2, the branch below goes live; every spot that needs the real 26.2 classes to
 * confirm an API shape is marked {@code TODO(26)}.
 *
 * Why the version gate and not "use pipeline everywhere": the Blaze3D GPU API exists from the 1.21.5/1.21.6
 * rework, so it is present on 1.21.11 too — but its method shapes drifted across that range (see the
 * TODO(26) notes), and 1.21.11 has no Vulkan backend to need it. Keeping GL live below 26 preserves the
 * MULTIVERSION guarantee that the un-chiseled tree is byte-for-byte today's working 1.21.11 build.
 */
public final class PipelineGuiRenderBackend implements GuiRenderBackend {

    //? if >=26 {
    /*
    // ---- imports (declared fully-qualified below so the <26 stub needs none) are, for reference:
    //   com.mojang.blaze3d.pipeline.RenderPipeline, .pipeline.RenderTarget
    //   com.mojang.blaze3d.systems.{RenderSystem, RenderPass, GpuDevice, CommandEncoder}
    //   com.mojang.blaze3d.buffers.{GpuBuffer, GpuBufferSlice, Std140Builder}
    //   com.mojang.blaze3d.textures.{GpuTexture, GpuTextureView, TextureFormat, FilterMode, AddressMode}
    //   com.mojang.blaze3d.vertex.{VertexFormat, VertexFormatElement}
    //   net.minecraft.client.renderer.RenderPipelines
    //   net.minecraft.resources.ResourceLocation

    // Six generic float attributes matching the native vertex layout (pos2, uv2, p4, col4, clip4, kind1 =
    // 17 floats / 68 bytes), same order the GL path binds to attribute locations 0..5.
    // TODO(26): confirm VertexFormatElement.register(id,index,Type.FLOAT,Usage.GENERIC,count) still exists
    //           and that the element ids/usages are accepted by the pipeline shader linker. If GENERIC is
    //           rejected, map aPos->POSITION, aCol->COLOR and the rest to GENERIC/UV elements.
    private static final com.mojang.blaze3d.vertex.VertexFormatElement E_POS  =
        com.mojang.blaze3d.vertex.VertexFormatElement.register(0, 0, com.mojang.blaze3d.vertex.VertexFormatElement.Type.FLOAT, com.mojang.blaze3d.vertex.VertexFormatElement.Usage.GENERIC, 2);
    private static final com.mojang.blaze3d.vertex.VertexFormatElement E_UV   =
        com.mojang.blaze3d.vertex.VertexFormatElement.register(1, 0, com.mojang.blaze3d.vertex.VertexFormatElement.Type.FLOAT, com.mojang.blaze3d.vertex.VertexFormatElement.Usage.GENERIC, 2);
    private static final com.mojang.blaze3d.vertex.VertexFormatElement E_P    =
        com.mojang.blaze3d.vertex.VertexFormatElement.register(2, 0, com.mojang.blaze3d.vertex.VertexFormatElement.Type.FLOAT, com.mojang.blaze3d.vertex.VertexFormatElement.Usage.GENERIC, 4);
    private static final com.mojang.blaze3d.vertex.VertexFormatElement E_COL  =
        com.mojang.blaze3d.vertex.VertexFormatElement.register(3, 0, com.mojang.blaze3d.vertex.VertexFormatElement.Type.FLOAT, com.mojang.blaze3d.vertex.VertexFormatElement.Usage.GENERIC, 4);
    private static final com.mojang.blaze3d.vertex.VertexFormatElement E_CLIP =
        com.mojang.blaze3d.vertex.VertexFormatElement.register(4, 0, com.mojang.blaze3d.vertex.VertexFormatElement.Type.FLOAT, com.mojang.blaze3d.vertex.VertexFormatElement.Usage.GENERIC, 4);
    private static final com.mojang.blaze3d.vertex.VertexFormatElement E_KIND =
        com.mojang.blaze3d.vertex.VertexFormatElement.register(5, 0, com.mojang.blaze3d.vertex.VertexFormatElement.Type.FLOAT, com.mojang.blaze3d.vertex.VertexFormatElement.Usage.GENERIC, 1);

    // TODO(26): VertexFormat.builder() element-name API — names must match the shader `in` names.
    private static final com.mojang.blaze3d.vertex.VertexFormat GUI_FORMAT =
        com.mojang.blaze3d.vertex.VertexFormat.builder()
            .add("aPos", E_POS).add("aUV", E_UV).add("aP", E_P)
            .add("aCol", E_COL).add("aClip", E_CLIP).add("aKind", E_KIND)
            .build();

    // Pipeline: custom shaders in assets/zalard/shaders/core/gui.{vsh,fsh}, one UBO (GuiUniforms{vec2 uView}),
    // one sampler (Sampler0 = MSDF atlas), straight-alpha blend, no depth test/write, no cull, draws triangles.
    // TODO(26): confirm the builder method names below. Across the 1.21.6→26 range these have been, variously,
    //   withVertexShader/withFragmentShader(ResourceLocation), withVertexFormat(VertexFormat, VertexFormat.Mode),
    //   withUniform(String, UniformType), withSampler(String), withBlend(BlendFunction), withDepthTestFunction,
    //   withCull(boolean), withColorWrite(boolean). Adjust to the 26.2 signatures the compiler reports.
    private static final com.mojang.blaze3d.pipeline.RenderPipeline PIPELINE =
        net.minecraft.client.renderer.RenderPipelines.register(
            com.mojang.blaze3d.pipeline.RenderPipeline.builder()
                .withLocation(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("zalard", "pipeline/gui"))
                .withVertexShader(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("zalard", "core/gui"))
                .withFragmentShader(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("zalard", "core/gui"))
                .withVertexFormat(GUI_FORMAT, com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES)
                .withUniform("GuiUniforms", com.mojang.blaze3d.pipeline.RenderPipeline.UniformType.UNIFORM_BUFFER)
                .withSampler("Sampler0")
                // straight-alpha: rgb = src.a*src + (1-src.a)*dst ; a = src.a + (1-src.a)*dst.a
                .withBlend(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)   // TODO(26): if TRANSLUCENT isn't (SRC_ALPHA,ONE_MINUS_SRC_ALPHA / ONE,ONE_MINUS_SRC_ALPHA), build a custom BlendFunction with those four factors
                .withDepthTestFunction(com.mojang.blaze3d.pipeline.DepthTestFunction.NO_DEPTH_TEST)
                .withCull(false)
                .build());

    private com.mojang.blaze3d.buffers.GpuBuffer vbo;
    private int vboCap;
    private com.mojang.blaze3d.buffers.GpuBuffer ubo;
    private com.mojang.blaze3d.textures.GpuTexture atlasTex;
    private com.mojang.blaze3d.textures.GpuTextureView atlasView;
    private boolean ready;

    @Override
    public void draw(Minecraft mc, int fbW, int fbH, ByteBuffer verts, int count, int stride) {
        com.mojang.blaze3d.systems.GpuDevice device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
        if (!ready) setup(device);

        int need = count * stride;
        // --- grow + upload this frame's vertices
        if (vbo == null || vboCap < need) {
            if (vbo != null) vbo.close();
            // TODO(26): createBuffer(Supplier<String> label, int usage, int size). Usage flags are ints now
            //           (BufferType/BufferUsage were removed). Confirm the vertex+map-write flag names.
            vbo = device.createBuffer(() -> "zalard gui vbo",
                    com.mojang.blaze3d.buffers.GpuBuffer.USAGE_VERTEX | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_MAP_WRITE, need);
            vboCap = need;
        }
        com.mojang.blaze3d.systems.CommandEncoder enc = device.createCommandEncoder();
        // TODO(26): writeToBuffer(GpuBufferSlice, ByteBuffer). `verts` is a direct view into native memory,
        //           positioned 0..need — upload it straight, no intermediate copy.
        enc.writeToBuffer(vbo.slice(0, need), verts);

        // --- uView uniform (std140: a single vec2 -> 16-byte block). TODO(26): confirm Std140Builder API
        //     (intoBuffer/onStack, putVec2) and that a 16-byte UBO satisfies getUniformOffsetAlignment().
        java.nio.ByteBuffer ub = com.mojang.blaze3d.buffers.Std140Builder
                .intoBuffer(org.lwjgl.system.MemoryUtil.memAlloc(16))
                .putVec2(fbW, fbH).get();
        if (ubo == null) ubo = device.createBuffer(() -> "zalard gui ubo", com.mojang.blaze3d.buffers.GpuBuffer.USAGE_UNIFORM | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_MAP_WRITE, 16);
        enc.writeToBuffer(ubo.slice(0, 16), ub);
        org.lwjgl.system.MemoryUtil.memFree(ub);

        // --- draw on top of the frame's main target (color + depth views), no clear
        com.mojang.blaze3d.pipeline.RenderTarget main = mc.getMainRenderTarget();
        com.mojang.blaze3d.textures.GpuTextureView color = main.getColorTextureView();
        com.mojang.blaze3d.textures.GpuTextureView depth = main.getDepthTextureView();
        // TODO(26): createRenderPass(Supplier<String>, colorView, Optional<Integer> colorClear,
        //           depthView, OptionalDouble depthClear). Both clears empty = draw over existing contents.
        try (com.mojang.blaze3d.systems.RenderPass pass = enc.createRenderPass(
                () -> "zalard gui", color, java.util.Optional.empty(), depth, java.util.OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("GuiUniforms", ubo.slice(0, 16));
            // TODO(26): bindSampler vs bindTexture(name, GpuTextureView, sampler-state). Filter = LINEAR,
            //           wrap = CLAMP_TO_EDGE (set on the texture at creation; see setup()).
            pass.bindSampler("Sampler0", atlasView);
            pass.setVertexBuffer(0, vbo.slice(0, need));
            // TODO(26): non-indexed draw(firstVertex, vertexCount). If only drawIndexed exists, build a
            //           trivial 0..count-1 index buffer once and use drawIndexed.
            pass.draw(0, count);
        }
    }

    private void setup(com.mojang.blaze3d.systems.GpuDevice device) {
        ZalardNative.load();
        int w = ZalardNative.atlasWidth(), h = ZalardNative.atlasHeight();
        // Native atlas is 3-channel RGB (MSDF). GPU texture formats are RGBA, so expand to RGBA8 once.
        // TODO(26): confirm TextureFormat.RGBA8 and whether an RGB format is available (would save the copy).
        ByteBuffer rgb = ZalardNative.atlasPixels();
        ByteBuffer rgba = org.lwjgl.system.MemoryUtil.memAlloc(w * h * 4);
        for (int i = 0, n = w * h; i < n; i++) {
            rgba.put((byte) rgb.get(i * 3)).put((byte) rgb.get(i * 3 + 1)).put((byte) rgb.get(i * 3 + 2)).put((byte) 0xFF);
        }
        rgba.flip();
        // TODO(26): createTexture(Supplier<String> label, int usage, TextureFormat, int w, int h,
        //           int depthOrLayers, int mipLevels). Then set LINEAR filter + CLAMP_TO_EDGE addressing,
        //           and writeToTexture(tex, data, ...). Exact arg lists drifted across the range — confirm.
        atlasTex = device.createTexture(() -> "zalard atlas",
                com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING | com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST,
                com.mojang.blaze3d.textures.TextureFormat.RGBA8, w, h, 1, 1);
        atlasTex.setTextureFilter(com.mojang.blaze3d.textures.FilterMode.LINEAR, false);
        atlasTex.setAddressMode(com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE);
        device.createCommandEncoder().writeToTexture(atlasTex, rgba, com.mojang.blaze3d.textures.TextureFormat.RGBA8, 0, 0, 0, 0, w, h);
        org.lwjgl.system.MemoryUtil.memFree(rgba);
        atlasView = device.createTextureView(atlasTex);
        ready = true;
    }

    @Override
    public void close() {
        if (vbo != null) { vbo.close(); vbo = null; }
        if (ubo != null) { ubo.close(); ubo = null; }
        if (atlasView != null) { atlasView.close(); atlasView = null; }
        if (atlasTex != null) { atlasTex.close(); atlasTex = null; }
        ready = false;
    }
    *///?} else {
    @Override
    public void draw(Minecraft mc, int fbW, int fbH, ByteBuffer verts, int count, int stride) {
        // Pre-26 builds are OpenGL-only and never select this backend (NativeRenderer uses the GL path
        // below 26). Throwing here just makes a forced selection degrade cleanly to the GL backend.
        throw new UnsupportedOperationException("pipeline GUI backend is 26.x+ only (this build targets <26)");
    }
    //?}
}
