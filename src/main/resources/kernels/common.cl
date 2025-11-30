/**
 * Common OpenCL utilities shared across all fractal renderers.
 * Includes vector operations, quaternions, camera, DoF, and color utilities.
 */

// ============================================================================
// Render modes for pass visualization
// ============================================================================

#define RENDER_FINAL        0
#define RENDER_NORMALS      1
#define RENDER_DEPTH        2
#define RENDER_AO           3
#define RENDER_SHADOWS      4
#define RENDER_DIFFUSE      5
#define RENDER_SPECULAR     6
#define RENDER_ORBIT_TRAP   7
#define RENDER_ITERATIONS   8

// ============================================================================
// Constants for ray marching quality
// ============================================================================

#define STEP_FACTOR 0.5f
#define MIN_EPSILON 1e-7f
#define MAX_EPSILON 1e-3f
#define EPSILON_FACTOR 1e-4f
#define MAX_DISTANCE 100.0f
#define NORMAL_EPSILON 0.0012f

// ============================================================================
// Adaptive epsilon and step helpers (Hart sphere tracing best practices)
// ============================================================================

/**
 * Adaptive hit epsilon based on travel distance and quality multiplier.
 * Clamped to avoid overshoot or excessive banding.
 */
float computeAdaptiveEpsilon(float totalDist, float baseEpsilon, float qualityMultiplier) {
    float adaptive = fmax(MIN_EPSILON / qualityMultiplier, totalDist * EPSILON_FACTOR / qualityMultiplier);
    adaptive = fmin(adaptive, MAX_EPSILON / qualityMultiplier);
    adaptive = fmax(adaptive, baseEpsilon * 0.1f);
    return adaptive;
}

/**
 * Step size scaled by a global factor and clamped to avoid tunneling and micro-steps.
 */
float computeStep(float dist, float qualityMultiplier, float factor) {
    float step = dist * factor / fmax(1.0f, qualityMultiplier * 0.5f);
    step = fmax(step, MIN_EPSILON / qualityMultiplier);
    return step;
}

// ============================================================================
// Vector operations
// ============================================================================

float3 normalize3(float3 v) {
    float len = sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
    if (len < 1e-10f) return (float3)(0.0f, 0.0f, 1.0f);
    return v / len;
}

float length3(float3 v) {
    return sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
}

float dot3(float3 a, float3 b) {
    return a.x * b.x + a.y * b.y + a.z * b.z;
}

float3 cross3(float3 a, float3 b) {
    return (float3)(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x
    );
}

float fract1(float x) {
    return x - floor(x);
}

// ============================================================================
// Quaternion operations for camera
// ============================================================================

float3 rotateByQuaternion(float3 v, float4 q) {
    float3 qv = (float3)(q.y, q.z, q.w);
    float3 uv = cross3(qv, v);
    float3 uuv = cross3(qv, uv);
    return v + ((uv * q.x) + uuv) * 2.0f;
}

float3 getCameraRay(float2 uv, float fov, float4 quat) {
    float fovScale = tan(fov * 0.5f);
    float3 localRay = normalize3((float3)(uv.x * fovScale, uv.y * fovScale, 1.0f));
    return rotateByQuaternion(localRay, quat);
}

// ============================================================================
// Pseudo-random number generator for DoF sampling
// ============================================================================

float hash(float2 p) {
    return fract1(sin(dot3((float3)(p.x, p.y, 0.0f), (float3)(12.9898f, 78.233f, 45.164f))) * 43758.5453f);
}

float2 randomInDisk(float2 seed) {
    float angle = hash(seed) * 6.28318f;
    float radius = sqrt(hash(seed + (float2)(0.5f, 0.5f)));
    return (float2)(cos(angle) * radius, sin(angle) * radius);
}

// ============================================================================
// Color palette with configurable base
// ============================================================================

float3 palette(float t, float3 baseHue) {
    float3 a = (float3)(0.5f, 0.5f, 0.5f);
    float3 b = (float3)(0.5f, 0.5f, 0.5f);
    float3 c = (float3)(1.0f, 1.0f, 1.0f);
    return a + b * cos(6.28318f * (c * t + baseHue));
}

// ============================================================================
// Fresnel effect (Schlick approximation)
// ============================================================================

float fresnel(float cosTheta, float f0) {
    return f0 + (1.0f - f0) * pow(1.0f - cosTheta, 5.0f);
}

// ============================================================================
// Iteration count to color (for visualization)
// ============================================================================

float3 iterationColor(int iterations, int maxIterations) {
    float t = (float)iterations / (float)maxIterations;
    float3 c;
    if (t < 0.33f) {
        c = mix((float3)(0.0f, 0.0f, 0.0f), (float3)(1.0f, 0.0f, 0.0f), t * 3.0f);
    } else if (t < 0.66f) {
        c = mix((float3)(1.0f, 0.0f, 0.0f), (float3)(1.0f, 1.0f, 0.0f), (t - 0.33f) * 3.0f);
    } else {
        c = mix((float3)(1.0f, 1.0f, 0.0f), (float3)(1.0f, 1.0f, 1.0f), (t - 0.66f) * 3.0f);
    }
    return c;
}

// ============================================================================
// Common Rendering Pipeline Helpers
// ============================================================================

/**
 * Tone mapping and gamma correction.
 * Uses filmic tone mapping + sRGB gamma.
 */
float3 toneMapAndGamma(float3 col) {
    // Filmic tone mapping
    col = col * (col + 0.5f) / (col * (col + 0.5f) + 0.5f);
    // Gamma correction (sRGB)
    return pow(fmax(col, (float3)(0.0f)), (float3)(0.4545f));
}

/**
 * Apply fog based on distance.
 */
float3 applyFog(float3 col, float3 fogColor, float distance, float density) {
    float fogAmount = 1.0f - exp(-distance * density);
    return mix(col, fogColor, fogAmount);
}

/**
 * Calculate specular highlight (Blinn-Phong).
 */
float calcSpecular(float3 normal, float3 light, float3 viewDir,
                   float specularPower, float specularIntensity) {
    float3 halfVec = normalize3(light + viewDir);
    float NdotH = fmax(dot3(normal, halfVec), 0.0f);
    return pow(NdotH, specularPower) * specularIntensity;
}

/**
 * Calculate rim lighting.
 */
float calcRimLight(float3 normal, float3 viewDir) {
    float NdotV = fmax(dot3(normal, viewDir), 0.0f);
    return pow(1.0f - NdotV, 4.0f) * 0.5f;
}

/**
 * Complete shading calculation.
 * Returns the final lit color before tone mapping.
 */
float3 calculateShading(
    float3 baseColor,
    float3 normal,
    float3 light,
    float3 viewDir,
    float3 lightCol,
    float lightIntensity,
    float3 ambientCol,
    float ambientIntensity,
    float shadow,
    float ao,
    float aoIntensity,
    float specularPower,
    float specularIntensity
) {
    // Mix AO intensity
    float aoMixed = mix(1.0f, ao, aoIntensity);

    // Diffuse
    float NdotL = fmax(dot3(normal, light), 0.0f);

    // Specular with Fresnel
    float NdotV = fmax(dot3(normal, viewDir), 0.0f);
    float fres = fresnel(NdotV, 0.04f);
    float specular = calcSpecular(normal, light, viewDir, specularPower, specularIntensity);

    // Rim light
    float rim = calcRimLight(normal, viewDir);

    // Combine lighting components
    float3 ambient = baseColor * ambientCol * ambientIntensity;
    float3 diffuseColor = baseColor * lightCol * NdotL * lightIntensity * shadow;
    float3 specularColor = lightCol * specular * shadow * (1.0f + fres);
    float3 rimColor = baseColor * rim * lightCol * 0.3f;

    return (ambient + diffuseColor + specularColor + rimColor) * aoMixed;
}

/**
 * Render based on visualization mode.
 * Returns the color for the specified render mode.
 */
float3 renderByMode(
    int renderMode,
    float3 baseColor,
    float3 normal,
    float3 light,
    float3 viewDir,
    float3 lightCol,
    float lightIntensity,
    float3 ambientCol,
    float ambientIntensity,
    float shadow,
    float ao,
    float aoIntensity,
    float specularPower,
    float specularIntensity,
    float totalDist,
    int iterations,
    int maxIterations
) {
    float3 finalColor;

    if (renderMode == RENDER_NORMALS) {
        finalColor = normal * 0.5f + 0.5f;
    }
    else if (renderMode == RENDER_DEPTH) {
        float depthValue = exp(-totalDist * 0.5f);
        finalColor = (float3)(depthValue, depthValue, depthValue);
    }
    else if (renderMode == RENDER_AO) {
        finalColor = (float3)(ao, ao, ao);
    }
    else if (renderMode == RENDER_SHADOWS) {
        finalColor = (float3)(shadow, shadow, shadow);
    }
    else if (renderMode == RENDER_DIFFUSE) {
        float NdotL = fmax(dot3(normal, light), 0.0f);
        float aoMixed = mix(1.0f, ao, aoIntensity);
        finalColor = baseColor * NdotL * shadow * aoMixed;
        finalColor = toneMapAndGamma(finalColor);
    }
    else if (renderMode == RENDER_SPECULAR) {
        float specular = calcSpecular(normal, light, viewDir, specularPower, specularIntensity);
        float NdotV = fmax(dot3(normal, viewDir), 0.0f);
        float fres = fresnel(NdotV, 0.04f);
        float spec = specular * shadow * (1.0f + fres);
        finalColor = (float3)(spec, spec, spec);
    }
    else if (renderMode == RENDER_ORBIT_TRAP) {
        finalColor = toneMapAndGamma(baseColor);
    }
    else if (renderMode == RENDER_ITERATIONS) {
        finalColor = iterationColor(iterations, maxIterations);
    }
    else {
        // RENDER_FINAL - full shading pipeline
        finalColor = calculateShading(
            baseColor, normal, light, viewDir,
            lightCol, lightIntensity, ambientCol, ambientIntensity,
            shadow, ao, aoIntensity, specularPower, specularIntensity
        );

        // Apply fog
        float3 fogColor = ambientCol * 0.1f;
        finalColor = applyFog(finalColor, fogColor, totalDist, 0.025f);

        // Tone map and gamma
        finalColor = toneMapAndGamma(finalColor);
    }

    return finalColor;
}

/**
 * Render background with glow and stars.
 */
float3 renderBackground(
    int renderMode,
    float3 rayDir,
    float minDist,
    float glowIntensity,
    float3 baseHue,
    float3 lightCol,
    float3 ambientCol
) {
    float3 finalBg;

    if (renderMode == RENDER_DEPTH) {
        finalBg = (float3)(0.0f, 0.0f, 0.0f);
    }
    else if (renderMode == RENDER_NORMALS || renderMode == RENDER_AO ||
             renderMode == RENDER_SHADOWS || renderMode == RENDER_SPECULAR ||
             renderMode == RENDER_ITERATIONS) {
        finalBg = (float3)(0.1f, 0.1f, 0.1f);
    }
    else {
        // Glow effect
        float glow = exp(-minDist * 8.0f) * glowIntensity;
        float3 glowColor = palette(0.6f, baseHue) * glow * lightCol;

        // Gradient background
        float t = rayDir.y * 0.5f + 0.5f;
        float3 bgColor = mix(ambientCol * 0.05f, ambientCol * 0.15f, t);

        // Stars
        float stars = 0.0f;
        float3 starDir = rayDir * 100.0f;
        float starNoise = fract1(sin(dot3(floor(starDir), (float3)(12.9898f, 78.233f, 45.164f))) * 43758.5453f);
        if (starNoise > 0.997f) {
            stars = (starNoise - 0.997f) * 333.0f;
        }

        finalBg = bgColor + glowColor + (float3)(stars, stars, stars) * 0.5f;
    }

    return finalBg;
}

// ============================================================================
// DoF Setup Helpers
// ============================================================================

/**
 * Structure to hold DoF sampling state.
 */
typedef struct {
    float3 camOrigin;
    float3 baseCameraRay;
    float3 camRight;
    float3 camUp;
    float3 focalPoint;
    int numSamples;
} DofSetup;

/**
 * Initialize DoF setup for a pixel.
 */
DofSetup initDofSetup(
    float4 camPos, float4 camQuat, float fov,
    float2 uv, float focalDistance, float aperture,
    int dofEnabled, int dofSamples
) {
    DofSetup dof;

    dof.camOrigin = (float3)(camPos.x, camPos.y, camPos.z);
    dof.baseCameraRay = getCameraRay(uv, fov, camQuat);
    dof.camRight = rotateByQuaternion((float3)(1.0f, 0.0f, 0.0f), camQuat);
    dof.camUp = rotateByQuaternion((float3)(0.0f, 1.0f, 0.0f), camQuat);
    dof.focalPoint = dof.camOrigin + dof.baseCameraRay * focalDistance;
    dof.numSamples = (dofEnabled && aperture > 0.0001f) ? max(1, dofSamples) : 1;

    return dof;
}

/**
 * Get ray origin and direction for a DoF sample.
 */
void getDofSampleRay(
    DofSetup dof, int sampleIdx, int pixelX, int pixelY,
    float aperture, int dofEnabled,
    float3* rayOrigin, float3* rayDir
) {
    *rayOrigin = dof.camOrigin;
    *rayDir = dof.baseCameraRay;

    if (dofEnabled && aperture > 0.0001f && dof.numSamples > 1) {
        float2 seed = (float2)((float)pixelX + (float)sampleIdx * 0.1f,
                               (float)pixelY + (float)sampleIdx * 0.37f);
        float2 lensOffset = randomInDisk(seed) * aperture;

        *rayOrigin = dof.camOrigin + dof.camRight * lensOffset.x + dof.camUp * lensOffset.y;
        *rayDir = normalize3(dof.focalPoint - *rayOrigin);
    }
}

// ============================================================================
// Ray Hit Result Structure (for generic pipeline)
// ============================================================================

/**
 * Result of ray marching - used to pass hit information between functions.
 */
typedef struct {
    bool hit;           // Did we hit the surface?
    float3 pos;         // Hit position
    float totalDist;    // Total distance traveled
    float minDist;      // Minimum distance encountered (for glow)
    int iterations;     // Fractal iterations at hit point
} RayHit;

// ============================================================================
// Pixel Setup Structure (for generic pipeline)
// ============================================================================

/**
 * Pixel setup information - computed once per kernel invocation.
 */
typedef struct {
    int x;              // Pixel X coordinate
    int y;              // Pixel Y coordinate
    int outputIdx;      // Output buffer index
    float u;            // Normalized screen coordinate U
    float v;            // Normalized screen coordinate V
    bool valid;         // Is this pixel within bounds?
} PixelSetup;

/**
 * Setup pixel coordinates and check bounds.
 * Common code that was duplicated in every kernel.
 */
PixelSetup setupPixel(
    int localX, int localY,
    int tileOffsetX, int tileOffsetY, int tileSize,
    int imageWidth, int imageHeight
) {
    PixelSetup px;

    px.x = tileOffsetX + localX;
    px.y = tileOffsetY + localY;
    px.outputIdx = (localY * tileSize + localX) * 4;

    // Bounds check
    px.valid = (localX < tileSize && localY < tileSize &&
                px.x < imageWidth && px.y < imageHeight);

    if (px.valid) {
        float aspectRatio = (float)imageWidth / (float)imageHeight;
        px.u = (2.0f * ((float)px.x + 0.5f) / (float)imageWidth - 1.0f) * aspectRatio;
        px.v = 1.0f - 2.0f * ((float)px.y + 0.5f) / (float)imageHeight;
    }

    return px;
}

/**
 * Write final color to output buffer.
 * Common code that was duplicated in every kernel.
 */
void outputPixel(__global float* output, int outputIdx, float3 color) {
    output[outputIdx]     = clamp(color.x, 0.0f, 1.0f);
    output[outputIdx + 1] = clamp(color.y, 0.0f, 1.0f);
    output[outputIdx + 2] = clamp(color.z, 0.0f, 1.0f);
    output[outputIdx + 3] = 1.0f;
}
