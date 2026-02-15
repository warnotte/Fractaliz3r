/**
 * Fractal Terrain Distance Estimator
 *
 * fBm noise heightfield terrain with domain warping and ridge noise.
 * Uses the standard raymarching pipeline.
 */

// ============================================================================
// Fractal-specific uniforms
// ============================================================================

uniform float terrainHeight;
uniform float terrainFrequency;
uniform int octaves;
uniform float lacunarity;
uniform float terrainRoughness;
uniform float warpStrength;
uniform float ridgeSharpness;
uniform float terrainOffset;

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
// Hash-based value noise (no texture dependency)
// ============================================================================

float terrainHash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float terrainNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);

    // Cubic Hermite interpolation (smoother than linear)
    vec2 u = f * f * (3.0 - 2.0 * f);

    float a = terrainHash(i);
    float b = terrainHash(i + vec2(1.0, 0.0));
    float c = terrainHash(i + vec2(0.0, 1.0));
    float d = terrainHash(i + vec2(1.0, 1.0));

    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// ============================================================================
// fBm with ridge noise and domain warping
// ============================================================================

float terrainFbm(vec2 p) {
    // Domain warping (applied before fBm summation)
    if (warpStrength > 0.001) {
        float wx = terrainNoise(p + vec2(5.2, 1.3)) * 2.0 - 1.0;
        float wy = terrainNoise(p + vec2(1.7, 9.2)) * 2.0 - 1.0;
        p += warpStrength * vec2(wx, wy);
    }

    float value = 0.0;
    float amplitude = 1.0;
    float frequency = 1.0;
    float totalAmplitude = 0.0;

    for (int i = 0; i < octaves; i++) {
        float n = terrainNoise(p * frequency);

        // Mix between smooth noise and ridge noise
        float smoothN = n;
        float ridge = 1.0 - abs(n * 2.0 - 1.0);  // Ridge: invert absolute value
        ridge = ridge * ridge;  // Sharpen ridges

        n = mix(smoothN, ridge, ridgeSharpness);

        value += n * amplitude;
        totalAmplitude += amplitude;
        frequency *= lacunarity;
        amplitude *= terrainRoughness;
    }

    return value / totalAmplitude;
}

float heightAt(vec2 xz) {
    return terrainFbm(xz * terrainFrequency) * terrainHeight + terrainOffset;
}

// ============================================================================
// Terrain DE (full version with orbit traps)
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    float h = heightAt(pos.xz);
    float d = pos.y - h;

    // Orbit traps for coloring
    trap.planeX = abs(fract(pos.x * 0.1) - 0.5);  // Spatial pattern X
    trap.planeY = clamp(h / max(terrainHeight, 0.01), 0.0, 1.0);  // Normalized height
    trap.planeZ = abs(fract(pos.z * 0.1) - 0.5);  // Spatial pattern Z
    trap.minDist = abs(d);
    trap.iterations = octaves;

    // Conservative step factor for safe marching on heightfields
    return d * 0.4;
}

// ============================================================================
// Simple DE (for shadows, AO, normals)
// ============================================================================

float DE_simple(vec3 pos) {
    float h = heightAt(pos.xz);
    return (pos.y - h) * 0.4;
}

// ============================================================================
// Material Color from Orbit Traps
// ============================================================================

vec3 getFactors(OrbitTrap trap) {
    // Structural: height-based (valleys vs peaks)
    float structural = trap.planeY;

    // Flow: spatial patterns for variation
    float flow = (trap.planeX + trap.planeZ) * 0.5 + trap.planeY * 0.3;

    // Detail: proximity to surface gives depth cue
    float iterNorm = clamp(1.0 - trap.minDist * 0.5, 0.0, 1.0);

    return vec3(structural, flow, iterNorm);
}
