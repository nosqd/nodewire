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

// Cheap hash noise in [0,1).
float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    float signal = clamp(vertexColor.a, 0.0, 1.0);

    // noise = (CLEAN - signal) / CLEAN, clamped: 0 in the clean zone, 1 at zero
    // signal. pow(.,0.75) front-loads it so weak signal degrades hard.
    float noise = clamp((CLEAN - signal) / CLEAN, 0.0, 1.0);
    float heavy = pow(noise, 0.75);

    vec2 uv = texCoord0;
    float row = floor(uv.y * 256.0);

    // Per-scanline horizontal tear (wraps like a torn analog signal).
    float tear = (hash(vec2(row, floor(Time * 20.0))) - 0.5) * 0.18 * heavy;
    uv.x = fract(uv.x + tear);

    vec3 color = texture(Sampler0, uv).rgb;

    // Animated static — at minimal signal almost nothing of the image survives.
    float g = hash(uv * vec2(827.0, 491.0) + vec2(Time * 67.0, Time * 39.0));
    color = mix(color, vec3(g), heavy * 0.92);

    // Gentle brightness wobble (analog gain) — only a slight dimming, never a
    // bright flash, and slower, so it doesn't strobe / hurt the eyes.
    float flick = mix(1.0, 0.88 + 0.12 * hash(vec2(floor(Time * 12.0), 7.0)), heavy);
    color *= flick;

    // Force opaque — the screen is a solid panel; vertexColor.a is the signal.
    fragColor = vec4(color, 1.0) * ColorModulator;
}
