#version 450

// CRT composite based on zfast_crt_geo by SoltanGris42 (Greg Hogan)
// S-Video color blending variant
// Glow compositing via kawase blur screen blend

layout(push_constant) uniform Push {
    vec4 SourceSize;
    vec4 OriginalSize;
    vec4 OutputSize;
    uint FrameCount;
    float curvature;
    float scanline;
    float mask_dark;
    float vignette;
    float glow_strength;
    float sweep;
    float sweep_bright;
    float crt_brightness;
    float noise_amount;
} params;

#pragma parameter curvature "Curvature" 1.7 0.0 2.0 0.1
#pragma parameter scanline "Scanlines" 0.75 0.0 1.0 0.05
#pragma parameter mask_dark "Mask" 0.3 0.0 0.5 0.05
#pragma parameter vignette "Vignette" 0.85 0.0 2.0 0.05
#pragma parameter glow_strength "Glow" 0.25 0.0 1.0 0.05
#pragma parameter sweep "Sweep" 1.0 0.0 1.0 0.05
#pragma parameter sweep_bright "Sweep Brightness" 0.35 0.0 1.0 0.05
#pragma parameter crt_brightness "Brightness" 1.0 0.5 1.5 0.05
#pragma parameter noise_amount "Noise" 0.15 0.0 1.0 0.05

layout(std140, set = 0, binding = 0) uniform UBO {
    mat4 MVP;
} global;

#pragma stage vertex
layout(location = 0) in vec4 Position;
layout(location = 1) in vec2 TexCoord;
layout(location = 0) out vec2 vTexCoord;

void main() {
    gl_Position = global.MVP * Position;
    vTexCoord = TexCoord;
}

#pragma stage fragment
layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 FragColor;
layout(set = 0, binding = 2) uniform sampler2D Source;
layout(set = 0, binding = 3) uniform sampler2D Original;

#define curvature params.curvature
#define scanline params.scanline
#define mask_dark params.mask_dark
#define vignette params.vignette
#define glow_strength params.glow_strength
#define sweep params.sweep
#define sweep_bright params.sweep_bright
#define crt_brightness params.crt_brightness
#define noise_amount params.noise_amount

vec2 Warp(vec2 pos) {
    pos = pos * 2.0 - 1.0;
    pos *= vec2(1.0 + pos.y * pos.y * 0.0276,
                1.0 + pos.x * pos.x * 0.0414);
    return pos * 0.5 + 0.5;
}

float rand(vec2 co) {
    return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
}

vec3 inv_gamma(vec3 col, vec3 power) {
    vec3 cir = col - 1.0;
    cir *= cir;
    return mix(sqrt(col), sqrt(1.0 - cir), power);
}

void main() {
    vec2 xy = mix(vTexCoord, Warp(vTexCoord), curvature);

    if (xy.x < 0.0 || xy.x > 1.0 || xy.y < 0.0 || xy.y > 1.0) {
        FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    vec2 InputSize = params.OriginalSize.xy;
    vec2 OutputSize = params.OutputSize.xy;

    // S-Video: 3-tap chroma convergence
    vec2 bxy = vec2(0.7, -0.3) / (InputSize * 2.0);
    vec2 s1 = texture(Original, vec2(xy.x + bxy.x, xy.y - bxy.y)).rg;
    vec3 s2 = texture(Original, xy).rgb;
    vec2 s3 = texture(Original, vec2(xy.x - bxy.x, xy.y + bxy.y)).gb;
    vec3 colour = vec3(s1.r * 0.5, s1.g * 0.25 + s3.r * 0.25, s3.g * 0.5) + 0.5 * s2;

    float ratio = xy.y * InputSize.y;
    float f = ratio - (floor(ratio) + 0.5);
    float Y = f * f;
    float scanW = 1.5 - scanline * (Y - Y * Y);

    float MSCL = OutputSize.y > 1499.0 ? 0.3333 : 0.5;
    float whichmask = floor(vTexCoord.x * OutputSize.x) * -MSCL;
    float mask = 1.0 + float(fract(whichmask) < MSCL) * -mask_dark;

    float luma = dot(colour, vec3(0.26667));
    colour *= mix(scanW * mask, 1.0, luma);
    colour = max(colour * colour * vec3(1.04, 1.01, 0.93), 0.0);

    float vigE = 0.08 + 0.12 * vignette;
    float vx = smoothstep(0.0, vigE, xy.x) * smoothstep(1.0, 1.0 - vigE, xy.x);
    float vy = smoothstep(0.0, vigE, xy.y) * smoothstep(1.0, 1.0 - vigE, xy.y);
    colour *= mix(1.0, vx * vy, vignette);

    float sweepPhase = 1.0 - mod(float(params.FrameCount), 480.0) / 480.0;
    float band = smoothstep(0.04, 0.0, abs(xy.y - sweepPhase));
    colour *= 1.0 + band * sweep_bright * sweep;

    float pwr = 1.0 / ((-0.0325 * scanline + 1.0) * (-0.311 * mask_dark + 1.0)) - 1.2;
    colour = inv_gamma(clamp(colour, 0.0, 1.0), vec3(pwr));

    vec3 glow = texture(Source, xy).rgb;
    colour = 1.0 - (1.0 - colour) * (1.0 - glow * glow_strength);

    if (curvature > 0.01) {
        vec2 corn = min(xy, 1.0 - xy);
        corn.x = 0.0001 / corn.x;
        if (corn.y <= corn.x || corn.x < 0.0001)
            colour = vec3(0.0);
    }

    float ntime = fract(float(params.FrameCount)) * 1000.0;
    vec2 seed = floor(vTexCoord * OutputSize) + vec2(ntime, ntime * 1.7);
    float n = (rand(seed) - 0.5) * 0.2 * noise_amount;
    colour += vec3(n);

    FragColor = vec4(colour * crt_brightness, 1.0);
}
