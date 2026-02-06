#version 430 core

/**
 * Post-processing shader for Fractaliz3r
 *
 * Effects:
 * - Tone mapping (ACES/Reinhard/Filmic)
 * - Color Grading (Procedural LUTs: Cinema, Vintage, Matrix, Neon, B&W)
 * - Bloom (bright areas glow)
 * - Chromatic aberration (color fringing)
 * - Vignette (darkened edges)
 * - Film grain
 * - Sharpening
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

// Color Grading
uniform int colorGradingMode;      // 0=None, 1=Cinema, 2=Vintage, 3=Matrix, 4=Neon, 5=B&W
uniform float colorGradingIntensity;

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
// Color Grading Algorithms (Procedural LUTs)
// ============================================================================

vec3 liftGammaGain(vec3 color, vec3 lift, vec3 gamma, vec3 gain) {
    color = color * (1.5 - 0.5 * lift) + 0.5 * lift - 0.5;
    color = clamp(color, 0.0, 1.0);
    color = pow(color, 1.0 / gamma);
    return color * gain;
}

vec3 applyColorGrading(vec3 color) {
    vec3 graded = color;
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));

    if (colorGradingMode == 1) { // Cinema (Teal & Orange)
        // Push shadows to Teal, Highlights to Orange
        vec3 shadows = vec3(0.0, 0.1, 0.15); // Teal
        vec3 midtones = vec3(1.0, 0.9, 0.8); // Warm
        vec3 highlights = vec3(1.2, 1.1, 1.0); // Bright warm
        
        graded = mix(shadows, highlights, luma);
        graded = mix(graded, color, 0.5); // Blend with original
        graded *= vec3(1.0, 0.95, 0.9); // Global warm tint
        graded = pow(graded, vec3(1.1)); // Contrast
    } 
    else if (colorGradingMode == 2) { // Vintage (Sepia/Faded)
        vec3 sepia = vec3(1.2, 1.0, 0.8) * luma;
        graded = mix(color, sepia, 0.6);
        graded = pow(graded, vec3(0.9, 1.0, 1.1)); // Lift blacks (blueish shadows)
    }
    else if (colorGradingMode == 3) { // Matrix (Green Tint)
        vec3 green = vec3(0.5, 1.2, 0.5) * luma;
        graded = mix(color, green, 0.7);
        graded *= vec3(0.8, 1.2, 0.8);
    }
    else if (colorGradingMode == 4) { // Neon (High Contrast, Purple/Blue)
        graded = pow(color, vec3(1.2)); // Increase contrast
        graded *= vec3(1.1, 0.8, 1.2); // Purple tint
    }
    else if (colorGradingMode == 5) { // B&W (Noir)
        graded = vec3(luma);
        graded = (graded - 0.5) * 1.3 + 0.5; // High contrast
    }

    return mix(color, graded, colorGradingIntensity);
}

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
        vec3 sharpened = sharpen(accumTexture, uv, texelSize, sharpenIntensity);
        sharpened /= float(max(sampleCount, 1));
        sharpened *= exposure;
        color = mix(color, sharpened, 0.5);
    }

    // Only apply effects for final render mode
    if (renderMode == 0) {
        // Dynamic saturation boost
        float dynamicSat = saturation;
        if (sampleCount > 64) dynamicSat *= 1.0 + min(0.2, (float(sampleCount) - 64.0) / 1000.0);
        color = adjustSaturation(color, dynamicSat);
        
        // --- NEW: Color Grading (Procedural LUTs) ---
        if (colorGradingMode > 0) {
            color = applyColorGrading(color);
        }

        // Tone mapping
        color = applyToneMap(color, toneMapMode);

        // Gamma correction
        color = pow(color, vec3(1.0 / 2.2));

        // Film grain
        if (filmGrainEnabled != 0 && filmGrainIntensity > 0.0001) {
            color = filmGrain(color, uv, filmGrainIntensity, filmGrainTime);
        }

        // Vignette
        if (vignetteEnabled != 0 && vignetteIntensity > 0.0001) {
            color *= vignette(uv, vignetteIntensity, vignetteSoftness);
        }
    }

    FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}