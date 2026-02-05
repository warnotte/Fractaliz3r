/**
 * Common GLSL utilities for Fractaliz3r
 *
 * This file is included first in all fractal shaders.
 * Provides:
 * - Constants
 * - Common uniforms
 * - Vector/quaternion operations
 * - Random number generation
 * - Color utilities
 * - Camera ray generation
 * - Tone mapping
 */

// ============================================================================
// Constants
// ============================================================================

const float PI = 3.14159265359;
const float TAU = 6.28318530718;
const float MAX_DISTANCE = 100.0;
const float MIN_EPSILON = 0.000001;
const float STEP_FACTOR = 0.9;
const float NORMAL_EPSILON = 0.00001;

// ============================================================================
// Common Uniforms (available to all shaders)
// ============================================================================

uniform vec2 resolution;
uniform int sampleIndex;
uniform float time;

// Camera
uniform vec3 camPos;
uniform vec4 camQuat;  // Quaternion (x, y, z, w)
uniform float fov;

// Lighting
uniform vec3 lightDir;
uniform vec3 lightColor;
uniform float lightIntensity;
uniform vec3 ambientColor;
uniform float ambientIntensity;

// Material
uniform vec3 baseHue;
uniform int paletteIndex;
uniform float colorStrength;
uniform float paletteOffset;

// Quality
uniform float qualityMultiplier;
uniform int maxRaySteps;
uniform float baseEpsilon;

// Effects
uniform float shadowSoftness;
uniform int shadowSteps;
uniform int aoSteps;
uniform float aoIntensity;
uniform float glowIntensity;
uniform float specularIntensity;
uniform float specularPower;

// Depth of Field
uniform int dofEnabled;
uniform float focalDistance;
uniform float aperture;
uniform int dofSamples;

// Render mode (0=Final, 1=Normals, 2=Depth, 3=AO, 4=Iterations, etc.)
uniform int renderMode;

// Path Tracing
uniform int pathTracingEnabled;
uniform int maxBounces;
uniform float roughness;        // Surface roughness for GGX
uniform float skyIntensity;     // Environment light intensity
uniform float indirectMultiplier; // Multiplier for indirect/bounced light (0 = direct only, 1 = full GI)

// Material System
// Type: 0 = Lambertian (diffuse), 1 = Metallic, 2 = Glass (dielectric)
uniform int materialType;
uniform float metalness;        // For metallic: blend between dielectric and metal (0-1)
uniform float ior;              // Index of refraction for glass (typically 1.5)

// Environment Map
uniform sampler2D envMap;       // Equirectangular environment map
uniform int useEnvMap;          // 0 = procedural, 1 = HDRI
uniform float envRotation;      // Rotation angle in radians
uniform float envLightingMix;   // 0 = directional only, 1 = full HDRI lighting

// ============================================================================
// Random Number Generation (PCG-based)
// ============================================================================

uint pcg_hash(uint seed) {
    uint state = seed * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

float random(inout uint seed) {
    seed = pcg_hash(seed);
    return float(seed) / 4294967295.0;
}

vec2 random2(inout uint seed) {
    return vec2(random(seed), random(seed));
}

vec3 random3(inout uint seed) {
    return vec3(random(seed), random(seed), random(seed));
}

// Initialize random seed from pixel coordinates and sample index
uint initRandom(vec2 fragCoord, int sampleIdx) {
    return uint(fragCoord.x) * 1973u + uint(fragCoord.y) * 9277u + uint(sampleIdx) * 26699u;
}

// Uniform disk sampling (for DoF)
vec2 randomDisk(inout uint seed) {
    float r = sqrt(random(seed));
    float theta = random(seed) * TAU;
    return r * vec2(cos(theta), sin(theta));
}

// Cosine-weighted hemisphere sampling (for diffuse)
vec3 randomCosineHemisphere(inout uint seed, vec3 normal) {
    float r1 = random(seed);
    float r2 = random(seed);

    float phi = TAU * r1;
    float sinTheta = sqrt(r2);
    float cosTheta = sqrt(1.0 - r2);

    // Create basis
    vec3 w = normal;
    vec3 u = normalize(cross(abs(w.x) > 0.1 ? vec3(0, 1, 0) : vec3(1, 0, 0), w));
    vec3 v = cross(w, u);

    return normalize(u * cos(phi) * sinTheta + v * sin(phi) * sinTheta + w * cosTheta);
}

// ============================================================================
// Material System Constants and Functions
// ============================================================================

const int MATERIAL_LAMBERTIAN = 0;
const int MATERIAL_METALLIC = 1;
const int MATERIAL_GLASS = 2;

// GGX/Trowbridge-Reitz microfacet distribution for metallic materials
vec3 randomGGX(inout uint seed, vec3 normal, float roughness) {
    float r1 = random(seed);
    float r2 = random(seed);

    float a = roughness * roughness;
    float a2 = a * a;

    float phi = TAU * r1;
    float cosTheta = sqrt((1.0 - r2) / (1.0 + (a2 - 1.0) * r2));
    float sinTheta = sqrt(1.0 - cosTheta * cosTheta);

    // Half-vector in tangent space
    vec3 H = vec3(
        sinTheta * cos(phi),
        sinTheta * sin(phi),
        cosTheta
    );

    // Create basis
    vec3 w = normal;
    vec3 u = normalize(cross(abs(w.x) > 0.1 ? vec3(0, 1, 0) : vec3(1, 0, 0), w));
    vec3 v = cross(w, u);

    // Transform to world space
    return normalize(u * H.x + v * H.y + w * H.z);
}

// Schlick Fresnel approximation
float fresnelSchlick(float cosTheta, float F0) {
    return F0 + (1.0 - F0) * pow(1.0 - cosTheta, 5.0);
}

vec3 fresnelSchlickVec(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(1.0 - cosTheta, 5.0);
}

// Refraction using Snell's law
// Returns true if refraction occurs, false if total internal reflection
bool refractRay(vec3 incident, vec3 normal, float eta, out vec3 refracted) {
    float cosI = -dot(incident, normal);
    float sin2T = eta * eta * (1.0 - cosI * cosI);

    if (sin2T > 1.0) {
        // Total internal reflection
        return false;
    }

    float cosT = sqrt(1.0 - sin2T);
    refracted = eta * incident + (eta * cosI - cosT) * normal;
    return true;
}

// Fresnel for dielectrics (Schlick approximation with correct IOR)
float fresnelDielectric(float cosTheta, float ior) {
    // F0 for dielectric based on IOR
    float r0 = (1.0 - ior) / (1.0 + ior);
    float F0 = r0 * r0;
    return fresnelSchlick(cosTheta, F0);
}

// ============================================================================
// Quaternion Operations
// ============================================================================

vec3 rotateByQuaternion(vec3 v, vec4 q) {
    vec3 qv = q.xyz;
    float qw = q.w;
    return v + 2.0 * cross(qv, cross(qv, v) + qw * v);
}

vec4 quaternionMultiply(vec4 a, vec4 b) {
    return vec4(
        a.w * b.xyz + b.w * a.xyz + cross(a.xyz, b.xyz),
        a.w * b.w - dot(a.xyz, b.xyz)
    );
}

vec4 quaternionFromAxisAngle(vec3 axis, float angle) {
    float halfAngle = angle * 0.5;
    return vec4(axis * sin(halfAngle), cos(halfAngle));
}

// ============================================================================
// Camera Ray Generation
// ============================================================================

struct Ray {
    vec3 origin;
    vec3 direction;
};

Ray getCameraRay(vec2 screenUV) {
    Ray ray;
    ray.origin = camPos;

    // Compute ray direction in camera space
    float aspect = resolution.x / resolution.y;
    float fovRad = radians(fov);
    float halfHeight = tan(fovRad * 0.5);
    float halfWidth = halfHeight * aspect;

    vec3 localDir = normalize(vec3(
        screenUV.x * halfWidth,
        screenUV.y * halfHeight,
        1.0
    ));

    // Transform by camera quaternion
    ray.direction = rotateByQuaternion(localDir, camQuat);

    return ray;
}

Ray getCameraRayDOF(vec2 screenUV, inout uint seed) {
    if (dofEnabled == 0 || aperture < 0.0001) {
        return getCameraRay(screenUV);
    }

    // Get focal point
    Ray centerRay = getCameraRay(screenUV);
    vec3 focalPoint = centerRay.origin + centerRay.direction * focalDistance;

    // Random point on aperture disk
    vec2 diskSample = randomDisk(seed) * aperture;

    // Offset ray origin
    vec3 right = rotateByQuaternion(vec3(1, 0, 0), camQuat);
    vec3 up = rotateByQuaternion(vec3(0, 1, 0), camQuat);

    Ray ray;
    ray.origin = camPos + right * diskSample.x + up * diskSample.y;
    ray.direction = normalize(focalPoint - ray.origin);

    return ray;
}

// ============================================================================
// Color Utilities
// ============================================================================

// Inigo Quilez's palette function
vec3 palette(float t, vec3 a, vec3 b, vec3 c, vec3 d) {
    return a + b * cos(TAU * (c * t + d));
}

// Advanced palettes presets (IQ Style: a + b*cos(2pi*(c*t+d)))
vec3 getPresetPalette(float t) {
    if (paletteIndex == 0) { // Custom (Uses Base Color)
        return palette(t, vec3(0.5), vec3(0.5), vec3(1.0), baseHue);
    } 
    else if (paletteIndex == 1) { // Magma / Fire
        return palette(t, vec3(0.5, 0.5, 0.5), vec3(0.5, 0.5, 0.5), vec3(1.0, 1.0, 1.0), vec3(0.00, 0.33, 0.67));
    } 
    else if (paletteIndex == 2) { // Ice / Ocean
        return palette(t, vec3(0.5, 0.5, 0.5), vec3(0.5, 0.5, 0.5), vec3(1.0, 1.0, 0.5), vec3(0.80, 0.90, 0.30));
    } 
    else if (paletteIndex == 3) { // Forest / Nature
        return palette(t, vec3(0.5, 0.5, 0.5), vec3(0.5, 0.5, 0.5), vec3(1.0, 1.0, 1.0), vec3(0.30, 0.20, 0.20));
    } 
    else if (paletteIndex == 4) { // Cyberpunk / Neon
        return palette(t, vec3(0.5, 0.5, 0.5), vec3(0.5, 0.5, 0.5), vec3(1.0, 1.0, 1.0), vec3(0.50, 0.20, 0.25));
    } 
    else if (paletteIndex == 5) { // Spectral / Rainbow
        return palette(t, vec3(0.5, 0.5, 0.5), vec3(0.5, 0.5, 0.5), vec3(1.0, 1.0, 1.0), vec3(0.00, 0.10, 0.20));
    }
    return vec3(t);
}

// Unified Material System
// Inputs:
// - factors.x: Proximity/Edge (0.0 = far, 1.0 = close/edge)
// - factors.y: Accumulation/Flow (0.0 to 1.0+, used for gradients)
// - factors.z: Iteration/Detail (0.0 = start, 1.0 = max depth)
vec3 applyMaterial(vec3 factors) {
    float structural = factors.x;
    float flow = factors.y;
    float depth = factors.z;

    // Use our new preset palettes with offset and strength
    vec3 color = getPresetPalette(flow * colorStrength + paletteOffset + depth * 0.1);

    // Add structural highlights (edges, geometric traps)
    vec3 highlight = mix(vec3(1.0), color * 1.5, 0.5);
    color = mix(color, highlight, clamp(structural * 0.9, 0.0, 1.0));

    // Darken deep iterations (Ambient Occlusion feel)
    color *= 1.0 - depth * 0.4;

    return color;
}

// HSV to RGB
vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

// Fresnel approximation
float fresnel(vec3 viewDir, vec3 normal, float power) {
    return pow(1.0 - max(dot(viewDir, normal), 0.0), power);
}

// ============================================================================
// Tone Mapping & Post-Processing
// ============================================================================

// ACES filmic tone mapping
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

// Gamma correction
vec3 gammaCorrect(vec3 color, float gamma) {
    return pow(color, vec3(1.0 / gamma));
}

vec3 gammaCorrect(vec3 color) {
    return gammaCorrect(color, 2.2);
}

// ============================================================================
// Adaptive Ray Marching Helpers
// ============================================================================

float computeAdaptiveEpsilon(float totalDist, float baseEps, float quality) {
    float scaled = baseEps / max(quality, 0.5);
    return max(MIN_EPSILON, scaled * (1.0 + totalDist * 0.1));
}

float computeStep(float dist, float quality, float stepFactor) {
    float factor = stepFactor / max(1.0, quality * 0.5);
    return dist * factor;
}

// ============================================================================
// Render Modes
// ============================================================================

const int RENDER_MODE_FINAL = 0;
const int RENDER_MODE_NORMALS = 1;
const int RENDER_MODE_DEPTH = 2;
const int RENDER_MODE_AO = 3;
const int RENDER_MODE_SHADOW = 4;
// Keep these in sync with AbstractFractalParams render mode constants
const int RENDER_MODE_DIFFUSE = 5;
const int RENDER_MODE_SPECULAR = 6;
const int RENDER_MODE_ORBIT_TRAP = 7;
const int RENDER_MODE_ITERATIONS = 8;

// ============================================================================
// Environment Sampling (Unified for raytracing and path tracing)
// ============================================================================

// Convert direction to equirectangular UV coordinates
vec2 dirToEquirectangular(vec3 dir) {
    // Apply rotation around Y axis
    float cosR = cos(envRotation);
    float sinR = sin(envRotation);
    vec3 rotDir = vec3(
        dir.x * cosR - dir.z * sinR,
        dir.y,
        dir.x * sinR + dir.z * cosR
    );

    float u = atan(rotDir.z, rotDir.x) / TAU + 0.5;
    float v = asin(clamp(rotDir.y, -1.0, 1.0)) / PI + 0.5;
    return vec2(u, v);
}

// Procedural sky with sun (used when no HDRI is loaded)
vec3 proceduralSky(vec3 dir) {
    // Gradient sky
    float t = 0.5 + 0.5 * dir.y;
    vec3 sky = mix(vec3(0.4, 0.4, 0.5), vec3(0.7, 0.8, 1.0), t);

    // Sun contribution
    float sunAngle = max(0.0, dot(dir, normalize(lightDir)));
    vec3 sun = lightColor * pow(sunAngle, 64.0) * 5.0;

    // Ground reflection (dark below horizon)
    if (dir.y < 0.0) {
        sky = mix(sky, vec3(0.1, 0.08, 0.06), smoothstep(0.0, -0.3, dir.y));
    }

    return sky + sun;
}

// Sample environment (HDRI or procedural)
vec3 sampleEnvironment(vec3 dir) {
    vec3 envColor;

    if (useEnvMap != 0) {
        // Sample HDRI (equirectangular)
        vec2 uv = dirToEquirectangular(dir);
        envColor = texture(envMap, uv).rgb;
    } else {
        // Procedural sky
        envColor = proceduralSky(dir);
    }

    return envColor * skyIntensity;
}

// Background with glow effect (for raytracing when ray misses)
vec3 sampleEnvironmentWithGlow(vec3 dir, float minDist) {
    vec3 bg = sampleEnvironment(dir);

    // Add glow effect based on how close we got to the fractal
    float glow = exp(-minDist * 10.0) * glowIntensity;
    bg += getPresetPalette(minDist * 2.0) * glow;

    // Add stars (only for procedural sky or as overlay)
    if (useEnvMap == 0) {
        vec3 starDir = normalize(dir);
        float stars = pow(max(0.0, sin(starDir.x * 100.0) * sin(starDir.y * 100.0) * sin(starDir.z * 100.0)), 20.0);
        bg += vec3(stars * 0.3);
    }

    return bg;
}

// Sample environment for diffuse lighting (hemisphere average)
// This provides environment-based ambient/diffuse lighting
vec3 sampleEnvironmentDiffuse(vec3 normal) {
    // Simple approximation: sample in normal direction + a few offset directions
    // For proper irradiance, we'd need precomputed irradiance maps
    vec3 up = abs(normal.y) < 0.99 ? vec3(0, 1, 0) : vec3(1, 0, 0);
    vec3 right = normalize(cross(up, normal));
    up = cross(normal, right);

    // Sample in 5 directions (normal + 4 around it at 45 degrees)
    vec3 irradiance = sampleEnvironment(normal);
    irradiance += sampleEnvironment(normalize(normal + right * 0.7));
    irradiance += sampleEnvironment(normalize(normal - right * 0.7));
    irradiance += sampleEnvironment(normalize(normal + up * 0.7));
    irradiance += sampleEnvironment(normalize(normal - up * 0.7));

    return irradiance * 0.2; // Average of 5 samples
}

// Get combined ambient lighting (mix of traditional ambient and environment)
vec3 getAmbientLighting(vec3 normal) {
    vec3 traditionalAmbient = ambientColor * ambientIntensity;

    if (useEnvMap != 0 && envLightingMix > 0.0) {
        vec3 envAmbient = sampleEnvironmentDiffuse(normal);
        return mix(traditionalAmbient, envAmbient, envLightingMix);
    }

    return traditionalAmbient;
}
