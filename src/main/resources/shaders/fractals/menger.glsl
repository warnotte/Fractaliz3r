/**
 * Menger Sponge Distance Estimator
 *
 * Classic IQ algorithm with offset and per-iteration rotation.
 */

// ============================================================================
// Fractal-specific uniforms
// ============================================================================

uniform int maxIterations;
uniform float scale;
uniform vec3 offset;
uniform float rotAngle;

// ============================================================================
// Orbit Trap Structure
// ============================================================================

struct OrbitTrap {
    float minDist;
    vec3 plane;
    float trap;
    int iterations;
};

// ============================================================================
// GLSL-style mod (x - y * floor(x/y))
// ============================================================================

vec3 glsl_mod3(vec3 x, float y) {
    return x - y * floor(x / y);
}

// ============================================================================
// Box SDF
// ============================================================================

float sdBox(vec3 p, vec3 b) {
    vec3 d = abs(p) - b;
    return min(max(d.x, max(d.y, d.z)), 0.0) + length(max(d, vec3(0.0)));
}

// ============================================================================
// Cross SDF (the shape we subtract at each iteration)
// ============================================================================

float sdCross(vec3 p) {
    float da = max(abs(p.x), abs(p.y));
    float db = max(abs(p.y), abs(p.z));
    float dc = max(abs(p.z), abs(p.x));
    return min(min(da, db), dc) - 1.0;
}

// ============================================================================
// Per-iteration rotation (XZ plane)
// ============================================================================

vec3 iterRotate(vec3 p, float angle) {
    if (abs(angle) < 0.001) return p;
    float c = cos(angle);
    float s = sin(angle);
    return vec3(c * p.x + s * p.z, p.y, -s * p.x + c * p.z);
}

// ============================================================================
// Menger Sponge Distance Estimator (full version with orbit traps)
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    float d = sdBox(pos, vec3(1.0));

    trap.minDist = 1e10;
    trap.trap = 0.0;
    trap.plane = vec3(0.0);
    trap.iterations = 0;

    float s = 1.0;
    float rad = radians(rotAngle);

    for (int m = 0; m < maxIterations; m++) {
        vec3 rp = iterRotate(pos, rad * float(m + 1));
        vec3 a = glsl_mod3(rp * s, 2.0) - offset;
        s *= scale;

        vec3 r = vec3(1.0) - scale * abs(a);
        float c = sdCross(r) / s;
        d = max(d, c);

        trap.minDist = min(trap.minDist, length(r));
        trap.plane = trap.plane + abs(r) / s;
        trap.trap = trap.trap + length(a);
        trap.iterations = m + 1;
    }

    trap.trap /= float(maxIterations);

    return d;
}

// ============================================================================
// Simple DE (for shadows, AO, normals)
// ============================================================================

float DE_simple(vec3 pos) {
    float d = sdBox(pos, vec3(1.0));
    float s = 1.0;
    float rad = radians(rotAngle);

    for (int m = 0; m < maxIterations; m++) {
        vec3 rp = iterRotate(pos, rad * float(m + 1));
        vec3 a = glsl_mod3(rp * s, 2.0) - offset;
        s *= scale;
        vec3 r = vec3(1.0) - scale * abs(a);
        float c = sdCross(r) / s;
        d = max(d, c);
    }

    return d;
}

// ============================================================================
// Material Color from Orbit Traps
// ============================================================================

vec3 getFactors(OrbitTrap trap) {
    // X: Structure (Geometric cross trap)
    float structural = smoothstep(0.3, 0.8, trap.trap);

    // Y: Flow (Combined plane traps for internal color variation)
    float p = (trap.plane.x + trap.plane.y + trap.plane.z) * 0.33;
    float flow = sin(p * 5.0) * 0.5 + 0.5;

    // Z: Detail (Iterations)
    float detail = float(trap.iterations) / float(max(maxIterations, 1));

    return vec3(structural, flow, detail);
}
