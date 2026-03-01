/**
 * Quaternion Julia 4D Distance Estimator
 *
 * Full 4D quaternion Julia set with hyperplane slicing and 4D rotations.
 * Extends julia3d.glsl with sliceW parameter and XW/YW/ZW rotation planes
 * to explore cross-sections of the 4D structure.
 *
 * Reference: https://www.iquilezles.org/www/articles/distancefractals/distancefractals.htm
 */

// ============================================================================
// Fractal-specific uniforms
// ============================================================================

uniform int maxIterations;
uniform float bailout;
uniform vec4 juliaC;

// 4D exploration
uniform float sliceW;
uniform float rotXW;
uniform float rotYW;
uniform float rotZW;

// ============================================================================
// Orbit Trap Structure
// ============================================================================

struct OrbitTrap {
    float minDist;
    float avgDist;
    float lastDist;
    int iterations;
};

// ============================================================================
// Quaternion Operations
// ============================================================================

vec4 qmul(vec4 a, vec4 b) {
    return vec4(
        a.x * b.x - a.y * b.y - a.z * b.z - a.w * b.w,
        a.x * b.y + a.y * b.x + a.z * b.w - a.w * b.z,
        a.x * b.z - a.y * b.w + a.z * b.x + a.w * b.y,
        a.x * b.w + a.y * b.z - a.z * b.y + a.w * b.x
    );
}

vec4 qsqr(vec4 q) {
    return vec4(
        q.x * q.x - q.y * q.y - q.z * q.z - q.w * q.w,
        2.0 * q.x * q.y,
        2.0 * q.x * q.z,
        2.0 * q.x * q.w
    );
}

// ============================================================================
// 4D Rotation (XW, YW, ZW planes)
// ============================================================================

vec4 apply4DRotation(vec4 q) {
    float c, s, tmp;

    // XW rotation
    if (abs(rotXW) > 0.0001) {
        c = cos(rotXW); s = sin(rotXW);
        tmp = c * q.x - s * q.w;
        q.w = s * q.x + c * q.w;
        q.x = tmp;
    }

    // YW rotation
    if (abs(rotYW) > 0.0001) {
        c = cos(rotYW); s = sin(rotYW);
        tmp = c * q.y - s * q.w;
        q.w = s * q.y + c * q.w;
        q.y = tmp;
    }

    // ZW rotation
    if (abs(rotZW) > 0.0001) {
        c = cos(rotZW); s = sin(rotZW);
        tmp = c * q.z - s * q.w;
        q.w = s * q.z + c * q.w;
        q.z = tmp;
    }

    return q;
}

// ============================================================================
// Quaternion Julia 4D Distance Estimator (full version with orbit traps)
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    vec4 q = apply4DRotation(vec4(pos, sliceW));
    vec4 dq = vec4(1.0, 0.0, 0.0, 0.0);

    trap.minDist = 1e10;
    trap.avgDist = 0.0;
    trap.lastDist = 0.0;
    trap.iterations = 0;

    float r2 = dot(q, q);

    for (int i = 0; i < maxIterations; i++) {
        if (r2 > bailout) break;

        dq = 2.0 * qmul(q, dq);
        q = qsqr(q) + juliaC;

        r2 = dot(q, q);

        float dist = sqrt(r2);
        trap.minDist = min(trap.minDist, dist);
        trap.avgDist += dist;
        trap.lastDist = dist;
        trap.iterations = i + 1;
    }

    trap.avgDist /= float(max(trap.iterations, 1));

    float r = sqrt(r2);
    float dr = length(dq);
    float de = 0.5 * r * log(r) / dr;
    float rPos = length(pos);
    if (rPos > 2.0 * bailout) de = min(de, rPos - bailout);
    return de;
}

// ============================================================================
// Simple DE (for shadows, AO, normals)
// ============================================================================

float DE_simple(vec3 pos) {
    vec4 q = apply4DRotation(vec4(pos, sliceW));
    vec4 dq = vec4(1.0, 0.0, 0.0, 0.0);

    float r2 = dot(q, q);

    for (int i = 0; i < maxIterations; i++) {
        if (r2 > bailout) break;

        dq = 2.0 * qmul(q, dq);
        q = qsqr(q) + juliaC;
        r2 = dot(q, q);
    }

    float r = sqrt(r2);
    float dr = length(dq);
    float de = 0.5 * r * log(r) / dr;
    float rPos = length(pos);
    if (rPos > 2.0 * bailout) de = min(de, rPos - bailout);
    return de;
}

// ============================================================================
// Material Color from Orbit Traps
// ============================================================================

vec3 getFactors(OrbitTrap trap) {
    float structural = clamp(trap.lastDist * 0.2, 0.0, 1.0);
    float flow = smoothstep(0.0, 2.0, trap.avgDist);
    float detail = float(trap.iterations) / float(max(maxIterations, 1));
    return vec3(structural, flow, detail);
}
