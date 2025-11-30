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
const float MIN_EPSILON = 0.00001;
const float STEP_FACTOR = 0.9;
const float NORMAL_EPSILON = 0.0001;

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

// Cosine-weighted hemisphere sampling (for future path tracing)
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

// Default fractal palette using baseHue
vec3 fractalPalette(float t) {
    return palette(t,
        vec3(0.5, 0.5, 0.5),
        vec3(0.5, 0.5, 0.5),
        vec3(1.0, 1.0, 1.0),
        baseHue
    );
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
const int RENDER_MODE_ITERATIONS = 5;
const int RENDER_MODE_ORBIT_TRAP = 6;
const int RENDER_MODE_DIFFUSE = 7;
const int RENDER_MODE_SPECULAR = 8;
