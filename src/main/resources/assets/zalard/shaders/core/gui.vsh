#version 150

// Blaze3D-pipeline variant of gui.vsh, used by PipelineGuiRenderBackend on MC 26.x (GL + Vulkan).
// Differs from the raw-GL gui.vsh only in how the view size is delivered: a std140 uniform block
// (declared to the pipeline via .withUniform("GuiUniforms", UNIFORM_BUFFER)) instead of a bare uniform.
// Attribute names/order must match GUI_FORMAT in PipelineGuiRenderBackend.
// TODO(26): confirm MC's pipeline shader preamble doesn't require relocating these (some versions inject
//           a #version/defines header and expect `layout(location=N)` on the inputs).

in vec2 aPos; in vec2 aUV; in vec4 aP; in vec4 aCol; in vec4 aClip; in float aKind;

layout(std140) uniform GuiUniforms {
    vec2 uView;
};

out vec2 vPos; out vec2 vUV; out vec4 vP; out vec4 vCol; out vec4 vClip; out float vKind;

void main() {
    vPos = aPos; vUV = aUV; vP = aP; vCol = aCol; vClip = aClip; vKind = aKind;
    gl_Position = vec4(aPos.x / uView.x * 2.0 - 1.0, 1.0 - aPos.y / uView.y * 2.0, 0.0, 1.0);
}
