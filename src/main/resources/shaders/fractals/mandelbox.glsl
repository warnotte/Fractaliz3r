/**
 * Mandelbox Distance Estimator
 *
 * Classic box fold + sphere fold fractal
 */

// ============================================================================
// Fractal-specific uniforms
// ============================================================================

uniform float scale;
uniform float minRadius;
uniform float fixedRadius;
uniform float foldingLimit;
uniform int maxIterations;

// ============================================================================
// Orbit Trap Structure
// ============================================================================

struct OrbitTrap {
    float minDist;
    float avgFold;
    float sphereHits;
    int iterations;
};

// ============================================================================
// Box Fold Operation
// ============================================================================

vec3 boxFold(vec3 z, float limit) {
    return clamp(z, -limit, limit) * 2.0 - z;
}

// ============================================================================
// Sphere Fold Operation
// ============================================================================

void sphereFold(inout vec3 z, inout float dz) {
    float minR2 = minRadius * minRadius;
    float fixR2 = fixedRadius * fixedRadius;
    float r2 = dot(z, z);

    if (r2 < minR2) {
        float scale = fixR2 / minR2;
        z *= scale;
        dz *= scale;
    } else if (r2 < fixR2) {
        float scale = fixR2 / r2;
        z *= scale;
        dz *= scale;
    }
}

// ============================================================================
// Mandelbox Distance Estimator (full version with orbit traps)
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    vec3 z = pos;
    vec3 offset = pos;
    float dz = 1.0;

    trap.minDist = 1e10;
    trap.avgFold = 0.0;
    trap.sphereHits = 0.0;
    trap.iterations = 0;

    for (int i = 0; i < maxIterations; i++) {
        // Box fold
        vec3 oldZ = z;
        z = boxFold(z, foldingLimit);
        float foldAmount = length(z - oldZ);
        trap.avgFold += foldAmount;

        // Sphere fold
        float r2Before = dot(z, z);
        sphereFold(z, dz);
        float r2After = dot(z, z);
        if (r2After != r2Before) {
            trap.sphereHits += 1.0;
        }

        // Scale and translate
        z = z * scale + offset;
        dz = dz * abs(scale) + 1.0;

        // Track orbit
        float dist = length(z);
        trap.minDist = min(trap.minDist, dist);

        if (dist > 1000.0) break;

        trap.iterations = i + 1;
    }

    trap.avgFold /= float(max(trap.iterations, 1));

    return length(z) / abs(dz);
}

// ============================================================================
// Simple DE (for shadows, AO, normals)
// ============================================================================

float DE_simple(vec3 pos) {
    vec3 z = pos;
    vec3 offset = pos;
    float dz = 1.0;

    for (int i = 0; i < maxIterations; i++) {
        z = boxFold(z, foldingLimit);
        sphereFold(z, dz);
        z = z * scale + offset;
        dz = dz * abs(scale) + 1.0;

        if (dot(z, z) > 1000000.0) break;
    }

    return length(z) / abs(dz);
}

// ============================================================================
// Material Color from Orbit Traps
// ============================================================================

vec3 getColor(OrbitTrap trap) {
    float t1 = trap.minDist * 0.1;
    float t2 = trap.avgFold * 0.5;
    float t3 = trap.sphereHits * 0.1;
    float t4 = float(trap.iterations) * 0.05;

    float combined = t1 * 0.25 + t2 * 0.25 + t3 * 0.25 + t4 * 0.25;
    combined += abs(scale) * 0.1;

    return fractalPalette(combined);
}
