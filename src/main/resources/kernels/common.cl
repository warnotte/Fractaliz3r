/**
 * Common OpenCL utilities shared across all fractal renderers.
 * Includes vector operations, quaternions, camera, DoF, and color utilities.
 */

// ============================================================================
// Render modes for pass visualization
// ============================================================================

#define RENDER_FINAL        0
#define RENDER_NORMALS      1
#define RENDER_DEPTH        2
#define RENDER_AO           3
#define RENDER_SHADOWS      4
#define RENDER_DIFFUSE      5
#define RENDER_SPECULAR     6
#define RENDER_ORBIT_TRAP   7
#define RENDER_ITERATIONS   8

// ============================================================================
// Constants for ray marching quality
// ============================================================================

#define STEP_FACTOR 0.5f
#define MIN_EPSILON 1e-7f
#define MAX_EPSILON 1e-3f
#define EPSILON_FACTOR 1e-4f
#define MAX_DISTANCE 100.0f
#define NORMAL_EPSILON 0.0012f

// ============================================================================
// Vector operations
// ============================================================================

float3 normalize3(float3 v) {
    float len = sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
    if (len < 1e-10f) return (float3)(0.0f, 0.0f, 1.0f);
    return v / len;
}

float length3(float3 v) {
    return sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
}

float dot3(float3 a, float3 b) {
    return a.x * b.x + a.y * b.y + a.z * b.z;
}

float3 cross3(float3 a, float3 b) {
    return (float3)(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x
    );
}

float fract1(float x) {
    return x - floor(x);
}

// ============================================================================
// Quaternion operations for camera
// ============================================================================

float3 rotateByQuaternion(float3 v, float4 q) {
    float3 qv = (float3)(q.y, q.z, q.w);
    float3 uv = cross3(qv, v);
    float3 uuv = cross3(qv, uv);
    return v + ((uv * q.x) + uuv) * 2.0f;
}

float3 getCameraRay(float2 uv, float fov, float4 quat) {
    float fovScale = tan(fov * 0.5f);
    float3 localRay = normalize3((float3)(uv.x * fovScale, uv.y * fovScale, 1.0f));
    return rotateByQuaternion(localRay, quat);
}

// ============================================================================
// Pseudo-random number generator for DoF sampling
// ============================================================================

float hash(float2 p) {
    return fract1(sin(dot3((float3)(p.x, p.y, 0.0f), (float3)(12.9898f, 78.233f, 45.164f))) * 43758.5453f);
}

float2 randomInDisk(float2 seed) {
    float angle = hash(seed) * 6.28318f;
    float radius = sqrt(hash(seed + (float2)(0.5f, 0.5f)));
    return (float2)(cos(angle) * radius, sin(angle) * radius);
}

// ============================================================================
// Color palette with configurable base
// ============================================================================

float3 palette(float t, float3 baseHue) {
    float3 a = (float3)(0.5f, 0.5f, 0.5f);
    float3 b = (float3)(0.5f, 0.5f, 0.5f);
    float3 c = (float3)(1.0f, 1.0f, 1.0f);
    return a + b * cos(6.28318f * (c * t + baseHue));
}

// ============================================================================
// Fresnel effect (Schlick approximation)
// ============================================================================

float fresnel(float cosTheta, float f0) {
    return f0 + (1.0f - f0) * pow(1.0f - cosTheta, 5.0f);
}

// ============================================================================
// Iteration count to color (for visualization)
// ============================================================================

float3 iterationColor(int iterations, int maxIterations) {
    float t = (float)iterations / (float)maxIterations;
    float3 c;
    if (t < 0.33f) {
        c = mix((float3)(0.0f, 0.0f, 0.0f), (float3)(1.0f, 0.0f, 0.0f), t * 3.0f);
    } else if (t < 0.66f) {
        c = mix((float3)(1.0f, 0.0f, 0.0f), (float3)(1.0f, 1.0f, 0.0f), (t - 0.33f) * 3.0f);
    } else {
        c = mix((float3)(1.0f, 1.0f, 0.0f), (float3)(1.0f, 1.0f, 1.0f), (t - 0.66f) * 3.0f);
    }
    return c;
}