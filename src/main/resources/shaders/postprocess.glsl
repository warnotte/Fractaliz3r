#version 430 core

/**
 * Post-processing shader for Fractaliz3r
 *
 * Effects:
 * - ACES/Reinhard/Filmic tone mapping
 * - Bloom (bright areas glow)
 * - Chromatic aberration (color fringing)
 * - Vignette (darkened edges)
 * - Film grain (optional)
 * - Sharpening (optional)
 */

in vec2 uv;
out vec4 FragColor;

// Input textures
uniform sampler2D accumTexture;    // Main render (accumulated)
uniform sampler2D bloomTexture;    // Blurred bright areas
uniform int sampleCount;
uniform int renderMode;

// Resolution for effects
uniform vec2 resolution;

// Post-processing parameters
uniform int toneMapMode;           // 0=ACES, 1=Reinhard, 2=Filmic, 3=None
uniform float exposure;            // Exposure adjustment (default 1.0)

uniform int bloomEnabled;          // 0=off, 1=on
uniform float bloomIntensity;      // Bloom strength (default 0.5)
uniform float bloomThreshold;      // Brightness threshold (default 1.0)

uniform int chromaticAberrationEnabled;
uniform float chromaticAberrationIntensity; // 0.0 - 0.02 typical

uniform int vignetteEnabled;
uniform float vignetteIntensity;   // 0.0 - 1.0
uniform float vignetteSoftness;    // 0.0 - 1.0

uniform int filmGrainEnabled;
uniform float filmGrainIntensity;  // 0.0 - 0.1 typical
uniform float filmGrainTime;       // For animation

uniform int sharpenEnabled;
uniform float sharpenIntensity;    // 0.0 - 1.0

uniform float saturation;          // 0.0 - 2.0 (default 1.0)

// ============================================================================
// Color Adjustments
// ============================================================================

vec3 adjustSaturation(vec3 color, float sat) {
    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
    return mix(vec3(luminance), color, sat);
}

// ============================================================================
// Tone Mapping Functions
// ============================================================================

// ACES filmic tone mapping (Academy Color Encoding System)
vec3 toneMapACES(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

// Reinhard tone mapping
vec3 toneMapReinhard(vec3 x) {
    return x / (x + vec3(1.0));
}

// Uncharted 2 filmic tone mapping
vec3 uncharted2Tonemap(vec3 x) {
    const float A = 0.15; // Shoulder Strength
    const float B = 0.50; // Linear Strength
    const float C = 0.10; // Linear Angle
    const float D = 0.20; // Toe Strength
    const float E = 0.02; // Toe Numerator
    const float F = 0.30; // Toe Denominator
    return ((x*(A*x+C*B)+D*E)/(x*(A*x+B)+D*F))-E/F;
}

vec3 toneMapFilmic(vec3 color) {
    const float W = 11.2; // White point
    vec3 curr = uncharted2Tonemap(color * 2.0);
    vec3 whiteScale = 1.0 / uncharted2Tonemap(vec3(W));
    return curr * whiteScale;
}

vec3 applyToneMap(vec3 color, int mode) {
    switch (mode) {
        case 0: return toneMapACES(color);
        case 1: return toneMapReinhard(color);
        case 2: return toneMapFilmic(color);
        default: return clamp(color, 0.0, 1.0);
    }
}

// ============================================================================
// Chromatic Aberration
// ============================================================================

vec3 chromaticAberration(sampler2D tex, vec2 texCoord, float intensity) {
    vec2 center = vec2(0.5);
    vec2 dir = texCoord - center;
    float dist = length(dir);

    // Offset increases with distance from center
    vec2 offset = dir * intensity * dist;

    float r = texture(tex, texCoord + offset).r;
    float g = texture(tex, texCoord).g;
    float b = texture(tex, texCoord - offset).b;

    return vec3(r, g, b);
}

// ============================================================================
// Vignette
// ============================================================================

float vignette(vec2 texCoord, float intensity, float softness) {
    vec2 center = vec2(0.5);
    float dist = distance(texCoord, center) * 1.414; // Normalize to corner

    // Smooth falloff
    float vign = 1.0 - smoothstep(1.0 - softness - intensity, 1.0 - softness, dist);
    return mix(1.0, vign, intensity);
}

// ============================================================================
// Film Grain
// ============================================================================

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

vec3 filmGrain(vec3 color, vec2 texCoord, float intensity, float time) {
    float noise = hash(texCoord * resolution + vec2(time * 1000.0));
    noise = (noise - 0.5) * intensity;
    return color + vec3(noise);
}

// ============================================================================
// Sharpening (Unsharp Mask)
// ============================================================================

vec3 sharpen(sampler2D tex, vec2 texCoord, vec2 texelSize, float intensity) {
    vec3 center = texture(tex, texCoord).rgb;

    vec3 blur = vec3(0.0);
    blur += texture(tex, texCoord + vec2(-texelSize.x, 0.0)).rgb;
    blur += texture(tex, texCoord + vec2( texelSize.x, 0.0)).rgb;
    blur += texture(tex, texCoord + vec2(0.0, -texelSize.y)).rgb;
    blur += texture(tex, texCoord + vec2(0.0,  texelSize.y)).rgb;
    blur *= 0.25;

    // Unsharp mask: original + (original - blurred) * intensity
    return center + (center - blur) * intensity;
}

// ============================================================================
// Main
// ============================================================================

void main() {
    vec2 texelSize = 1.0 / resolution;

    // Read accumulated color
    vec3 color;

    if (chromaticAberrationEnabled != 0 && chromaticAberrationIntensity > 0.0001) {
        color = chromaticAberration(accumTexture, uv, chromaticAberrationIntensity);
    } else {
        color = texture(accumTexture, uv).rgb;
    }

    // Normalize by sample count
    color /= float(max(sampleCount, 1));

    // Apply exposure
    color *= exposure;

    // Add bloom
    if (bloomEnabled != 0 && bloomIntensity > 0.0001) {
        vec3 bloom = texture(bloomTexture, uv).rgb;
        color += bloom * bloomIntensity;
    }

    // Sharpening (before tone mapping)
    if (sharpenEnabled != 0 && sharpenIntensity > 0.0001) {
        // Create a normalized version for sharpening
        vec3 sharpened = sharpen(accumTexture, uv, texelSize, sharpenIntensity);
        sharpened /= float(max(sampleCount, 1));
        sharpened *= exposure;
        // Blend with chromatic aberration result
        color = mix(color, sharpened, 0.5);
    }

    // Only apply effects for final render mode
    if (renderMode == 0) {
        // Apply saturation before tone mapping for better vibrance
        // Dynamic saturation boost: slightly increase saturation for very high sample counts
        float dynamicSat = saturation;
        if (sampleCount > 64) dynamicSat *= 1.0 + min(0.2, (float(sampleCount) - 64.0) / 1000.0);
        color = adjustSaturation(color, dynamicSat);

        // Tone mapping
        color = applyToneMap(color, toneMapMode);

        // Gamma correction
        color = pow(color, vec3(1.0 / 2.2));

        // Film grain (after gamma for natural look)
        if (filmGrainEnabled != 0 && filmGrainIntensity > 0.0001) {
            color = filmGrain(color, uv, filmGrainIntensity, filmGrainTime);
        }

        // Vignette (last - affects final brightness)
        if (vignetteEnabled != 0 && vignetteIntensity > 0.0001) {
            color *= vignette(uv, vignetteIntensity, vignetteSoftness);
        }
    }
    // Debug modes: output values without post-processing

    FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
