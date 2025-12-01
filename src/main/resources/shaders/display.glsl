#version 430 core

/**
 * Display shader for progressive rendering
 * Reads accumulated samples, normalizes, and applies tone mapping
 */

in vec2 uv;
out vec4 FragColor;

uniform sampler2D accumTexture;
uniform int sampleCount;
uniform int renderMode;  // 0 = Final, other = debug modes

// ACES filmic tone mapping
vec3 toneMapACES(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

void main() {
    // Read accumulated color
    vec3 accumulated = texture(accumTexture, uv).rgb;

    // Average by sample count
    vec3 color = accumulated / float(max(sampleCount, 1));

    // Only apply tone mapping and gamma for final render mode
    if (renderMode == 0) {
        // Tone mapping
        color = toneMapACES(color);
        // Gamma correction
        color = pow(color, vec3(1.0 / 2.2));
    }
    // Debug modes: output raw values (already in displayable range)

    FragColor = vec4(color, 1.0);
}
