#version 150

// Blaze3D-pipeline variant of gui.fsh, used by PipelineGuiRenderBackend on MC 26.x (GL + Vulkan).
// Identical shape logic to the raw-GL gui.fsh; only the sampler name changes (Sampler0, bound via the
// pipeline's .withSampler("Sampler0")). fwidth()/gl_FragCoord are used by MC's own core shaders, so they
// are available under both backends.
// TODO(26): confirm the atlas sampler binds as "Sampler0" (MC's conventional name) under the Vulkan path.

uniform sampler2D Sampler0;

in vec2 vPos; in vec2 vUV; in vec4 vP; in vec4 vCol; in vec4 vClip; in float vKind;
out vec4 fragColor;

void main() {
    if (vPos.x < vClip.x || vPos.y < vClip.y || vPos.x > vClip.z || vPos.y > vClip.w) discard;
    float a;
    if (vKind < 0.5) {            // rounded rect
        vec2 q = abs(vPos - vP.xy) - vP.zw + vec2(vUV.x);
        float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - vUV.x;
        a = clamp(0.5 - d, 0.0, 1.0);
    } else if (vKind < 1.5) {     // msdf text: median of the three channels keeps corners sharp
        vec3 t = texture(Sampler0, vUV).rgb;
        float s = max(min(t.r, t.g), min(max(t.r, t.g), t.b));
        float w = max(fwidth(s), 1e-4) * 0.7;
        a = smoothstep(0.5 - w, 0.5 + w, s);
    } else if (vKind < 2.5) {     // capsule segment
        vec2 pa = vPos - vP.xy, ba = vP.zw - vP.xy;
        float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-4), 0.0, 1.0);
        a = clamp(0.5 - (length(pa - ba * h) - vUV.x), 0.0, 1.0);
    } else if (vKind < 3.5) {     // ring
        a = clamp(0.5 - (abs(length(vPos - vP.xy) - vP.z) - vP.w), 0.0, 1.0);
    } else {                      // rounded rect outline (u = radius, v = half thickness), drawn inside the edge
        vec2 q = abs(vPos - vP.xy) - vP.zw + vec2(vUV.x);
        float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - vUV.x;
        a = clamp(0.5 - (abs(d + vUV.y) - vUV.y), 0.0, 1.0);
    }
    if (a <= 0.0) discard;
    // interleaved-gradient-noise dither: kills banding on the dark gradients
    float n = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    fragColor = vec4(vCol.rgb + (n - 0.5) / 255.0, vCol.a * a);
}
