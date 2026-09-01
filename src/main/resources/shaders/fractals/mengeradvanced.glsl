/**
 * Menger Advanced Distance Estimator
 *
 * Hybrid Menger sponge with inner box fold and Z-scale stretch.
 * Based on GMT-fractals implementation.
 */

// ============================================================================
// Fractal-specific uniforms
// ============================================================================

uniform int maxIterations;
uniform float scale;
uniform float offset;
uniform float rotX;
uniform float rotZ;
uniform float innerFold;
uniform float zScale;

// ============================================================================
// Orbit Trap Structure
// ============================================================================

struct OrbitTrap {
    float minDist;
    float planeX;
    float planeY;
    float planeZ;
    int iterations;
};

// ============================================================================
// Rotation helpers
// ============================================================================

vec3 rotateXZ(vec3 p, float ax, float az) {
    if (abs(ax) > 0.001) {
        float cx = cos(ax); float sx = sin(ax);
        p = vec3(p.x, cx * p.y - sx * p.z, sx * p.y + cx * p.z);
    }
    if (abs(az) > 0.001) {
        float cz = cos(az); float sz = sin(az);
        p = vec3(cz * p.x - sz * p.y, sz * p.x + cz * p.y, p.z);
    }
    return p;
}

// ============================================================================
// Menger Advanced DE (full version with orbit traps)
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    trap.minDist = 1e10;
    trap.planeX = 0.0;
    trap.planeY = 0.0;
    trap.planeZ = 0.0;
    trap.iterations = 0;

    vec3 z = pos;
    float dr = 1.0;

    for (int i = 0; i < maxIterations + gExtraIterations; i++) {
        // Per-iteration rotation (negate: rotating space vs object)
        z = rotateXZ(z, -rotX, -rotZ);

        // Abs fold + sort (Menger fold)
        z = abs(z);
        if (z.x < z.y) z = vec3(z.y, z.x, z.z);
        if (z.x < z.z) z = vec3(z.z, z.y, z.x);
        if (z.y < z.z) z = vec3(z.x, z.z, z.y);

        // Inner box fold
        z = vec3(z.x, z.y, z.z - innerFold * (z.z - offset));

        // Scale and offset
        z = z * scale - offset * (scale - 1.0);
        dr = dr * scale;

        // Z-scale stretch
        z = vec3(z.x, z.y, z.z * zScale);
        dr = dr * zScale;

        // Orbit traps
        float d = length(z);
        trap.minDist = min(trap.minDist, d);
        trap.planeX = trap.planeX + abs(z.x) / dr;
        trap.planeY = trap.planeY + abs(z.y) / dr;
        trap.planeZ = trap.planeZ + abs(z.z) / dr;
        trap.iterations = i + 1;
    }

    return (length(z) - offset) / dr;
}

// ============================================================================
// Simple DE (for shadows, AO, normals)
// ============================================================================

float DE_simple(vec3 pos) {
    vec3 z = pos;
    float dr = 1.0;

    for (int i = 0; i < maxIterations + gExtraIterations; i++) {
        z = rotateXZ(z, -rotX, -rotZ);

        z = abs(z);
        if (z.x < z.y) z = vec3(z.y, z.x, z.z);
        if (z.x < z.z) z = vec3(z.z, z.y, z.x);
        if (z.y < z.z) z = vec3(z.x, z.z, z.y);

        z = vec3(z.x, z.y, z.z - innerFold * (z.z - offset));

        z = z * scale - offset * (scale - 1.0);
        dr = dr * scale;

        z = vec3(z.x, z.y, z.z * zScale);
        dr = dr * zScale;
    }

    return (length(z) - offset) / dr;
}

// ============================================================================
// Material Color from Orbit Traps
// ============================================================================

vec3 getFactors(OrbitTrap trap) {
    float structural = 1.0 - exp(-trap.minDist * 0.5);
    float p = (trap.planeX + trap.planeY + trap.planeZ) * 0.33;
    float flow = sin(p * 5.0) * 0.5 + 0.5;
    float detail = float(trap.iterations) / float(max(maxIterations + gExtraIterations, 1));
    return vec3(structural, flow, detail);
}
