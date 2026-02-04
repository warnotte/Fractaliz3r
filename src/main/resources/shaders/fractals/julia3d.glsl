/**
 * Quaternion Julia Set Distance Estimator
 *
 * The 3D Julia set using quaternion mathematics.
 * Formula: q' = q² + c (quaternion multiplication)
 *
 * Reference: https://www.iquilezles.org/www/articles/distancefractals/distancefractals.htm
 */

// ============================================================================
// Fractal-specific uniforms
// ============================================================================

uniform int maxIterations;
uniform float bailout;
// Julia constant (quaternion c = cx + cy*i + cz*j + cw*k)
uniform vec4 juliaC;

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

// Quaternion multiplication: q1 * q2
vec4 qmul(vec4 a, vec4 b) {
    return vec4(
        a.x * b.x - a.y * b.y - a.z * b.z - a.w * b.w,
        a.x * b.y + a.y * b.x + a.z * b.w - a.w * b.z,
        a.x * b.z - a.y * b.w + a.z * b.x + a.w * b.y,
        a.x * b.w + a.y * b.z - a.z * b.y + a.w * b.x
    );
}

// Quaternion square: q²
vec4 qsqr(vec4 q) {
    return vec4(
        q.x * q.x - q.y * q.y - q.z * q.z - q.w * q.w,
        2.0 * q.x * q.y,
        2.0 * q.x * q.z,
        2.0 * q.x * q.w
    );
}

// ============================================================================
// Julia 3D Distance Estimator (full version with orbit traps)
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    // Initialize quaternion from 3D position (w=0 for pure imaginary)
    vec4 q = vec4(pos, 0.0);
    vec4 dq = vec4(1.0, 0.0, 0.0, 0.0);  // Derivative

    trap.minDist = 1e10;
    trap.avgDist = 0.0;
    trap.lastDist = 0.0;
    trap.iterations = 0;

    float r2 = dot(q, q);

    for (int i = 0; i < maxIterations; i++) {
        if (r2 > bailout) break;

        // Derivative: dq' = 2 * q * dq
        dq = 2.0 * qmul(q, dq);

        // Iteration: q' = q² + c
        q = qsqr(q) + juliaC;

        r2 = dot(q, q);

        // Track orbit traps for coloring
        float dist = sqrt(r2);
        trap.minDist = min(trap.minDist, dist);
        trap.avgDist += dist;
        trap.lastDist = dist;
        trap.iterations = i + 1;
    }

    trap.avgDist /= float(max(trap.iterations, 1));

    // Distance estimation formula
    float r = sqrt(r2);
    float dr = length(dq);
    return 0.5 * r * log(r) / dr;
}

// ============================================================================
// Simple DE (for shadows, AO, normals)
// ============================================================================

float DE_simple(vec3 pos) {
    vec4 q = vec4(pos, 0.0);
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
    return 0.5 * r * log(r) / dr;
}

// ============================================================================
// Material Color from Orbit Traps
// ============================================================================

vec3 getFactors(OrbitTrap trap) {
    // X: Structure (Divergence / Last distance)
    // High values mean the point is escaping fast
    float structural = clamp(trap.lastDist * 0.2, 0.0, 1.0);

    // Y: Flow (Stability / Average distance)
    // Low values mean the orbit stayed close to origin
    float flow = smoothstep(0.0, 2.0, trap.avgDist);

    // Z: Detail (Iterations)
    float detail = float(trap.iterations) / float(max(maxIterations, 1));

    return vec3(structural, flow, detail);
}
