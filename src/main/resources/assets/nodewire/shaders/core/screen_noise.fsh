#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float Time;

in vec2 texCoord0;
in vec4 vertexColor; // .a carries the reception signal (1 = perfect, 0 = static)

out vec4 fragColor;

// At/above this signal the picture is perfectly clean (no noise). Below it the
// noise ramps up smoothly. Keep in sync with ScreenBlockRenderer.CLEAN_SIGNAL.
const float CLEAN = 0.95;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// Smooth (interpolated) value noise — continuous in space AND time, so scrolling
// it gives gentle flowing grain instead of a per-frame strobe.
float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

void main() {
    float signal = clamp(vertexColor.a, 0.0, 1.0);

    // noise = (CLEAN - signal) / CLEAN, clamped: 0 in the clean zone, 1 at zero
    // signal. pow(.,0.75) front-loads it so weak signal degrades hard.
    float noise = clamp((CLEAN - signal) / CLEAN, 0.0, 1.0);
    float heavy = pow(noise, 0.75);

    vec2 uv = texCoord0;
    float row = floor(uv.y * 256.0);

    // Gentle per-scanline horizontal drift (smooth, slow — no jitter strobe).
    float tear = (vnoise(vec2(row * 0.25, Time * 2.0)) - 0.5) * 0.12 * heavy;
    uv.x = fract(uv.x + tear);

    vec3 color = texture(Sampler0, uv).rgb;

    // Flowing grain: coarse value noise that scrolls continuously (no boiling) and
    // is compressed to a low-contrast gray band so it never glares / flickers.
    float g = vnoise(uv * vec2(90.0, 60.0) + vec2(Time * 5.0, Time * 3.0));
    g = 0.35 + 0.4 * g;
    color = mix(color, vec3(g), heavy * 0.9);

    // Force opaque — the screen is a solid panel; vertexColor.a is the signal.
    fragColor = vec4(color, 1.0) * ColorModulator;
}
