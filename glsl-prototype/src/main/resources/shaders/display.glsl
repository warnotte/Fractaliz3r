#version 430 core

/**
 * Display shader for progressive rendering
 * Reads accumulated samples and applies tone mapping
 */

in vec2 uv;
out vec4 FragColor;

uniform sampler2D accumTexture;
uniform int sampleCount;

// ACES tone mapping
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
    vec3 color = accumulated / float(sampleCount);

    // Tone mapping
    color = toneMapACES(color);

    // Gamma correction
    color = pow(color, vec3(1.0 / 2.2));

    FragColor = vec4(color, 1.0);
}
