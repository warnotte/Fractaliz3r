/**
 * Common GLSL utilities for fractal rendering
 * Shared across all fractal types
 */

// ============================================================================
// Constants
// ============================================================================

const float PI = 3.14159265359;
const float MAX_DISTANCE = 100.0;
const float MIN_EPSILON = 0.00001;
const float STEP_FACTOR = 0.9;
const float NORMAL_EPSILON = 0.0001;

// ============================================================================
// Uniforms (globally accessible - this is why GLSL > OpenCL for this!)
// ============================================================================

uniform vec2 resolution;
uniform vec3 camPos;
uniform vec4 camQuat;
uniform float fov;
uniform int sampleIndex;
uniform float time;

// Lighting
uniform vec3 lightDir;
uniform vec3 lightColor;
uniform vec3 ambientColor;

// ============================================================================
// Random number generation (PCG-based)
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

// ============================================================================
// Quaternion operations
// ============================================================================

vec3 rotateByQuaternion(vec3 v, vec4 q) {
    vec3 qv = q.xyz;
    float qw = q.w;
    return v + 2.0 * cross(qv, cross(qv, v) + qw * v);
}

// ============================================================================
// Camera ray generation
// ============================================================================

void getCameraRay(vec2 uv, out vec3 ro, out vec3 rd) {
    // Compute ray origin
    ro = camPos;

    // Compute ray direction in camera space
    float aspect = resolution.x / resolution.y;
    float fovRad = radians(fov);
    float halfHeight = tan(fovRad * 0.5);
    float halfWidth = halfHeight * aspect;

    vec3 localDir = normalize(vec3(
        uv.x * halfWidth,
        uv.y * halfHeight,
        1.0
    ));

    // Transform by camera quaternion
    rd = rotateByQuaternion(localDir, camQuat);
}

// Jittered ray for anti-aliasing / DOF
void getCameraRayJittered(vec2 uv, vec2 jitter, out vec3 ro, out vec3 rd) {
    // Apply sub-pixel jitter for anti-aliasing
    vec2 jitteredUV = uv + jitter / resolution;
    getCameraRay(jitteredUV, ro, rd);
}

// ============================================================================
// Color utilities
// ============================================================================

// Inigo Quilez's palette function
vec3 palette(float t, vec3 a, vec3 b, vec3 c, vec3 d) {
    return a + b * cos(6.28318 * (c * t + d));
}

// Default fractal palette
vec3 fractalPalette(float t) {
    return palette(t,
        vec3(0.5, 0.5, 0.5),
        vec3(0.5, 0.5, 0.5),
        vec3(1.0, 1.0, 1.0),
        vec3(0.0, 0.33, 0.67)
    );
}

// ============================================================================
// Tone mapping and gamma correction
// ============================================================================

vec3 toneMapACES(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

vec3 gammaCorrect(vec3 color) {
    return pow(color, vec3(1.0 / 2.2));
}

// ============================================================================
// Adaptive ray marching helpers
// ============================================================================

float computeAdaptiveEpsilon(float totalDist, float baseEpsilon) {
    return max(MIN_EPSILON, baseEpsilon * (1.0 + totalDist * 0.1));
}

float computeStep(float dist, float stepFactor) {
    return dist * stepFactor;
}
