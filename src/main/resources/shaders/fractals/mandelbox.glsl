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
    // Normalize trap values for better control
    float foldIntensity = trap.avgFold * 0.5;
    float sphereIntensity = trap.sphereHits * 0.15;
    float iterNorm = float(trap.iterations) / float(max(maxIterations, 1));

    // Base color from palette
    vec3 baseColor = fractalPalette(iterNorm * 0.8 + foldIntensity * 0.2);

    // Structural Tints
    vec3 coreColor = vec3(1.0, 0.6, 0.2);   // Orange core (high folding)
    vec3 shellColor = vec3(0.1, 0.7, 0.8);  // Cyan shell (sphere hits)
    vec3 deepColor = vec3(0.2, 0.1, 0.5);   // Deep purple voids

    // Mix based on trap data
    vec3 color = baseColor;
    
    // Highlight areas with many sphere folds (often the "bulbs")
    color = mix(color, shellColor, clamp(sphereIntensity, 0.0, 0.8));
    
    // Highlight high-folding areas (complex details)
    color = mix(color, coreColor, clamp(foldIntensity * 0.5, 0.0, 0.6));
    
    // Darken deep iterations
    color = mix(color, deepColor, iterNorm * 0.3);

    // AO-like darkening from min distance
    float proximity = 1.0 - exp(-trap.minDist * 0.2);
    color *= 0.5 + 0.5 * proximity;

    return color;
}
