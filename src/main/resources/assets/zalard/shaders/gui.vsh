#version 150
in vec2 aPos; in vec2 aUV; in vec4 aP; in vec4 aCol; in vec4 aClip; in float aKind;
uniform vec2 uView;
out vec2 vPos; out vec2 vUV; out vec4 vP; out vec4 vCol; out vec4 vClip; out float vKind;
void main() {
    vPos = aPos; vUV = aUV; vP = aP; vCol = aCol; vClip = aClip; vKind = aKind;
    gl_Position = vec4(aPos.x / uView.x * 2.0 - 1.0, 1.0 - aPos.y / uView.y * 2.0, 0.0, 1.0);
}
