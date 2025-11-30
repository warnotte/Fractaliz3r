/**
 * Menger Sponge Distance Estimator
 *
 * Classic IQ algorithm implementation
 */

// ============================================================================
// Fractal-specific uniforms
// ============================================================================

uniform int maxIterations;
uniform float scale;
uniform vec3 offset;

// ============================================================================
// Orbit Trap Structure
// ============================================================================

struct OrbitTrap {
    float minDist;
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
// Menger Sponge Distance Estimator (full version with orbit traps)
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    float d = sdBox(pos, vec3(1.0));

    trap.minDist = 1e10;
    trap.trap = 0.0;
    trap.iterations = 0;

    float s = 1.0;

    for (int m = 0; m < maxIterations; m++) {
        vec3 a = glsl_mod3(pos * s, 2.0) - vec3(1.0);
        s *= scale;

        vec3 r = vec3(1.0) - scale * abs(a);
        float c = sdCross(r) / s;
        d = max(d, c);

        trap.minDist = min(trap.minDist, length(r));
        trap.trap += length(a);
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

    for (int m = 0; m < maxIterations; m++) {
        vec3 a = glsl_mod3(pos * s, 2.0) - vec3(1.0);
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

vec3 getColor(OrbitTrap trap) {
    float t = trap.trap * 0.5 + trap.minDist * 0.3;
    return fractalPalette(t);
}
