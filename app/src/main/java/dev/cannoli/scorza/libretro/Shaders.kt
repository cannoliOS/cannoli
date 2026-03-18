package dev.cannoli.scorza.libretro

object Shaders {

    val vertex = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    val passthrough = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """.trimIndent()

    // lcd3x by Gigaherz (public domain) + Pixel Transparency v1.1 by mattakins (MIT)
    // https://github.com/libretro/glsl-shaders/blob/master/handheld/shaders/lcd3x.glsl
    // https://github.com/mattakins/Pixel_Transparency
    val lcd = """
        precision highp float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform vec2 uSourceSize;
        uniform vec2 uOutputSize;

        // --- lcd3x ---
        #define PI 3.141592654
        const float brighten_scanlines = 3.0;
        const float brighten_lcd = 1.5;
        const vec3 offsets = vec3(PI) * vec3(0.5, 0.5 - 2.0/3.0, 0.5 - 4.0/3.0);

        // --- Pixel Transparency ---
        const float PT_BASE_ALPHA = 0.20;
        const float PT_THRESHOLD = 0.90;
        const vec3  PT_TINT = vec3(0.651, 0.675, 0.518); // GB Pocket
        const float PT_SHADOW_OFFSET = 3.0;
        const float PT_SHADOW_OPACITY = 0.5;
        const float PT_SHADOW_BLUR = 1.0;

        float luma(vec3 c) {
            return 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
        }

        float isWhite(vec3 c) {
            return step(PT_THRESHOLD, luma(c))
                 * step(PT_THRESHOLD * 0.9, min(min(c.r, c.g), c.b));
        }

        float hash(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        vec3 paperBg(vec2 uv) {
            vec2 p = uv * 128.0;
            float n = hash(p) * 0.5 + hash(p * 2.0) * 0.25 + hash(p * 4.0) * 0.125;
            float grain = (n - 0.4375) * 0.065;
            vec3 base = vec3(0.4773 + grain);
            return clamp(PT_TINT + base * 2.0 - 1.0, 0.0, 1.0);
        }

        void main() {
            vec3 res = texture2D(uTexture, vTexCoord).rgb;

            // lcd3x
            vec2 omega = vec2(PI) * 2.0 * uSourceSize;
            vec2 angle = vTexCoord * omega;
            float yfactor = (brighten_scanlines + sin(angle.y)) / (brighten_scanlines + 1.0);
            vec3 xfactors = (brighten_lcd + sin(angle.x + offsets)) / (brighten_lcd + 1.0);
            vec3 lcd = res * yfactor * xfactors;

            // Procedural paper background with tint
            vec3 bg = paperBg(vTexCoord);

            // Drop shadow (5-sample lite blur)
            float sf = sqrt(uOutputSize.x * uOutputSize.y / 307200.0);
            vec2 sOff = vec2(-PT_SHADOW_OFFSET) * sf / uOutputSize;
            float bd = PT_SHADOW_BLUR * sf / uOutputSize.x;

            vec3 ss = texture2D(uTexture, vTexCoord + sOff).rgb;
            if (isWhite(ss) < 0.5) {
                vec2 sp = vTexCoord + sOff;
                float sh = (1.0 - luma(texture2D(uTexture, sp).rgb))
                         + (1.0 - luma(texture2D(uTexture, sp + vec2(-bd, 0.0)).rgb))
                         + (1.0 - luma(texture2D(uTexture, sp + vec2( bd, 0.0)).rgb))
                         + (1.0 - luma(texture2D(uTexture, sp + vec2(0.0, -bd)).rgb))
                         + (1.0 - luma(texture2D(uTexture, sp + vec2(0.0,  bd)).rgb));
                bg = mix(bg, bg * 0.2, (sh / 5.0) * PT_SHADOW_OPACITY);
            }

            // Brightness-based transparency (using lcd-processed brightness)
            float transparency = clamp(PT_BASE_ALPHA * luma(lcd) * 2.665, 0.0, 1.0);

            gl_FragColor = vec4(mix(lcd, bg, transparency), 1.0);
        }
    """.trimIndent()

    // Kawase blur kernel — 4-tap diamond pattern at distance d
    // Based on Shadertoy by Kubuxu: https://www.shadertoy.com/view/Xl3XW7
    val kawaseBlur = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform vec2 uTexelSize;
        uniform float uDistance;
        void main() {
            vec2 step1 = (uDistance + 0.5) * uTexelSize;
            vec4 color = texture2D(uTexture, vTexCoord + step1) * 0.25;
            color += texture2D(uTexture, vTexCoord - step1) * 0.25;
            vec2 step2 = vec2(step1.x, -step1.y);
            color += texture2D(uTexture, vTexCoord + step2) * 0.25;
            color += texture2D(uTexture, vTexCoord - step2) * 0.25;
            gl_FragColor = color;
        }
    """.trimIndent()

    // CRT composite based on zfast_crt_geo by SoltanGris42 (Greg Hogan)
    // S-Video color blending variant
    // Glow compositing via kawase blur screen blend
    // https://github.com/libretro/glsl-shaders/blob/master/crt/shaders/zfast_crt_geo_svideo.glsl
    val crtComposite = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform sampler2D uGlowTex;
        uniform vec2 uSourceSize;
        uniform vec2 uOutputSize;
        uniform float uCurvature;
        uniform float uScanline;
        uniform float uMaskDark;
        uniform float uVignette;
        uniform float uGlow;
        uniform float uSweep;
        uniform float uSweepBright;
        uniform float uBrightness;
        uniform float uNoise;
        uniform float uTime;
        uniform float uSweepPhase;

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
            vec2 xy = mix(vTexCoord, Warp(vTexCoord), uCurvature);

            if (xy.x < 0.0 || xy.x > 1.0 || xy.y < 0.0 || xy.y > 1.0) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                return;
            }

            // S-Video: 3-tap chroma convergence
            vec2 bxy = vec2(0.7, -0.3) / (uSourceSize * 2.0);
            vec2 s1 = texture2D(uTexture, vec2(xy.x + bxy.x, xy.y - bxy.y)).rg;
            vec3 s2 = texture2D(uTexture, xy).rgb;
            vec2 s3 = texture2D(uTexture, vec2(xy.x - bxy.x, xy.y + bxy.y)).gb;
            vec3 colour = vec3(s1.r * 0.5, s1.g * 0.25 + s3.r * 0.25, s3.g * 0.5) + 0.5 * s2;

            // Scanlines (Quilez-style bell curve)
            float ratio = xy.y * uSourceSize.y;
            float f = ratio - (floor(ratio) + 0.5);
            float Y = f * f;
            float scanW = 1.5 - uScanline * (Y - Y * Y);

            // Column mask — alternates every other column (1-in-3 at high res)
            float MSCL = uOutputSize.y > 1499.0 ? 0.3333 : 0.5;
            float whichmask = floor(vTexCoord.x * uOutputSize.x) * -MSCL;
            float mask = 1.0 + float(fract(whichmask) < MSCL) * -uMaskDark;

            // Brightness-adaptive scanline + mask
            float luma = dot(colour, vec3(0.26667));
            colour *= mix(scanW * mask, 1.0, luma);

            // Approximate gamma linearize + warm D93 phosphor tint
            colour = max(colour * colour * vec3(1.04, 1.01, 0.93), 0.0);

            // Vignette — wider falloff so darkening reaches into the image
            float vigE = 0.08 + 0.12 * uVignette;
            float vx = smoothstep(0.0, vigE, xy.x) * smoothstep(1.0, 1.0 - vigE, xy.x);
            float vy = smoothstep(0.0, vigE, xy.y) * smoothstep(1.0, 1.0 - vigE, xy.y);
            colour *= mix(1.0, vx * vy, uVignette);

            // Phosphor sweep — bright band rolling top to bottom
            float band = smoothstep(0.04, 0.0, abs(xy.y - uSweepPhase));
            colour *= 1.0 + band * uSweepBright * uSweep;

            // Inverse gamma — compensate for darkening from scanline + mask
            float pwr = 1.0 / ((-0.0325 * uScanline + 1.0) * (-0.311 * uMaskDark + 1.0)) - 1.2;
            colour = inv_gamma(clamp(colour, 0.0, 1.0), vec3(pwr));

            // Screen blend with kawase glow
            vec3 glow = texture2D(uGlowTex, xy).rgb;
            colour = 1.0 - (1.0 - colour) * (1.0 - glow * uGlow);

            // Round corners when curved
            if (uCurvature > 0.01) {
                vec2 corn = min(xy, 1.0 - xy);
                corn.x = 0.0001 / corn.x;
                if (corn.y <= corn.x || corn.x < 0.0001)
                    colour = vec3(0.0);
            }

            // Static noise
            float ntime = fract(uTime * 60.0) * 1000.0;
            vec2 seed = floor(vTexCoord * uOutputSize.xy) + vec2(ntime, ntime * 1.7);
            float n = (rand(seed) - 0.5) * 0.2 * uNoise;
            colour += vec3(n);

            // CRT boot — line, reveal from center, soft-to-sharp warmup
            if (uTime < 1.867) {
                float dist = abs(xy.y - 0.5);

                if (uTime < 0.183) {
                    colour = vec3(0.0);
                } else if (uTime < 0.75) {
                    float t = (uTime - 0.183) / 0.567;
                    float flicker = 0.8 + 0.2 * sin(uTime * 210.0);
                    float line = smoothstep(0.02, 0.0, dist) * flicker;
                    float glw = exp(-dist * dist * 200.0) * 0.4 * flicker;
                    float intensity = smoothstep(0.0, 0.2, t) * (line + glw);
                    colour = vec3(intensity * 0.7, intensity * 0.8, intensity * 1.0);
                } else {
                    float t = (uTime - 0.75) / 1.117;
                    float warmup = t * t;

                    vec3 blurry = texture2D(uGlowTex, xy).rgb;
                    colour = mix(blurry, colour, warmup);

                    vec3 glowC = texture2D(uGlowTex, xy).rgb;
                    float glowAmt = uGlow * warmup;
                    colour = 1.0 - (1.0 - colour) * (1.0 - glowC * glowAmt);

                    float reveal = smoothstep(0.5, 0.0, dist - t * 0.6);
                    float bright = mix(1.25, 1.0, warmup);
                    vec3 tint = mix(vec3(0.8, 0.88, 1.0), vec3(1.0), warmup);
                    colour = colour * reveal * bright * tint;
                }
            }

            gl_FragColor = vec4(colour * uBrightness, 1.0);
        }
    """.trimIndent()
}
