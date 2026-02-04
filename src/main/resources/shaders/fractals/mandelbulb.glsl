/**
 * Mandelbulb Distance Estimator
 *
 * Only fractal-specific code:
 * - OrbitTrap struct
 * - DE() with orbit traps
 * - DE_simple() for shadows/AO
 * - getColor() for material
 *
 * Generic raytracing handled by raytracer.glsl
 */

// ============================================================================
// Fractal-specific uniforms
// ============================================================================

uniform float power;
uniform int maxIterations;
uniform float bailout;

// ============================================================================
// Orbit Trap Structure
// ============================================================================

struct OrbitTrap {
    float minDist;      // Point trap at origin
    float planeX;       // Distance to YZ plane (x=0)
    float planeY;       // Distance to XZ plane (y=0)
    float planeZ;       // Distance to XY plane (z=0)
    int iterations;
};

// ============================================================================
// Mandelbulb Distance Estimator (full version with orbit traps)
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    vec3 z = pos;
    float dr = 1.0;
    float r = 0.0;

    trap.minDist = 1e10;
    trap.planeX = 1e10;
    trap.planeY = 1e10;
    trap.planeZ = 1e10;
    trap.iterations = 0;

    for (int i = 0; i < maxIterations; i++) {
        r = length(z);

        if (r > bailout) break;

        // Convert to polar coordinates
        float theta = acos(z.z / r);
        float phi = atan(z.y, z.x);

        // Update derivative
        dr = pow(r, power - 1.0) * power * dr + 1.0;

        // Scale and rotate the point
        float zr = pow(r, power);
        theta = theta * power;
        phi = phi * power;

        // Convert back to cartesian
        z = zr * vec3(
            sin(theta) * cos(phi),
            sin(theta) * sin(phi),
            cos(theta)
        );
        z += pos;

        // Track orbit traps for coloring (IQ style)
        // Point trap: distance to origin
        trap.minDist = min(trap.minDist, length(z));
        // Plane traps: distance to coordinate planes
        trap.planeX = min(trap.planeX, abs(z.x));
        trap.planeY = min(trap.planeY, abs(z.y));
        trap.planeZ = min(trap.planeZ, abs(z.z));
        trap.iterations = i + 1;
    }

    return 0.5 * log(r) * r / dr;
}

// ============================================================================
// Simple DE (for shadows, AO, normals)
// ============================================================================

float DE_simple(vec3 pos) {
    vec3 z = pos;
    float dr = 1.0;
    float r = 0.0;

    for (int i = 0; i < maxIterations; i++) {
        r = length(z);
        if (r > bailout) break;

        float theta = acos(z.z / r);
        float phi = atan(z.y, z.x);
        dr = pow(r, power - 1.0) * power * dr + 1.0;

        float zr = pow(r, power);
        theta = theta * power;
        phi = phi * power;

        z = zr * vec3(
            sin(theta) * cos(phi),
            sin(theta) * sin(phi),
            cos(theta)
        );
        z += pos;
    }

    return 0.5 * log(r) * r / dr;
}

// ============================================================================
// Material Color from Orbit Traps (Inigo Quilez style)
// ============================================================================
// Uses 4 orbit traps like IQ's Mandelbulb:
// - Point trap at origin: multiplicative factor (pseudo-AO)
// - 3 plane traps (x=0, y=0, z=0): each mixes a different color
//
// Color sampling is done at a fixed offset from surface (in raytracer.glsl)
// so we can use simple exponential falloff without quality-dependent issues.
// ============================================================================

vec3 getColor(OrbitTrap trap) {
    // Exponential falloff for plane traps (IQ style)
    // At fixed offset from surface, these values are consistent
    float trapX = exp(-trap.planeX * 2.0);
    float trapY = exp(-trap.planeY * 2.0);
    float trapZ = exp(-trap.planeZ * 2.0);

    // Point trap for pseudo-AO effect
    float pointTrap = 1.0 - exp(-trap.minDist * 0.5);

    // Base color from palette using iteration count
    float iterNorm = float(trap.iterations) / float(max(maxIterations, 1));
    vec3 baseColor = fractalPalette(iterNorm);

    // Three tint colors for each plane trap
    vec3 tintX = vec3(1.0, 0.7, 0.2);  // Gold/Amber
    vec3 tintY = vec3(0.4, 0.2, 0.9);  // Deep Purple
    vec3 tintZ = vec3(0.2, 0.9, 0.8);  // Teal/Cyan

    // Mix plane trap colors additively (IQ approach)
    vec3 color = baseColor;
    color = mix(color, color * tintX + tintX * 0.3, trapX * 0.6);
    color = mix(color, color * tintY + tintY * 0.3, trapY * 0.6);
    color = mix(color, color * tintZ + tintZ * 0.3, trapZ * 0.6);

    // Point trap as multiplicative factor (darkens crevices)
    color *= 0.6 + 0.4 * pointTrap;

    return color;
}
