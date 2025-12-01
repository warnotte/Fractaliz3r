#version 430 core

/**
 * Gaussian blur shader for bloom effect
 * Two-pass separable filter (horizontal/vertical)
 * 13-tap Gaussian kernel for quality blur
 */

in vec2 uv;
out vec4 FragColor;

uniform sampler2D inputTexture;
uniform vec2 direction;  // (1,0) for horizontal, (0,1) for vertical
uniform vec2 resolution;

// 13-tap Gaussian kernel weights (sigma ~= 4)
const float weights[7] = float[](
    0.1964825501511404,
    0.2969069646728344,
    0.09447039785044732,
    0.010381362401148057,
    0.0003951070280050633,
    0.000005188579191494255,
    0.0000000235265332093
);

void main() {
    vec2 texelSize = 1.0 / resolution;
    vec2 offset = direction * texelSize;

    // Center sample
    vec3 result = texture(inputTexture, uv).rgb * weights[0];

    // Bilateral samples
    for (int i = 1; i < 7; i++) {
        vec2 sampleOffset = offset * float(i) * 2.0; // Larger spread for more blur
        result += texture(inputTexture, uv + sampleOffset).rgb * weights[i];
        result += texture(inputTexture, uv - sampleOffset).rgb * weights[i];
    }

    FragColor = vec4(result, 1.0);
}
