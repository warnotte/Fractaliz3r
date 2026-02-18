/**
 * Common GLSL utilities for Fractaliz3r
 *
 * This file is included first in all fractal shaders.
 */

// ============================================================================
// Constants
// ============================================================================

const float PI = 3.14159265359;
const float TAU = 6.28318530718;
const float MAX_DISTANCE = 100.0;
const float MIN_EPSILON = 0.000001;
const float STEP_FACTOR = 0.9;
const float NORMAL_EPSILON = 0.00001;

const int RENDER_MODE_FINAL = 0;
const int RENDER_MODE_NORMALS = 1;
const int RENDER_MODE_DEPTH = 2;
const int RENDER_MODE_AO = 3;
const int RENDER_MODE_SHADOW = 4;
const int RENDER_MODE_DIFFUSE = 5;
const int RENDER_MODE_SPECULAR = 6;
const int RENDER_MODE_ORBIT_TRAP = 7;
const int RENDER_MODE_ITERATIONS = 8;

const int MATERIAL_LAMBERTIAN = 0;
const int MATERIAL_METALLIC = 1;
const int MATERIAL_GLASS = 2;

const int EXTRA_LIGHT_OFF = 0;
const int EXTRA_LIGHT_DIRECTIONAL = 1;
const int EXTRA_LIGHT_POINT = 2;
const int EXTRA_LIGHT_SPOT = 3;

// ============================================================================
// Common Uniforms
// ============================================================================

uniform vec2 resolution;
uniform vec2 tileOffset;       // [0,1] tile position in full image (default: 0,0)
uniform vec2 tileScale;        // [0,1] tile fraction of full image (default: 1,1)
uniform vec2 fullResolution;   // full image resolution in pixels (default: = resolution)
uniform int sampleIndex;
uniform float time;

uniform vec3 camPos;
uniform vec4 camQuat;
uniform float fov;
uniform int projectionMode;

uniform vec3 lightDir;
uniform vec3 lightColor;
uniform float lightIntensity;
uniform vec3 ambientColor;
uniform float ambientIntensity;
uniform int extraLightType;
uniform int extraLightAttachToCamera;
uniform vec3 extraLightPos;
uniform vec3 extraLightDir;
uniform vec3 extraLightColor;
uniform float extraLightIntensity;
uniform float extraLightRange;
uniform float extraLightAreaRadius;
uniform float extraLightConeAngle;
uniform float extraLightConeSoftness;

uniform vec3 baseHue;
uniform int paletteIndex;
uniform float colorStrength;
uniform float paletteOffset;
uniform int coloringMode;

uniform float qualityMultiplier;
uniform int maxRaySteps;
uniform float baseEpsilon;

uniform float shadowSoftness;
uniform int shadowSteps;
uniform int aoSteps;
uniform float aoIntensity;
uniform float glowIntensity;
uniform float specularIntensity;
uniform float specularPower;

uniform int dofEnabled;
uniform float focalDistance;
uniform float aperture;
uniform int dofSamples;
uniform float anamorphicRatio;
uniform int bokehBlades;
uniform float bokehRotation;
uniform float opticalVignettingStrength;
uniform int tiltShiftEnabled;
uniform float tiltAngleX;
uniform float tiltAngleY;
uniform float dofChromaticStrength;

uniform int renderMode;

uniform int pathTracingEnabled;
uniform int maxBounces;
uniform float roughness;
uniform float skyIntensity;
uniform float indirectMultiplier;

uniform int materialType;
uniform float metalness;
uniform float ior;

uniform sampler2D envMap;
uniform int useEnvMap;
uniform float envRotation;
uniform float envLightingMix;

uniform int skyType;
uniform float cloudDensity;
uniform float skySpeed;
uniform float skyTime;
uniform float skyParallax;

// Gradient Palette (1D texture)
uniform sampler2D paletteTexture;

// Volumetric Fog
uniform int volumetricFogEnabled;
uniform float fogDensity;
uniform vec3 fogColor;
uniform float fogScattering; // Anisotropy
uniform int fogSteps;

// Advanced Effects
uniform float reflectionIntensity;
uniform float emissiveIntensity;
uniform float sssIntensity;
uniform float sssRadius;
uniform vec3 sssColor;

// Audio-reactive uniforms
uniform int audioEnabled;
uniform float audioLevel;
uniform float audioBeat;
uniform float audioOnset;
uniform float audioBands[8];
uniform float audioReactPower;
uniform float audioReactColor;
uniform float audioReactGlow;
uniform float audioReactFOV;
uniform float audioReactOnset;
uniform float audioReactFog;
uniform float audioReactShake;       // Camera shake intensity
uniform float audioReactWarp;        // Space warp intensity
uniform float audioReactPaletteJump; // Palette jump intensity
uniform int audioFrameIndex;         // Deterministic per-frame counter

// Adaptive Sampling
uniform int adaptiveSampling;        // 0 = off, 1 = on
uniform float varianceThreshold;     // Convergence threshold (default 0.005)
uniform int minAdaptiveSamples;      // Min samples before convergence check (default 8)

// Erosion
uniform int erosionEnabled;          // 0 = off, 1 = on
uniform float erosionStrength;       // 0-1, overall intensity
uniform float erosionTime;           // 0-20, progression (animatable)
uniform float erosionScale;          // 0.1-5, world-space feature scale
uniform int erosionType;             // 0=All, 1=Hydraulic, 2=Thermal, 3=Cracks

// Crystallization
uniform int crystalEnabled;          // 0 = off, 1 = on
uniform float crystalStrength;       // 0-1
uniform float crystalTime;           // 0-10, growth progression
uniform float crystalScale;          // 0.1-5, crystal size
uniform float crystalSharpness;      // 0.5-5, edge sharpness

// Moss/Lichen
uniform int mossEnabled;             // 0 = off, 1 = on
uniform float mossStrength;          // 0-1
uniform float mossTime;              // 0-10, growth progression
uniform float mossScale;             // 0.1-5, patch size
uniform vec3 mossColor;              // default (0.15, 0.35, 0.08) = dark green
uniform float mossNormalThreshold;   // 0-1, default 0.3

// ============================================================================
// Math & Random Helpers
// ============================================================================

// Henyey-Greenstein Phase Function for volumetric scattering
float phaseHG(float cosTheta, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (pow(1.0 + g2 - 2.0 * g * cosTheta, 1.5) * 4.0 * PI);
}

float hash3D(ivec3 p) {
    uvec3 v = uvec3(p);
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    return float(v.x) / 4294967295.0;
}

float hash1(uint n) {
    n = (n << 13U) ^ n;
    n = n * (n * n * 15731U + 789221U) + 1376312589U;
    return float(n & uint(0x7fffffffU)) / 2147483647.0;
}

float noise(vec3 p) {
    ivec3 i = ivec3(floor(p));
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(hash3D(i + ivec3(0,0,0)), hash3D(i + ivec3(1,0,0)), f.x),
            mix(hash3D(i + ivec3(0,1,0)), hash3D(i + ivec3(1,1,0)), f.x), f.y),
        mix(mix(hash3D(i + ivec3(0,0,1)), hash3D(i + ivec3(1,0,1)), f.x),
            mix(hash3D(i + ivec3(0,1,1)), hash3D(i + ivec3(1,1,1)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; ++i) {
        v += a * noise(p);
        p = p * 2.0 + vec3(10.0);
        a *= 0.5;
    }
    return v;
}

// Lightweight fbm (3 octaves) for erosion displacement
float fbmLow(vec3 p) {
    float n0 = noise(p);
    float n1 = noise(p * 2.0 + vec3(10.0));
    float n2 = noise(p * 4.0 + vec3(30.0));
    return n0 * 0.5 + n1 * 0.25 + n2 * 0.125;
}

float warpedFbm(vec3 p) {
    vec3 q = vec3(fbm(p), fbm(p + vec3(5.2, 1.3, 2.8)), fbm(p + vec3(1.3, 2.8, 5.2)));
    return fbm(p + 1.0 * q);
}

// Maximum possible erosion displacement magnitude for proximity gating
float erosionMaxDisplacement() {
    return (0.2 + 0.5 + 0.35) * erosionTime * erosionStrength * erosionScale;
}

float getErosionDisplacement(vec3 pos) {
    if (erosionEnabled == 0) return 0.0;

    vec3 p = pos / erosionScale;
    float t = erosionTime * erosionStrength;

    float weathering = 0.0, hydraulic = 0.0, thermal = 0.0;

    // Weathering: fine cracks and pits (3 octaves)
    if (erosionType == 0 || erosionType == 3) {
        weathering = (fbmLow(p * 8.0 + vec3(42.0)) - 0.4) * 0.2;
    }

    // Hydraulic: vertical flow channels, deeper at lower Y
    // Cheap warp: single fbm offset instead of 3-component vec3 warp
    if (erosionType == 0 || erosionType == 1) {
        vec3 flowP = vec3(p.x, p.y * 0.25, p.z);
        vec3 wp = flowP * 2.5;
        float warp = fbmLow(wp);
        float raw = pow(fbmLow(wp + warp * 1.0), 1.5) * 0.5;
        hydraulic = raw * (1.0 + 0.3 * (1.0 - p.y));
    }

    // Thermal: large-scale rounding/smoothing (3 octaves, low freq = 2 would suffice)
    if (erosionType == 0 || erosionType == 2) {
        thermal = fbmLow(p * 1.5 + vec3(13.0, 7.0, 19.0)) * 0.35;
    }

    return (weathering + hydraulic + thermal) * t * erosionScale;
}

// Voronoi / cellular noise — returns vec2(minDist, secondMinDist)
vec2 voronoi3D(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    float d1 = 1e10;
    float d2 = 1e10;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            for (int z = -1; z <= 1; z++) {
                vec3 neighbor = vec3(float(x), float(y), float(z));
                vec3 cellPos = neighbor + vec3(
                    hash3D(ivec3(i + neighbor)),
                    hash3D(ivec3(i + neighbor) + ivec3(127, 311, 74)),
                    hash3D(ivec3(i + neighbor) + ivec3(269, 183, 47))
                );
                float d = length(f - cellPos);
                if (d < d1) { d2 = d1; d1 = d; }
                else if (d < d2) { d2 = d; }
            }
        }
    }
    return vec2(d1, d2);
}

// Lighter erosion for shadow/AO (2 octaves, no hydraulic warp)
float getErosionDisplacementLight(vec3 pos) {
    if (erosionEnabled == 0) return 0.0;

    vec3 p = pos / erosionScale;
    float t = erosionTime * erosionStrength;

    float weathering = 0.0;
    float hydraulic = 0.0;
    float thermal = 0.0;

    if (erosionType == 0 || erosionType == 3) {
        float n1 = noise(p * 8.0 + vec3(42.0)) * 0.5;
        float n2 = noise(p * 16.0 + vec3(42.0)) * 0.25;
        weathering = (n1 + n2 - 0.3) * 0.2;
    }
    if (erosionType == 0 || erosionType == 1) {
        vec3 flowP = vec3(p.x, p.y * 0.25, p.z);
        float n1 = noise(flowP * 2.5) * 0.5;
        float n2 = noise(flowP * 5.0) * 0.25;
        float raw = pow(max(0.0, n1 + n2), 1.5) * 0.5;
        hydraulic = raw * (1.0 + 0.3 * (1.0 - p.y));
    }
    if (erosionType == 0 || erosionType == 2) {
        float n1 = noise(p * 1.5 + vec3(13.0, 7.0, 19.0)) * 0.5;
        float n2 = noise(p * 3.0 + vec3(13.0, 7.0, 19.0)) * 0.25;
        thermal = (n1 + n2) * 0.35;
    }

    return (weathering + hydraulic + thermal) * t * erosionScale;
}

// ============================================================================
// Crystallization — sharp Voronoi facets growing outward from surface
// ============================================================================

float crystalMaxDisplacement() {
    return crystalTime * crystalStrength * crystalScale * 0.3;
}

float getCrystalDisplacement(vec3 pos) {
    if (crystalEnabled == 0) return 0.0;
    vec3 p = pos / crystalScale;
    float t = crystalTime * crystalStrength;
    // Voronoi cell edges = crystal facets
    vec2 v = voronoi3D(p * 4.0 + vec3(77.0, 33.0, 55.0));
    float edgeDist = v.y - v.x;
    float facet = pow(max(0.0, 1.0 - edgeDist * crystalSharpness), 2.0);
    // Fine detail layer
    vec2 v2 = voronoi3D(p * 12.0 + vec3(11.0, 22.0, 33.0));
    float fine = pow(max(0.0, 1.0 - (v2.y - v2.x) * crystalSharpness * 1.5), 3.0);
    // Negative displacement = growth outward
    return -(facet * 0.25 + fine * 0.05) * t * crystalScale;
}

float getCrystalDisplacementLight(vec3 pos) {
    if (crystalEnabled == 0) return 0.0;
    vec3 p = pos / crystalScale;
    float t = crystalTime * crystalStrength;
    vec2 v = voronoi3D(p * 4.0 + vec3(77.0, 33.0, 55.0));
    float edgeDist = v.y - v.x;
    float facet = pow(max(0.0, 1.0 - edgeDist * crystalSharpness), 2.0);
    return -(facet * 0.25) * t * crystalScale;
}

// ============================================================================
// Moss/Lichen — organic growth on surfaces
// ============================================================================

float mossMaxDisplacement() {
    return mossTime * mossStrength * mossScale * 0.05;
}

float getMossDisplacement(vec3 pos) {
    if (mossEnabled == 0) return 0.0;
    vec3 p = pos / mossScale;
    float t = mossTime * mossStrength;
    float patches = fbmLow(p * 3.0 + vec3(91.0, 17.0, 53.0));
    float detail = noise(p * 12.0 + vec3(7.0)) * 0.3;
    float growth = max(0.0, patches + detail - 0.3);
    return -growth * 0.05 * t * mossScale;
}

float getMossDisplacementLight(vec3 pos) {
    if (mossEnabled == 0) return 0.0;
    vec3 p = pos / mossScale;
    float t = mossTime * mossStrength;
    float n1 = noise(p * 3.0 + vec3(91.0, 17.0, 53.0)) * 0.5;
    float n2 = noise(p * 6.0 + vec3(91.0, 17.0, 53.0)) * 0.25;
    float growth = max(0.0, n1 + n2 - 0.2);
    return -growth * 0.05 * t * mossScale;
}

// Factor for coloring (0 = no moss, 1 = full moss)
float getMossFactor(vec3 pos, vec3 normal, float ao) {
    if (mossEnabled == 0) return 0.0;
    vec3 p = pos / mossScale;
    float t = mossTime * mossStrength;
    // Horizontal surface preference
    float upFacing = smoothstep(mossNormalThreshold, mossNormalThreshold + 0.3, normal.y);
    // Crevice preference (low AO = occluded = crevice)
    float crevice = smoothstep(0.7, 0.3, ao);
    // Either horizontal OR crevice (weighted)
    float placement = max(upFacing * 0.7, crevice * 1.0);
    // Organic patchiness
    float patches = fbmLow(p * 3.0 + vec3(91.0, 17.0, 53.0));
    float growth = smoothstep(0.3, 0.7, patches) * placement * t;
    return clamp(growth, 0.0, 1.0);
}

uint pcg_hash(uint seed) {
    uint state = seed * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

float random(inout uint seed) {
    seed = pcg_hash(seed);
    return float(seed) / 4294967295.0;
}

vec2 random2(inout uint seed) { return vec2(random(seed), random(seed)); }
vec3 random3(inout uint seed) { return vec3(random(seed), random(seed), random(seed)); }

uint initRandom(vec2 fragCoord, int sampleIdx) {
    return uint(fragCoord.x) * 1973u + uint(fragCoord.y) * 9277u + uint(sampleIdx) * 26699u;
}

// ============================================================================
// Palette & Material
// ============================================================================

// Environment palette — clamp to avoid wrap discontinuity at edges
vec3 getSmoothPalette(float t) {
    return texture(paletteTexture, vec2(clamp(t, 0.0, 1.0), 0.5)).rgb;
}

// Fractal palette — fract for cyclic wrapping on orbit traps
vec3 getPresetPalette(float t) {
    return texture(paletteTexture, vec2(fract(t), 0.5)).rgb;
}

vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0/3.0, 2.0/3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

vec3 applyMaterial(vec3 factors) {
    float structural = factors.x;
    float flow = factors.y;
    float depth = factors.z;

    // Modes 6-8 bypass the palette entirely
    if (coloringMode == 6) {
        // HSV Direct: 3 factors independently control Hue/Saturation/Value
        float h = fract(flow * colorStrength + paletteOffset);
        float s = mix(0.4, 1.0, structural);
        float v = mix(0.3, 1.0, 1.0 - depth * 0.7);
        return hsv2rgb(vec3(h, s, v));
    }
    if (coloringMode == 7) {
        // Dual Palette: two independent palette lookups blended by depth
        float t1 = structural * colorStrength + paletteOffset;
        float t2 = flow * colorStrength * 1.5 + paletteOffset + 0.5;
        vec3 c1 = getPresetPalette(t1);
        vec3 c2 = getPresetPalette(t2);
        vec3 color = mix(c1, c2, depth);
        color *= 1.0 - depth * 0.3;
        return color;
    }
    if (coloringMode == 8) {
        // Neon: HSV with high saturation, sharp hue bands from all 3 factors
        float h = fract(floor((structural + flow) * colorStrength * 8.0) / 8.0 + paletteOffset);
        float s = 0.9;
        float v = mix(0.5, 1.0, 1.0 - depth * 0.5);
        vec3 color = hsv2rgb(vec3(h, s, v));
        // Add glow on structural edges
        color += hsv2rgb(vec3(h, 0.5, 1.0)) * smoothstep(0.3, 0.7, structural) * 0.3;
        return color;
    }

    // Modes 0-5: palette-based coloring
    float t;

    if (coloringMode == 1) {
        // Iteration Bands: sharp discrete color bands by depth
        t = floor(depth * 12.0) / 12.0 * colorStrength + paletteOffset;
    } else if (coloringMode == 2) {
        // Distance: proximity/structural based coloring
        t = structural * colorStrength + paletteOffset;
    } else if (coloringMode == 3) {
        // Angular: atan2-based spiral patterns from flow vs structural
        t = atan(flow - 0.5, structural - 0.5) / 6.2832 + 0.5;
        t = t * colorStrength + paletteOffset;
    } else if (coloringMode == 4) {
        // Blend: equal weight mix of all three factors
        t = (structural + flow + depth) * 0.333 * colorStrength + paletteOffset;
    } else if (coloringMode == 5) {
        // Contour: high-frequency sine stripes (topographic map effect)
        float combined = flow * 3.0 + structural * 2.0 + depth;
        t = sin(combined * colorStrength * 20.0) * 0.5 + 0.5 + paletteOffset;
    } else {
        // Standard (mode 0): original flow + depth coloring
        t = flow * colorStrength + paletteOffset + depth * 0.1;
    }

    vec3 color = getPresetPalette(t);

    // Structural highlights (shared across palette modes)
    vec3 highlight = mix(vec3(1.0), color * 1.5, 0.5);
    color = mix(color, highlight, clamp(structural * 0.9, 0.0, 1.0));

    // Depth darkening
    color *= 1.0 - depth * 0.4;

    return color;
}

// ============================================================================
// Star Generation System
// ============================================================================

vec3 renderStarLayer(vec3 dir, float scale, float threshold, float brightness, float parallaxFactor) {
    // Scaled parallax based on global skyParallax uniform
    vec3 sp = (dir + camPos * parallaxFactor * skyParallax) * scale;
    vec3 ip = floor(sp);
    vec3 fp = fract(sp);
    vec3 col = vec3(0.0);
    for(int z=-1; z<=1; z++)
    for(int y=-1; y<=1; y++)
    for(int x=-1; x<=1; x++) {
        ivec3 offset = ivec3(x, y, z);
        float h = hash3D(ivec3(ip) + offset);
        if (h > threshold) {
            vec3 pos = vec3(offset) + vec3(hash1(uint(h*1234.0)), hash1(uint(h*5678.0)), hash1(uint(h*9101.0)));
            float dist = length(fp - pos);
            float core = smoothstep(0.05, 0.0, dist);
            float glow = smoothstep(0.4, 0.0, dist) * 0.5;
            float temp = hash1(uint(h*9999.0));
            vec3 starTint = mix(vec3(0.6, 0.8, 1.0), vec3(1.0, 0.9, 0.6), temp);
            float mag = pow(h, 20.0); 
            col += starTint * (core + glow) * mag * brightness;
        }
    }
    return col;
}

// ============================================================================
// Environments
// ============================================================================

// Multi-layered Space with Spatial Parallax
vec3 renderSpace(vec3 dir) {
    // 1. Deep Nebula Layer
    vec3 p1 = (dir + camPos * 0.02 * skyParallax) * 1.0 * skySpeed;
    p1.z += skyTime * 0.01;
    float n1 = warpedFbm(p1);
    vec3 nebula = getSmoothPalette(n1 * 1.2 + paletteOffset) * smoothstep(0.2, 0.8, n1) * cloudDensity * 0.4;
    
    // 2. Dust Layer
    vec3 p2 = (dir + camPos * 0.05 * skyParallax) * 2.0 * skySpeed;
    p2.x += skyTime * 0.02;
    float n2 = fbm(p2);
    float dust = smoothstep(0.4, 0.7, n2);
    nebula *= (1.0 - dust * 0.8);
    
    // 3. Stars (Multi-Layer)
    vec3 stars = vec3(0.0);
    stars += renderStarLayer(dir, 40.0, 0.98, 2.0, 0.01);
    stars += renderStarLayer(dir, 100.0, 0.95, 0.8, 0.05);
    stars += renderStarLayer(dir, 300.0, 0.90, 0.3, 0.15) * (1.0 - dust);

    return (nebula + stars) * skyIntensity;
}

// Multi-layered Clouds with Spatial Parallax
vec3 renderClouds(vec3 dir) {
    float t = 0.5 * (dir.y + 1.0);
    vec3 sky = mix(vec3(0.1, 0.2, 0.4), vec3(0.4, 0.6, 0.9), t);
    float sun = pow(max(0.0, dot(dir, normalize(lightDir))), 256.0) * 8.0 * lightIntensity;
    
    // Layer 1: Heavy Low Clouds
    vec3 p1 = (dir + camPos * 0.1 * skyParallax) * 3.0 * skySpeed;
    p1.x += skyTime * 0.05;
    float c1 = smoothstep(1.0 - cloudDensity, 1.4 - cloudDensity, fbm(p1));
    
    // Layer 2: Fast High Cirrus
    vec3 p2 = (dir + camPos * 0.03 * skyParallax) * 8.0 * skySpeed;
    p2.z += skyTime * 0.15;
    float c2 = smoothstep(0.6, 1.0, fbm(p2)) * 0.4;
    
    float totalClouds = clamp(c1 + c2, 0.0, 1.0) * max(0.0, dir.y + 0.2);
    sky = mix(sky, vec3(1.0), totalClouds * 0.7);
    
    return sky + sun * lightColor;
}

vec3 renderOcean(vec3 dir) {
    vec3 sky = mix(vec3(0.0, 0.1, 0.3), vec3(0.5, 0.7, 1.0), smoothstep(-0.1, 0.5, dir.y));
    if (dir.y < 0.0) {
        vec3 p = (dir + camPos * 0.1 * skyParallax) * 15.0 / abs(dir.y);
        p.xz += skyTime * 0.5;
        float wave = fbm(p * 0.2) * 0.5 + 0.5;
        sky = mix(vec3(0.0, 0.05, 0.1), vec3(0.0, 0.2, 0.4), wave);
        sky += pow(wave, 16.0) * 0.4;
    }
    return sky + pow(max(0.0, dot(dir, normalize(lightDir))), 128.0) * lightColor * 2.0;
}

vec3 renderStudio(vec3 dir) {
    float g = dot(dir, vec3(0,1,0)) * 0.5 + 0.5;
    return mix(vec3(0.05), vec3(0.2), g) * cloudDensity;
}

vec3 proceduralSky(vec3 dir) {
    if (skyType == 1) return renderSpace(dir);
    if (skyType == 2) return renderOcean(dir);
    if (skyType == 3) return renderStudio(dir);
    return renderClouds(dir);
}

// ============================================================================
// Sampling & Lighting
// ============================================================================

struct Ray { vec3 origin; vec3 direction; };

vec2 dirToEquirectangular(vec3 dir) {
    float u = fract(atan(dir.z, dir.x) / TAU + 0.5 - envRotation / TAU);
    float v = asin(clamp(dir.y, -1.0, 1.0)) / PI + 0.5;
    return vec2(u, v);
}

vec3 sampleEnvironment(vec3 dir) {
    if (useEnvMap != 0) return texture(envMap, dirToEquirectangular(dir)).rgb * skyIntensity;
    // Rotate direction for procedural sky to match env rotation convention
    float cosR = cos(envRotation);
    float sinR = sin(envRotation);
    vec3 rotDir = vec3(cosR * dir.x + sinR * dir.z, dir.y, -sinR * dir.x + cosR * dir.z);
    return proceduralSky(rotDir) * skyIntensity;
}

vec3 sampleEnvironmentWithGlow(vec3 dir, float minDist) {
    vec3 bg = sampleEnvironment(dir);
    float glow = exp(-minDist * 10.0) * glowIntensity;
    bg += getSmoothPalette(minDist * 2.0) * glow;
    return bg;
}

vec3 sampleEnvironmentDiffuse(vec3 normal) {
    vec3 up = abs(normal.y) < 0.99 ? vec3(0, 1, 0) : vec3(1, 0, 0);
    vec3 right = normalize(cross(up, normal));
    up = cross(normal, right);
    vec3 irradiance = sampleEnvironment(normal);
    irradiance += sampleEnvironment(normalize(normal + right * 0.7));
    irradiance += sampleEnvironment(normalize(normal - right * 0.7));
    irradiance += sampleEnvironment(normalize(normal + up * 0.7));
    irradiance += sampleEnvironment(normalize(normal - up * 0.7));
    return irradiance * 0.2;
}

vec3 getAmbientLighting(vec3 normal) {
    return mix(ambientColor * ambientIntensity, sampleEnvironmentDiffuse(normal), envLightingMix);
}

// ============================================================================
// Optics & Physics
// ============================================================================

vec3 dofColorWeight = vec3(1.0);

vec2 randomDisk(inout uint seed) {
    float r = sqrt(random(seed));
    float theta = random(seed) * TAU;
    return r * vec2(cos(theta), sin(theta));
}

vec2 sampleAperture(inout uint seed) {
    vec2 disk = randomDisk(seed);
    if (bokehBlades < 3) return disk;

    float r = length(disk);
    if (r < 0.0001) return disk;
    float theta = atan(disk.y, disk.x) + bokehRotation;

    float n = float(bokehBlades);
    float halfSector = PI / n;
    float sectorTheta = mod(theta + halfSector, TAU / n) - halfSector;
    float polyRadius = cos(PI / n) / cos(sectorTheta);
    r *= polyRadius;

    return r * vec2(cos(theta), sin(theta));
}

vec3 randomCosineHemisphere(inout uint seed, vec3 normal) {
    float r1 = random(seed); float r2 = random(seed);
    float phi = TAU * r1; float sinTheta = sqrt(r2); float cosTheta = sqrt(1.0 - r2);
    vec3 w = normal;
    vec3 u = normalize(cross(abs(w.x) > 0.1 ? vec3(0, 1, 0) : vec3(1, 0, 0), w));
    vec3 v = cross(w, u);
    return normalize(u * cos(phi) * sinTheta + v * sin(phi) * sinTheta + w * cosTheta);
}

vec3 randomGGX(inout uint seed, vec3 normal, float roughness) {
    float r1 = random(seed); float r2 = random(seed);
    float a = roughness * roughness; float a2 = a * a;
    float phi = TAU * r1;
    float cosTheta = sqrt((1.0 - r2) / (1.0 + (a2 - 1.0) * r2));
    float sinTheta = sqrt(1.0 - cosTheta * cosTheta);
    vec3 H = vec3(sinTheta * cos(phi), sinTheta * sin(phi), cosTheta);
    vec3 w = normal;
    vec3 u = normalize(cross(abs(w.x) > 0.1 ? vec3(0, 1, 0) : vec3(1, 0, 0), w));
    vec3 v = cross(w, u);
    return normalize(u * H.x + v * H.y + w * H.z);
}

float fresnelSchlick(float cosTheta, float F0) { return F0 + (1.0 - F0) * pow(1.0 - cosTheta, 5.0); }
vec3 fresnelSchlickVec(float cosTheta, vec3 F0) { return F0 + (1.0 - F0) * pow(1.0 - cosTheta, 5.0); }

bool refractRay(vec3 incident, vec3 normal, float eta, out vec3 refracted) {
    float cosI = -dot(incident, normal);
    float sin2T = eta * eta * (1.0 - cosI * cosI);
    if (sin2T > 1.0) return false;
    float cosT = sqrt(1.0 - sin2T);
    refracted = eta * incident + (eta * cosI - cosT) * normal;
    return true;
}

float fresnelDielectric(float cosTheta, float ior) {
    float r0 = (1.0 - ior) / (1.0 + ior);
    float F0 = r0 * r0;
    return fresnelSchlick(cosTheta, F0);
}

// ============================================================================
// GGX Geometry Term (Smith)
// ============================================================================

float smithG1GGX(float NdotX, float a2) {
    return 2.0 * NdotX / (NdotX + sqrt(a2 + (1.0 - a2) * NdotX * NdotX));
}

float smithG2GGX(float NdotL, float NdotV, float a2) {
    return smithG1GGX(NdotL, a2) * smithG1GGX(NdotV, a2);
}

float powerHeuristic(float pdf_a, float pdf_b) {
    float a2 = pdf_a * pdf_a;
    float b2 = pdf_b * pdf_b;
    return a2 / max(a2 + b2, 1e-8);
}

// ============================================================================
// Environment Importance Sampling (NEE + MIS)
// ============================================================================

uniform sampler2D envMarginalCDF;   // 1 x height, R32F
uniform sampler2D envConditionalCDF; // width x height, R32F
uniform float envTotalLuminance;
uniform int envMapWidth;
uniform int envMapHeight;
uniform int neeEnabled;

// Binary search a 1D CDF stored in a texture row
int binarySearchCDF(sampler2D cdfTex, float xi, int row, int width, bool isMarginal) {
    int lo = 0, hi = width - 1;
    while (lo < hi) {
        int mid = (lo + hi) / 2;
        float cdfVal;
        if (isMarginal) {
            cdfVal = texelFetch(cdfTex, ivec2(0, mid), 0).r;
        } else {
            cdfVal = texelFetch(cdfTex, ivec2(mid, row), 0).r;
        }
        if (cdfVal < xi) {
            lo = mid + 1;
        } else {
            hi = mid;
        }
    }
    return lo;
}

// Convert equirectangular UV to world direction (exact inverse of dirToEquirectangular)
// dirToEquirectangular: u = atan(z,x)/TAU + 0.5, v = asin(y)/PI + 0.5
// Inverse: phi = (u-0.5)*TAU, lat = (v-0.5)*PI
//   dir = (cos(phi)*cos(lat), sin(lat), sin(phi)*cos(lat))
vec3 equirectangularToDir(vec2 uv) {
    float phi = (uv.x - 0.5) * TAU;
    float lat = (uv.y - 0.5) * PI;
    float cosLat = cos(lat);
    return vec3(cos(phi) * cosLat, sin(lat), sin(phi) * cosLat);
}

// Sample a direction from the HDRI luminance CDF
// Returns: sampled color, direction, pdf in solid angle measure
void sampleEnvironmentImportance(inout uint seed, out vec3 envColor, out vec3 envDir, out float envPdf) {
    float xi1 = random(seed);
    float xi2 = random(seed);

    // 1) Pick row from marginal CDF
    int row = binarySearchCDF(envMarginalCDF, xi1, 0, envMapHeight, true);

    // 2) Pick column from conditional CDF for that row
    int col = binarySearchCDF(envConditionalCDF, xi2, row, envMapWidth, false);

    // 3) Convert pixel to UV (in texture space, unrotated)
    float u = (float(col) + 0.5) / float(envMapWidth);
    float v = (float(row) + 0.5) / float(envMapHeight);

    // 4) Sample environment color directly from texture
    envColor = texture(envMap, vec2(u, v)).rgb * skyIntensity;

    // 5) Convert UV to texture-space direction, then rotate to world space
    envDir = equirectangularToDir(vec2(u, v));
    float cosR = cos(envRotation);
    float sinR = sin(envRotation);
    envDir = vec3(cosR * envDir.x - sinR * envDir.z, envDir.y, sinR * envDir.x + cosR * envDir.z);

    // 6) Compute PDF in solid angle
    float theta = acos(clamp(envDir.y, -1.0, 1.0));
    float sinTheta = max(sin(theta), 1e-8);
    float luminance = dot(envColor / skyIntensity, vec3(0.2126, 0.7152, 0.0722));
    envPdf = (luminance * float(envMapWidth) * float(envMapHeight)) / (envTotalLuminance * 2.0 * PI * PI * sinTheta);
    envPdf = max(envPdf, 1e-8);
}

// Compute PDF for a given direction under the environment importance distribution
float environmentPDF(vec3 dir) {
    vec2 uv = dirToEquirectangular(dir);
    vec3 color = texture(envMap, uv).rgb;
    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));

    float theta = acos(clamp(dir.y, -1.0, 1.0));
    float sinTheta = max(sin(theta), 1e-8);
    return (luminance * float(envMapWidth) * float(envMapHeight)) / (envTotalLuminance * 2.0 * PI * PI * sinTheta);
}

vec3 rotateByQuaternion(vec3 v, vec4 q) {
    vec3 qv = q.xyz; float qw = q.w;
    return v + 2.0 * cross(qv, cross(qv, v) + qw * v);
}

vec4 quaternionMultiply(vec4 a, vec4 b) {
    return vec4(a.w * b.xyz + b.w * a.xyz + cross(a.xyz, b.xyz), a.w * b.w - dot(a.xyz, b.xyz));
}

vec4 quaternionFromAxisAngle(vec3 axis, float angle) {
    float halfAngle = angle * 0.5;
    return vec4(axis * sin(halfAngle), cos(halfAngle));
}

Ray getCameraRay(vec2 screenUV) {
    Ray ray;
    ray.origin = camPos;
    
    if (projectionMode == 1) { // 360 EQUIRECTANGULAR
        // screenUV is in range [-1, 1]
        // Longitude: -PI to PI
        float lon = screenUV.x * PI;
        // Latitude: -PI/2 to PI/2
        float lat = screenUV.y * PI * 0.5;
        
        // Spherical to Cartesian (Right-handed, Z is forward)
        // We use Z as the primary axis to align with the camera rotation logic
        float x = sin(lon) * cos(lat);
        float y = sin(lat);
        float z = cos(lon) * cos(lat);
        
        ray.direction = rotateByQuaternion(vec3(x, y, z), camQuat);
    } else { // PERSPECTIVE (Standard)
        float effectiveFov = fov;
        // Audio-reactive FOV pulse on beats
        if (audioEnabled != 0) {
            effectiveFov += audioBeat * audioReactFOV * 8.0;
        }

        // Audio-reactive camera shake
        if (audioEnabled != 0 && audioReactShake > 0.0) {
            float shakeAmount = audioBeat * audioReactShake * 0.02;
            float shakeTime = float(audioFrameIndex) * 1.7;
            float sx = noise(vec3(shakeTime, 0.0, 0.0)) * 2.0 - 1.0;
            float sy = noise(vec3(0.0, shakeTime, 0.0)) * 2.0 - 1.0;
            screenUV += vec2(sx, sy) * shakeAmount;
        }

        // Audio-reactive space warp (bass-driven ray distortion)
        if (audioEnabled != 0 && audioReactWarp > 0.0) {
            float bassEnergy = (audioBands[0] + audioBands[1]) * 0.5;
            float warpAmount = bassEnergy * audioReactWarp * 0.03;
            float warpTime = float(audioFrameIndex) * 0.3;
            float nx = noise(vec3(screenUV * 3.0, warpTime)) * 2.0 - 1.0;
            float ny = noise(vec3(screenUV * 3.0 + vec2(17.0, 31.0), warpTime)) * 2.0 - 1.0;
            screenUV += vec2(nx, ny) * warpAmount;
        }

        float aspect = fullResolution.x / fullResolution.y;
        float halfHeight = tan(radians(effectiveFov) * 0.5);
        float halfWidth = halfHeight * aspect;
        vec3 localDir = normalize(vec3(screenUV.x * halfWidth, screenUV.y * halfHeight, 1.0));
        ray.direction = rotateByQuaternion(localDir, camQuat);
    }
    return ray;
}

Ray getCameraRayDOF(vec2 screenUV, inout uint seed) {
    dofColorWeight = vec3(1.0);
    if (dofEnabled == 0 || aperture < 0.0001) return getCameraRay(screenUV);

    Ray centerRay = getCameraRay(screenUV);
    vec3 forward = rotateByQuaternion(vec3(0,0,1), camQuat);
    vec3 right   = rotateByQuaternion(vec3(1,0,0), camQuat);
    vec3 up      = rotateByQuaternion(vec3(0,1,0), camQuat);

    // --- Longitudinal CA ---
    float effectiveFocalDist = focalDistance;
    if (dofChromaticStrength > 0.0001) {
        float ch = random(seed);
        if (ch < 0.333)      { effectiveFocalDist *= (1.0 - dofChromaticStrength); dofColorWeight = vec3(3,0,0); }
        else if (ch < 0.666) { dofColorWeight = vec3(0,3,0); }
        else                 { effectiveFocalDist *= (1.0 + dofChromaticStrength); dofColorWeight = vec3(0,0,3); }
    }

    // --- Focal point (with optional tilt-shift) ---
    vec3 focalPoint;
    if (tiltShiftEnabled != 0 && (abs(tiltAngleX) > 0.001 || abs(tiltAngleY) > 0.001)) {
        vec3 planeCenter = camPos + forward * effectiveFocalDist;
        vec3 pn = forward;
        float cx = cos(tiltAngleX), sx = sin(tiltAngleX);
        pn = pn * cx + cross(right, pn) * sx + right * dot(right, pn) * (1.0 - cx);
        float cy = cos(tiltAngleY), sy = sin(tiltAngleY);
        pn = pn * cy + cross(up, pn) * sy + up * dot(up, pn) * (1.0 - cy);
        pn = normalize(pn);
        float denom = dot(centerRay.direction, pn);
        if (abs(denom) > 0.0001) {
            float t = dot(planeCenter - centerRay.origin, pn) / denom;
            focalPoint = centerRay.origin + centerRay.direction * max(t, 0.01);
        } else {
            focalPoint = centerRay.origin + centerRay.direction * effectiveFocalDist;
        }
    } else {
        focalPoint = centerRay.origin + centerRay.direction * effectiveFocalDist;
    }

    // --- Aperture sampling (polygon or circle) ---
    vec2 diskSample = sampleAperture(seed) * aperture;

    // --- Anamorphic stretch ---
    diskSample.y *= anamorphicRatio;

    // --- Optical vignetting (cat's eye) ---
    if (opticalVignettingStrength > 0.001) {
        float screenDist = length(screenUV);
        float vScale = max(1.0 - opticalVignettingStrength * screenDist * screenDist, 0.1);
        vec2 radDir = screenDist > 0.001 ? normalize(screenUV) : vec2(1,0);
        vec2 tanDir = vec2(-radDir.y, radDir.x);
        diskSample = radDir * dot(diskSample, radDir) * vScale + tanDir * dot(diskSample, tanDir);
    }

    // --- Construct ray ---
    Ray ray;
    ray.origin = camPos + right * diskSample.x + up * diskSample.y;
    ray.direction = normalize(focalPoint - ray.origin);
    return ray;
}

float fresnel(vec3 viewDir, vec3 normal, float power) { return pow(1.0 - max(dot(viewDir, normal), 0.0), power); }

float computeAdaptiveEpsilon(float totalDist, float baseEps, float quality) {
    float scaled = baseEps / max(quality, 0.5);
    return max(MIN_EPSILON, scaled * (1.0 + totalDist * 0.1));
}

float computeStep(float dist, float quality, float stepFactor) {
    float factor = stepFactor / max(1.0, quality * 0.5);
    return dist * factor;
}
