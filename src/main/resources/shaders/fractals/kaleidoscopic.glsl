/**
 * Kaleidoscopic IFS Distance Estimator
 *
 * Based on the classic Sierpinski tetrahedron folding algorithm
 * Reference: Syntopia blog - Distance Estimated 3D Fractals (III): Folding Space
 */

// ============================================================================
// Fractal-specific uniforms
// ============================================================================

uniform int maxIterations;
uniform float scale;
uniform float ifsOffset;  // Classic KIFS uses scalar offset
uniform float foldAngleX;
uniform float foldAngleY;

// ============================================================================
// Orbit Trap Structure
// ============================================================================

struct OrbitTrap {
    float minDist;
    float sumDist;
    float avgFold;
    int iterations;
};

// ============================================================================
// Kaleidoscopic IFS Distance Estimator (full version with orbit traps)
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    trap.minDist = 1e10;
    trap.sumDist = 0.0;
    trap.avgFold = 0.0;
    trap.iterations = 0;

    vec3 z = pos;
    float foldSum = 0.0;

    int n;
    for (n = 0; n < maxIterations; n++) {
        // Classic KIFS folding - creates tetrahedral/kaleidoscopic symmetry
        // These are conditional reflections across planes

        // Fold 1: plane with normal (1, 1, 0)
        if (z.x + z.y < 0.0) {
            z.xy = -z.yx;
            foldSum += 1.0;
        }

        // Fold 2: plane with normal (1, 0, 1)
        if (z.x + z.z < 0.0) {
            z.xz = -z.zx;
            foldSum += 1.0;
        }

        // Fold 3: plane with normal (0, 1, 1)
        if (z.y + z.z < 0.0) {
            z.yz = -z.zy;
            foldSum += 1.0;
        }

        // Optional: apply small rotations for variety
        if (abs(foldAngleX) > 0.0001) {
            float c = cos(foldAngleX);
            float s = sin(foldAngleX);
            z = vec3(z.x, c * z.y - s * z.z, s * z.y + c * z.z);
        }
        if (abs(foldAngleY) > 0.0001) {
            float c = cos(foldAngleY);
            float s = sin(foldAngleY);
            z = vec3(c * z.x + s * z.z, z.y, -s * z.x + c * z.z);
        }

        // Scale and translate - the key IFS transformation
        z = z * scale - ifsOffset * (scale - 1.0);

        // Track orbit traps for coloring
        float dist = length(z);
        trap.minDist = min(trap.minDist, dist);
        trap.sumDist += dist;
    }

    trap.iterations = n;
    trap.avgFold = foldSum / float(n);

    // Distance estimation formula for KIFS
    return length(z) * pow(scale, -float(n));
}

// ============================================================================
// Simple DE (for shadows, AO, normals)
// ============================================================================

float DE_simple(vec3 pos) {
    vec3 z = pos;

    int n;
    for (n = 0; n < maxIterations; n++) {
        // Fold 1
        if (z.x + z.y < 0.0) z.xy = -z.yx;
        // Fold 2
        if (z.x + z.z < 0.0) z.xz = -z.zx;
        // Fold 3
        if (z.y + z.z < 0.0) z.yz = -z.zy;

        // Optional rotations
        if (abs(foldAngleX) > 0.0001) {
            float c = cos(foldAngleX);
            float s = sin(foldAngleX);
            z = vec3(z.x, c * z.y - s * z.z, s * z.y + c * z.z);
        }
        if (abs(foldAngleY) > 0.0001) {
            float c = cos(foldAngleY);
            float s = sin(foldAngleY);
            z = vec3(c * z.x + s * z.z, z.y, -s * z.x + c * z.z);
        }

        // Scale and translate
        z = z * scale - ifsOffset * (scale - 1.0);
    }

    return length(z) * pow(scale, -float(n));
}

// ============================================================================
// Material Color from Orbit Traps
// ============================================================================

vec3 getColor(OrbitTrap trap) {
    // Normalize iteration count
    float iterNorm = float(trap.iterations) / float(max(maxIterations, 1));
    
    // Geometric factors
    float geomFactor = trap.minDist * 2.0 + trap.avgFold;
    
    // Base palette
    vec3 color = fractalPalette(geomFactor * 0.1 + iterNorm * 0.5);

    // Neon Tints
    vec3 neonPink = vec3(1.0, 0.1, 0.6);
    vec3 electricBlue = vec3(0.1, 0.6, 1.0);
    
    // Use sumDist to create large-scale color variation
    float variation = sin(trap.sumDist * 0.2);
    
    // Mix based on variation
    color = mix(color, neonPink, smoothstep(0.2, 0.8, variation) * 0.4);
    color = mix(color, electricBlue, smoothstep(-0.8, -0.2, variation) * 0.4);
    
    // Highlight edges (low min dist)
    float edge = exp(-trap.minDist * 4.0);
    color += vec3(0.5) * edge;

    return color;
}
