// Kawase blur kernel — 4-tap diamond pattern
// Based on Shadertoy by Kubuxu: https://www.shadertoy.com/view/Xl3XW7

#if defined(VERTEX)
attribute vec2 VertexCoord;
attribute vec2 TexCoord;
varying vec2 vTexCoord;
void main() {
    gl_Position = vec4(VertexCoord, 0.0, 1.0);
    vTexCoord = TexCoord;
}
#elif defined(FRAGMENT)
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D Texture;
uniform vec2 TextureSize;

void main() {
    vec2 texelSize = 1.0 / TextureSize;
    vec2 step1 = 0.5 * texelSize;
    vec4 color = texture2D(Texture, vTexCoord + step1) * 0.25;
    color += texture2D(Texture, vTexCoord - step1) * 0.25;
    vec2 step2 = vec2(step1.x, -step1.y);
    color += texture2D(Texture, vTexCoord + step2) * 0.25;
    color += texture2D(Texture, vTexCoord - step2) * 0.25;
    gl_FragColor = color;
}
#endif
