package dev.zalard.nativegui;

import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL21.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL33.*;

/**
 * Raw-OpenGL backend: one shader, drawn right before the buffer swap so it sits on top of everything
 * Minecraft drew. Every bit of GL state it touches is saved and restored so Minecraft's state cache stays
 * valid. This is the original Zalard draw path, unchanged in behavior — extracted behind
 * {@link GuiRenderBackend} so a Vulkan-capable path ({@link PipelineGuiRenderBackend}) can take over on
 * 26.x. It keeps working on every GL-backed Minecraft but cannot run under 26.x's Vulkan renderer.
 */
public final class GlGuiRenderBackend implements GuiRenderBackend {

    private int program, vao, vbo, atlas, uView, stride;
    private boolean ready;

    @Override
    public void draw(Minecraft mc, int fbW, int fbH, ByteBuffer verts, int count, int stride) {
        if (!ready) setup(stride);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // ---- save
            int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
            int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            int prevBuf = glGetInteger(GL_ARRAY_BUFFER_BINDING);
            int prevFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
            int prevActive = glGetInteger(GL_ACTIVE_TEXTURE);
            glActiveTexture(GL_TEXTURE0);
            int prevTex = glGetInteger(GL_TEXTURE_BINDING_2D);
            int prevSampler = glGetInteger(GL_SAMPLER_BINDING);
            IntBuffer vp = stack.mallocInt(4);
            glGetIntegerv(GL_VIEWPORT, vp);
            boolean blend = glIsEnabled(GL_BLEND), depth = glIsEnabled(GL_DEPTH_TEST),
                    cull = glIsEnabled(GL_CULL_FACE), scissor = glIsEnabled(GL_SCISSOR_TEST),
                    stencil = glIsEnabled(GL_STENCIL_TEST);
            boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
            ByteBuffer cm = stack.malloc(4);
            glGetBooleanv(GL_COLOR_WRITEMASK, cm);
            int bSrcRgb = glGetInteger(GL_BLEND_SRC_RGB), bDstRgb = glGetInteger(GL_BLEND_DST_RGB);
            int bSrcA = glGetInteger(GL_BLEND_SRC_ALPHA), bDstA = glGetInteger(GL_BLEND_DST_ALPHA);
            int eqRgb = glGetInteger(GL_BLEND_EQUATION_RGB), eqA = glGetInteger(GL_BLEND_EQUATION_ALPHA);

            // ---- draw
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            glViewport(0, 0, fbW, fbH);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_STENCIL_TEST);
            glDepthMask(false);
            glColorMask(true, true, true, true);
            glEnable(GL_BLEND);
            glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

            glUseProgram(program);
            glUniform2f(uView, fbW, fbH);
            glBindSampler(0, 0);
            glBindTexture(GL_TEXTURE_2D, atlas);
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            // one upload of exactly this frame's vertices (orphans the old storage; no full-size
            // re-allocation + second copy every frame like before)
            glBufferData(GL_ARRAY_BUFFER, verts, GL_STREAM_DRAW);
            glDrawArrays(GL_TRIANGLES, 0, count);

            // ---- restore
            glBindBuffer(GL_ARRAY_BUFFER, prevBuf);
            glBindVertexArray(prevVao);
            glBindTexture(GL_TEXTURE_2D, prevTex);
            glBindSampler(0, prevSampler);
            glActiveTexture(prevActive);
            glUseProgram(prevProgram);
            glBlendEquationSeparate(eqRgb, eqA);
            glBlendFuncSeparate(bSrcRgb, bDstRgb, bSrcA, bDstA);
            if (!blend) glDisable(GL_BLEND);
            if (depth) glEnable(GL_DEPTH_TEST);
            if (cull) glEnable(GL_CULL_FACE);
            if (scissor) glEnable(GL_SCISSOR_TEST);
            if (stencil) glEnable(GL_STENCIL_TEST);
            glDepthMask(depthMask);
            glColorMask(cm.get(0) != 0, cm.get(1) != 0, cm.get(2) != 0, cm.get(3) != 0);
            glViewport(vp.get(0), vp.get(1), vp.get(2), vp.get(3));
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevFbo);
        }
    }

    private String shader(String name) throws IOException {
        try (InputStream in = GlGuiRenderBackend.class.getResourceAsStream("/assets/zalard/shaders/" + name)) {
            if (in == null) throw new IOException("missing shader " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private int compile(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE)
            throw new IllegalStateException("shader compile: " + glGetShaderInfoLog(s));
        return s;
    }

    private void setup(int strideBytes) {
        try {
            ZalardNative.load();
            this.stride = strideBytes;
            int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
            int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            int prevBuf = glGetInteger(GL_ARRAY_BUFFER_BINDING);
            int prevActive = glGetInteger(GL_ACTIVE_TEXTURE);
            glActiveTexture(GL_TEXTURE0);
            int prevTex = glGetInteger(GL_TEXTURE_BINDING_2D);
            int prevUnpack = glGetInteger(GL_UNPACK_ALIGNMENT);
            int prevRowLen = glGetInteger(GL_UNPACK_ROW_LENGTH);
            int prevSkipR = glGetInteger(GL_UNPACK_SKIP_ROWS);
            int prevSkipP = glGetInteger(GL_UNPACK_SKIP_PIXELS);
            int prevUnpackBuf = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);

            int vs = compile(GL_VERTEX_SHADER, shader("gui.vsh"));
            int fs = compile(GL_FRAGMENT_SHADER, shader("gui.fsh"));
            program = glCreateProgram();
            glAttachShader(program, vs);
            glAttachShader(program, fs);
            String[] attrs = {"aPos", "aUV", "aP", "aCol", "aClip", "aKind"};
            for (int i = 0; i < attrs.length; i++) glBindAttribLocation(program, i, attrs[i]);
            glBindFragDataLocation(program, 0, "fragColor");
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException("shader link: " + glGetProgramInfoLog(program));
            glDeleteShader(vs);
            glDeleteShader(fs);
            uView = glGetUniformLocation(program, "uView");
            glUseProgram(program);
            glUniform1i(glGetUniformLocation(program, "uAtlas"), 0);

            vao = glGenVertexArrays();
            vbo = glGenBuffers();
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            int[][] layout = {{2, 0}, {2, 2}, {4, 4}, {4, 8}, {4, 12}, {1, 16}};
            for (int i = 0; i < layout.length; i++) {
                glEnableVertexAttribArray(i);
                glVertexAttribPointer(i, layout[i][0], GL_FLOAT, false, stride, layout[i][1] * 4L);
            }

            atlas = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, atlas);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            // MSDF atlas: 3 channels (RGB), the shader takes their median
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, ZalardNative.atlasWidth(), ZalardNative.atlasHeight(), 0,
                    GL_RGB, GL_UNSIGNED_BYTE, ZalardNative.atlasPixels());
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

            glPixelStorei(GL_UNPACK_ALIGNMENT, prevUnpack);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, prevRowLen);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, prevSkipR);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, prevSkipP);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, prevUnpackBuf);
            glBindTexture(GL_TEXTURE_2D, prevTex);
            glActiveTexture(prevActive);
            glBindVertexArray(prevVao);
            glBindBuffer(GL_ARRAY_BUFFER, prevBuf);
            glUseProgram(prevProgram);
            ready = true;
        } catch (IOException e) {
            throw new IllegalStateException("GL GUI backend setup failed", e);
        }
    }
}
