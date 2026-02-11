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

float warpedFbm(vec3 p) {
    vec3 q = vec3(fbm(p), fbm(p + vec3(5.2, 1.3, 2.8)), fbm(p + vec3(1.3, 2.8, 5.2)));
    return fbm(p + 1.0 * q);
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

vec3 applyMaterial(vec3 factors) {
    float structural = factors.x;
    float flow = factors.y;
    float depth = factors.z;
    vec3 color = getPresetPalette(flow * colorStrength + paletteOffset + depth * 0.1);
    vec3 highlight = mix(vec3(1.0), color * 1.5, 0.5);
    color = mix(color, highlight, clamp(structural * 0.9, 0.0, 1.0));
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

vec2 randomDisk(inout uint seed) {
    float r = sqrt(random(seed));
    float theta = random(seed) * TAU;
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
        float aspect = fullResolution.x / fullResolution.y;
        float halfHeight = tan(radians(fov) * 0.5);
        float halfWidth = halfHeight * aspect;
        vec3 localDir = normalize(vec3(screenUV.x * halfWidth, screenUV.y * halfHeight, 1.0));
        ray.direction = rotateByQuaternion(localDir, camQuat);
    }
    return ray;
}

Ray getCameraRayDOF(vec2 screenUV, inout uint seed) {
    if (dofEnabled == 0 || aperture < 0.0001) return getCameraRay(screenUV);
    Ray centerRay = getCameraRay(screenUV);
    vec3 focalPoint = centerRay.origin + centerRay.direction * focalDistance;
    vec2 diskSample = randomDisk(seed) * aperture;
    vec3 right = rotateByQuaternion(vec3(1, 0, 0), camQuat);
    vec3 up = rotateByQuaternion(vec3(0, 1, 0), camQuat);
    Ray ray;
    ray.origin = camPos + right * diskSample.x + up * diskSample.y;
    ray.direction = normalize(focalPoint - ray.origin);
    return ray;
}

vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
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
