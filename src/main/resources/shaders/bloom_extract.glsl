#version 430 core

/**
 * Bloom bright extraction shader
 * Extracts pixels above brightness threshold for bloom effect
 */

in vec2 uv;
out vec4 FragColor;

uniform sampler2D accumTexture;
uniform int sampleCount;
uniform float threshold;      // Brightness threshold (default 1.0)
uniform float softThreshold;  // Soft knee (default 0.5)

void main() {
    vec3 color = texture(accumTexture, uv).rgb;

    // Normalize by sample count
    color /= float(max(sampleCount, 1));

    // Calculate brightness (perceived luminance)
    float brightness = dot(color, vec3(0.2126, 0.7152, 0.0722));

    // Soft threshold with knee
    float knee = threshold * softThreshold;
    float soft = brightness - threshold + knee;
    soft = clamp(soft, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 0.00001);

    float contribution = max(soft, brightness - threshold) / max(brightness, 0.00001);
    contribution = max(contribution, 0.0);

    FragColor = vec4(color * contribution, 1.0);
}
