/**
 * Polyhedral IFS (Iterated Function Systems)
 *
 * Adapted from legacy fract.frag (Subblue/Tom Beddard algorithm)
 */

uniform int polyType; // 0: Octa, 1: Dodeca, 2: Icosa, 3: Tetra
uniform int maxIterations;
uniform float scale;
uniform vec3 offset;
uniform vec3 shift;

// Rotation matrices from Java
uniform mat3 fractalRotation1;
uniform mat3 fractalRotation2;

const float phi = 1.61803398875;

struct OrbitTrap {
    float minDist;
    float sumDist;
    vec3 plane;
    int iterations;
};

// ============================================================================
// Polyhedral Formulas
// ============================================================================

// Dodecahedron constants
const float IKVNORM = 0.19098593171; // 1.0 / sqrt( (phi*(1+phi))^2 + (phi*phi-1)^2 + (1+phi)^2 )
const vec3 phi3 = vec3(0.5, 0.5 / phi, 0.5 * phi);
const vec3 c_3  = vec3(phi * (1.0 + phi) * IKVNORM, (phi * phi - 1.0) * IKVNORM, (1.0 + phi) * IKVNORM);

// Icosahedron constants (pre-normalized: |n1|=2, |n2|=2*phi, |n3|=1)
const vec3 n1_ico = vec3(-0.80901699437, 0.30901699437, 0.5);
const vec3 n2_ico = vec3( 0.30901699437, -0.5, 0.80901699437);
const vec3 n3_ico = vec3(0.0, 0.0, -1.0);

float polyDE(vec3 p, out OrbitTrap trap) {
    trap.minDist = 1e10;
    trap.sumDist = 0.0;
    trap.plane = vec3(0.0);
    
    vec3 w = p;
    vec3 scale_offset = offset * (scale - 1.0);
    
    int i;
    for (i = 0; i < maxIterations; i++) {
        w *= fractalRotation1;

        if (polyType == 0) { // Octahedral
            // Box fold (abs) required before sorting for octahedral symmetry
            w = abs(w + shift) - shift;
            // Explicit swaps (avoid w.xy = w.yx which triggers NVIDIA C9999)
            if (w.x < w.y) { float tmp = w.x; w = vec3(w.y, tmp, w.z); }
            if (w.x < w.z) { float tmp = w.x; w = vec3(w.z, w.y, tmp); }
            if (w.y < w.z) { float tmp = w.y; w = vec3(w.x, w.z, tmp); }
        }
        else if (polyType == 1) { // Dodecahedron
            // 5 reflection fold planes handle symmetry directly (no abs needed)
            float t;
            t = w.x * phi3.z + w.y * phi3.y - w.z * phi3.x;
            if (t < 0.0) w += vec3(-2.0, -2.0, 2.0) * t * phi3.zyx;

            t = -w.x * phi3.x + w.y * phi3.z + w.z * phi3.y;
            if (t < 0.0) w += vec3(2.0, -2.0, -2.0) * t * phi3.xzy;

            t = w.x * phi3.y - w.y * phi3.x + w.z * phi3.z;
            if (t < 0.0) w += vec3(-2.0, 2.0, -2.0) * t * phi3.yxz;

            t = -w.x * c_3.x + w.y * c_3.y + w.z * c_3.z;
            if (t < 0.0) w += vec3(2.0, -2.0, -2.0) * t * c_3.xyz;

            t = w.x * c_3.z - w.y * c_3.x + w.z * c_3.y;
            if (t < 0.0) w += vec3(-2.0, 2.0, -2.0) * t * c_3.zxy;
        }
        else if (polyType == 2) { // Icosahedron
            // Icosahedron uses its own abs for symmetry
            w = abs(w);
            float t;
            t = dot(w, n1_ico); if (t > 0.0) w -= 2.0 * t * n1_ico;
            t = dot(w, n2_ico); if (t > 0.0) w -= 2.0 * t * n2_ico;
            t = dot(w, n3_ico); if (t > 0.0) w -= 2.0 * t * n3_ico;
            t = dot(w, n2_ico); if (t > 0.0) w -= 2.0 * t * n2_ico;
        }
        else if (polyType == 3) { // Tetrahedron
            // Conditional negate-swap folds (NO abs - it would make conditions always false)
            // Explicit vec3 constructors to avoid NVIDIA C9999 on swizzled assign
            if (w.x + w.y < 0.0) { float tmp = w.x; w = vec3(-w.y, -tmp, w.z); }
            if (w.x + w.z < 0.0) { float tmp = w.x; w = vec3(-w.z, w.y, -tmp); }
            if (w.y + w.z < 0.0) { float tmp = w.y; w = vec3(w.x, -w.z, -tmp); }
        }

        w *= fractalRotation2;
        
        // Use a weighted sum for plane traps to capture variation across all iterations
        trap.plane += abs(w) / pow(scale, float(i) * 0.5);
        
        float d2 = dot(w, w);
        trap.minDist = min(trap.minDist, d2);
        trap.sumDist += d2 / pow(scale, float(i) * 0.5);

        w *= scale;
        w -= scale_offset;
    }
    
    trap.iterations = i;
    
    // Generic Polyhedral DE
    return (length(w) - 2.0) * pow(scale, -float(i));
}

// ============================================================================
// Standard DE Bridges
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    return polyDE(pos, trap);
}

float DE_simple(vec3 pos) {
    OrbitTrap trap;
    return polyDE(pos, trap);
}

vec3 getFactors(OrbitTrap trap) {
    // Structural: More aggressive falloff to highlight small geometric details
    float structural = 1.0 - exp(-sqrt(trap.minDist) * 5.0);
    
    // Flow: Use the weighted average of absolute coordinates
    float p = (trap.plane.x + trap.plane.y + trap.plane.z) * 0.33;
    float flow = sin(p * 2.0) * 0.5 + 0.5;
    
    // Detail: Iteration count
    float detail = float(trap.iterations) / float(max(maxIterations, 1));
    return vec3(structural, flow, detail);
}
