/**
 * Mandelbulb 3D fractal OpenCL kernel
 * Enhanced with tetrahedron normals, multiple orbit traps, improved shadows
 * and configurable lighting with render pass visualization
 */

// ============================================================================
// Render modes for pass visualization
// ============================================================================

#define RENDER_FINAL        0   // Complete render with all effects
#define RENDER_NORMALS      1   // Surface normals (RGB = XYZ)
#define RENDER_DEPTH        2   // Depth/distance visualization
#define RENDER_AO           3   // Ambient occlusion only
#define RENDER_SHADOWS      4   // Shadows only
#define RENDER_DIFFUSE      5   // Diffuse lighting only (no specular)
#define RENDER_SPECULAR     6   // Specular only
#define RENDER_ORBIT_TRAP   7   // Orbit trap coloring (no lighting)
#define RENDER_ITERATIONS   8   // Iteration count visualization

// ============================================================================
// Constants for ray marching quality
// ============================================================================

#define STEP_FACTOR 0.5f          // Conservative step multiplier (0.5-0.7 recommended)
#define MIN_EPSILON 1e-7f         // Minimum epsilon for close-up precision
#define MAX_EPSILON 1e-3f         // Maximum epsilon for distant objects
#define EPSILON_FACTOR 1e-4f      // Epsilon scales with distance
#define MAX_DISTANCE 100.0f       // Maximum ray travel distance
#define NORMAL_EPSILON 0.0012f    // Fixed epsilon for tetrahedron normal sampling

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
// Orbit trap structure for rich coloring
// ============================================================================

typedef struct {
    float plane;      // Distance to y=0 plane
    float sphere;     // Distance to unit sphere shell
    float axis;       // Distance to z-axis
    float cube;       // Distance to unit cube surface
    int iterations;   // Number of iterations before escape
} OrbitTraps;

// ============================================================================
// Mandelbulb distance estimator with multiple orbit traps
// ============================================================================

float mandelbulbDE(float3 pos, float power, int baseIterations, float bailout,
                   float distanceHint, OrbitTraps* traps) {
    float3 z = pos;
    float dr = 1.0f;
    float r = 0.0f;

    // Initialize orbit traps to large values
    traps->plane = 1e10f;
    traps->sphere = 1e10f;
    traps->axis = 1e10f;
    traps->cube = 1e10f;
    traps->iterations = 0;

    // Dynamic iterations: more iterations when closer
    int dynamicIter = baseIterations;
    if (distanceHint < 0.1f && distanceHint > 0.0f) {
        dynamicIter = baseIterations + (int)((0.1f - distanceHint) * 10.0f * (float)baseIterations);
        dynamicIter = min(dynamicIter, baseIterations * 2);
    }

    int i;
    for (i = 0; i < dynamicIter; i++) {
        r = length3(z);

        // Multiple orbit traps for rich coloring
        traps->plane = fmin(traps->plane, fabs(z.y));                           // Plane trap
        traps->sphere = fmin(traps->sphere, fabs(r - 1.0f));                    // Spherical shell
        traps->axis = fmin(traps->axis, sqrt(z.x * z.x + z.y * z.y));          // Z-axis trap
        traps->cube = fmin(traps->cube, fmax(fmax(fabs(z.x), fabs(z.y)), fabs(z.z))); // Cube trap

        if (r > bailout) break;

        // Spherical coordinates
        float theta = acos(clamp(z.z / r, -1.0f, 1.0f));
        float phi = atan2(z.y, z.x);

        // Derivative
        dr = pow(r, power - 1.0f) * power * dr + 1.0f;

        // Scale and rotate
        float zr = pow(r, power);
        theta *= power;
        phi *= power;

        // Back to cartesian
        float sinTheta = sin(theta);
        z = (float3)(
            sinTheta * cos(phi),
            sinTheta * sin(phi),
            cos(theta)
        ) * zr + pos;
    }

    traps->iterations = i;

    // Distance estimation with safety clamp
    float de = 0.5f * log(r) * r / dr;
    return fmax(de, 1e-7f);
}

/**
 * Simplified DE for shadows/AO (fixed iterations, no orbit trap)
 */
float mandelbulbDE_simple(float3 pos, float power, int maxIterations, float bailout) {
    float3 z = pos;
    float dr = 1.0f;
    float r = 0.0f;

    for (int i = 0; i < maxIterations; i++) {
        r = length3(z);
        if (r > bailout) break;

        float theta = acos(clamp(z.z / r, -1.0f, 1.0f));
        float phi = atan2(z.y, z.x);
        dr = pow(r, power - 1.0f) * power * dr + 1.0f;

        float zr = pow(r, power);
        theta *= power;
        phi *= power;

        float sinTheta = sin(theta);
        z = (float3)(
            sinTheta * cos(phi),
            sinTheta * sin(phi),
            cos(theta)
        ) * zr + pos;
    }

    float de = 0.5f * log(r) * r / dr;
    return fmax(de, 1e-7f);
}

// ============================================================================
// Tetrahedron normal calculation (4 samples instead of 6)
// More efficient and often more accurate
// ============================================================================

float3 calcNormalTetra(float3 pos, float power, int maxIterations, float bailout) {
    // Tetrahedron vertices for gradient sampling
    const float3 k1 = (float3)( 1.0f, -1.0f, -1.0f);
    const float3 k2 = (float3)(-1.0f, -1.0f,  1.0f);
    const float3 k3 = (float3)(-1.0f,  1.0f, -1.0f);
    const float3 k4 = (float3)( 1.0f,  1.0f,  1.0f);

    float e = NORMAL_EPSILON;

    float d1 = mandelbulbDE_simple(pos + k1 * e, power, maxIterations, bailout);
    float d2 = mandelbulbDE_simple(pos + k2 * e, power, maxIterations, bailout);
    float d3 = mandelbulbDE_simple(pos + k3 * e, power, maxIterations, bailout);
    float d4 = mandelbulbDE_simple(pos + k4 * e, power, maxIterations, bailout);

    float3 n = k1 * d1 + k2 * d2 + k3 * d3 + k4 * d4;
    return normalize3(n);
}

// ============================================================================
// Improved soft shadows with step clamping
// Based on Inigo Quilez's improved soft shadows
// ============================================================================

float calcSoftShadow(float3 ro, float3 rd, float mint, float maxt,
                     float softness, float power, int maxIterations, float bailout) {
    float res = 1.0f;
    float t = mint;

    for (int i = 0; i < 64 && t < maxt; i++) {
        float3 pos = ro + rd * t;
        float h = mandelbulbDE_simple(pos, power, maxIterations, bailout);

        // Hit something - fully in shadow
        if (h < 0.0001f) {
            return 0.0f;
        }

        // Soft shadow calculation
        // softness is inverted: lower value = softer shadows
        // We use h/t ratio - when h is small relative to t, we're close to shadow
        res = fmin(res, h * softness / t);

        // Step forward
        t += h * 0.4f;
    }

    // Clamp and apply smoothstep for nicer falloff
    res = clamp(res, 0.0f, 1.0f);
    return res * res * (3.0f - 2.0f * res);
}

// ============================================================================
// Ambient occlusion
// ============================================================================

float calcAO(float3 pos, float3 normal, int aoSteps, float power, int maxIterations, float bailout) {
    float ao = 0.0f;
    float scale = 1.0f;

    for (int i = 0; i < aoSteps; i++) {
        float hr = 0.005f + 0.12f * (float)(i + 1) / (float)aoSteps;
        float3 aoPos = pos + normal * hr;
        float dd = mandelbulbDE_simple(aoPos, power, maxIterations, bailout);
        ao += (hr - dd) * scale;
        scale *= 0.6f;
    }

    return clamp(1.0f - 5.0f * ao, 0.0f, 1.0f);
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
// Material color from orbit traps
// ============================================================================

float3 getOrbitColor(OrbitTraps traps, float3 baseHue) {
    // Combine multiple orbit traps for rich coloring
    float t1 = traps.plane * 2.0f;
    float t2 = traps.sphere * 3.0f;
    float t3 = traps.axis * 1.5f;
    float t4 = traps.cube * 2.5f;

    // Weighted combination
    float combined = t1 * 0.3f + t2 * 0.3f + t3 * 0.2f + t4 * 0.2f;

    return palette(combined, baseHue);
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
    // Hot colormap: black -> red -> yellow -> white
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
// Pseudo-random number generator for DoF sampling
// ============================================================================

float hash(float2 p) {
    return fract1(sin(dot3((float3)(p.x, p.y, 0.0f), (float3)(12.9898f, 78.233f, 45.164f))) * 43758.5453f);
}

float2 randomInDisk(float2 seed) {
    // Use hash to generate pseudo-random angle and radius
    float angle = hash(seed) * 6.28318f;
    float radius = sqrt(hash(seed + (float2)(0.5f, 0.5f)));
    return (float2)(cos(angle) * radius, sin(angle) * radius);
}

// ============================================================================
// Main kernel with enhanced lighting parameters and render modes
// ============================================================================

__kernel void renderMandelbulb(
    __global float* output,
    int imageWidth,
    int imageHeight,
    int tileOffsetX,
    int tileOffsetY,
    int tileSize,
    // Camera
    float4 camPos,
    float4 camQuat,
    float fov,
    // Fractal params
    float power,
    int maxIterations,
    int maxRaySteps,
    float bailout,
    float baseEpsilon,
    // Light direction (normalized)
    float4 lightDir,
    // Light color and intensity
    float4 lightColor,        // RGB + intensity in w
    // Ambient color
    float4 ambientColor,      // RGB + intensity in w
    // Material base hue for palette
    float4 materialHue,       // RGB offset for color palette
    // Rendering quality
    float shadowSoftness,
    int aoSteps,
    float aoIntensity,
    float glowIntensity,
    // Specular
    float specularIntensity,
    float specularPower,
    // Render mode
    int renderMode,
    // Depth of Field
    int dofEnabled,
    float focalDistance,
    float aperture,
    int dofSamples
) {
    int localX = get_global_id(0);
    int localY = get_global_id(1);

    if (localX >= tileSize || localY >= tileSize) return;

    int pixelX = tileOffsetX + localX;
    int pixelY = tileOffsetY + localY;

    if (pixelX >= imageWidth || pixelY >= imageHeight) return;

    int outputIdx = (localY * tileSize + localX) * 4;

    // Screen coordinates
    float aspectRatio = (float)imageWidth / (float)imageHeight;
    float u = (2.0f * ((float)pixelX + 0.5f) / (float)imageWidth - 1.0f) * aspectRatio;
    float v = 1.0f - 2.0f * ((float)pixelY + 0.5f) / (float)imageHeight;

    // Base camera setup
    float3 camOrigin = (float3)(camPos.x, camPos.y, camPos.z);
    float3 baseCameraRay = getCameraRay((float2)(u, v), fov, camQuat);

    // Get camera basis vectors for DoF lens offset
    float3 camRight = rotateByQuaternion((float3)(1.0f, 0.0f, 0.0f), camQuat);
    float3 camUp = rotateByQuaternion((float3)(0.0f, 1.0f, 0.0f), camQuat);

    // Calculate focal point (where all DoF rays converge)
    float3 focalPoint = camOrigin + baseCameraRay * focalDistance;

    // Number of DoF samples (1 if disabled)
    int numSamples = (dofEnabled && aperture > 0.0001f) ? max(1, dofSamples) : 1;

    // Accumulator for multi-sample DoF
    float3 accumulatedColor = (float3)(0.0f, 0.0f, 0.0f);

    // DoF sampling loop
    for (int sampleIdx = 0; sampleIdx < numSamples; sampleIdx++) {
        float3 rayOrigin = camOrigin;
        float3 rayDir = baseCameraRay;

        // Apply thin-lens model if DoF is enabled
        if (dofEnabled && aperture > 0.0001f && numSamples > 1) {
            // Generate random point on lens disk
            float2 seed = (float2)((float)pixelX + (float)sampleIdx * 0.1f,
                                   (float)pixelY + (float)sampleIdx * 0.37f);
            float2 lensOffset = randomInDisk(seed) * aperture;

            // Offset ray origin on the lens plane
            rayOrigin = camOrigin + camRight * lensOffset.x + camUp * lensOffset.y;

            // New ray direction points from lens position to focal point
            rayDir = normalize3(focalPoint - rayOrigin);
        }

        // ========================================================================
        // Ray marching with adaptive epsilon
        // ========================================================================

        float totalDist = 0.0f;
        float3 pos = rayOrigin;
        float dist = 0.0f;
        OrbitTraps traps;
        bool hit = false;
        float minDist = 1e10f;
        float lastDist = 1e10f;

        for (int i = 0; i < maxRaySteps; i++) {
            pos = rayOrigin + rayDir * totalDist;

            dist = mandelbulbDE(pos, power, maxIterations, bailout, lastDist, &traps);

            minDist = fmin(minDist, dist);
            lastDist = dist;

            float adaptiveEpsilon = fmax(MIN_EPSILON, totalDist * EPSILON_FACTOR);
            adaptiveEpsilon = fmin(adaptiveEpsilon, MAX_EPSILON);
            adaptiveEpsilon = fmax(adaptiveEpsilon, baseEpsilon * 0.1f);

            if (dist < adaptiveEpsilon) {
                hit = true;
                break;
            }

            float step = dist * STEP_FACTOR;
            step = fmax(step, MIN_EPSILON);
            totalDist += step;

            if (totalDist > MAX_DISTANCE) break;
        }

    // ========================================================================
    // Shading based on render mode
    // ========================================================================

    float4 color;

    // Extract lighting parameters
    float3 lightCol = (float3)(lightColor.x, lightColor.y, lightColor.z);
    float lightIntensity = lightColor.w;
    float3 ambientCol = (float3)(ambientColor.x, ambientColor.y, ambientColor.z);
    float ambientIntensity = ambientColor.w;
    float3 baseHue = (float3)(materialHue.x, materialHue.y, materialHue.z);

    if (hit) {
        // Calculate common values needed for most render modes
        float3 normal = calcNormalTetra(pos, power, maxIterations, bailout);
        float3 light = normalize3((float3)(lightDir.x, lightDir.y, lightDir.z));
        float3 viewDir = -rayDir;

        // Material color from multiple orbit traps
        float3 baseColor = getOrbitColor(traps, baseHue);

        // Diffuse (Lambert)
        float NdotL = fmax(dot3(normal, light), 0.0f);
        float diffuse = NdotL;

        // Specular (Blinn-Phong)
        float3 halfVec = normalize3(light + viewDir);
        float NdotH = fmax(dot3(normal, halfVec), 0.0f);
        float specular = pow(NdotH, specularPower) * specularIntensity;

        // Fresnel for rim and specular enhancement
        float NdotV = fmax(dot3(normal, viewDir), 0.0f);
        float fres = fresnel(NdotV, 0.04f);

        // Soft shadows
        float shadowBias = 0.001f + totalDist * 0.001f;
        float shadow = calcSoftShadow(pos + normal * shadowBias, light,
                                      shadowBias, 15.0f, shadowSoftness,
                                      power, max(8, maxIterations / 2), bailout);

        // Ambient occlusion
        float ao = calcAO(pos, normal, aoSteps, power, max(8, maxIterations / 2), bailout);

        // ====================================================================
        // Select output based on render mode
        // ====================================================================

        float3 finalColor;

        if (renderMode == RENDER_NORMALS) {
            // Normals: map [-1,1] to [0,1] for RGB
            finalColor = normal * 0.5f + 0.5f;
        }
        else if (renderMode == RENDER_DEPTH) {
            // Depth: closer = brighter
            // Use exponential falloff for better visualization at typical viewing distances
            float depthValue = exp(-totalDist * 0.5f);
            finalColor = (float3)(depthValue, depthValue, depthValue);
        }
        else if (renderMode == RENDER_AO) {
            // AO only
            finalColor = (float3)(ao, ao, ao);
        }
        else if (renderMode == RENDER_SHADOWS) {
            // Shadows only
            finalColor = (float3)(shadow, shadow, shadow);
        }
        else if (renderMode == RENDER_DIFFUSE) {
            // Diffuse only (with shadow and AO)
            float aoMixed = mix(1.0f, ao, aoIntensity);
            finalColor = baseColor * diffuse * shadow * aoMixed;
            // Gamma correction
            finalColor = pow(fmax(finalColor, (float3)(0.0f)), (float3)(0.4545f));
        }
        else if (renderMode == RENDER_SPECULAR) {
            // Specular only
            float spec = specular * shadow * (1.0f + fres);
            finalColor = (float3)(spec, spec, spec);
        }
        else if (renderMode == RENDER_ORBIT_TRAP) {
            // Orbit trap colors without lighting
            finalColor = baseColor;
            // Gamma correction
            finalColor = pow(fmax(finalColor, (float3)(0.0f)), (float3)(0.4545f));
        }
        else if (renderMode == RENDER_ITERATIONS) {
            // Iteration count visualization
            finalColor = iterationColor(traps.iterations, maxIterations);
        }
        else {
            // RENDER_FINAL: Complete render with all effects
            float aoMixed = mix(1.0f, ao, aoIntensity);

            // Rim lighting based on fresnel
            float rim = pow(1.0f - NdotV, 4.0f) * 0.5f;

            // Combine lighting
            float3 ambient = baseColor * ambientCol * ambientIntensity;
            float3 diffuseColor = baseColor * lightCol * diffuse * lightIntensity * shadow;
            float3 specularColor = lightCol * specular * shadow * (1.0f + fres);
            float3 rimColor = baseColor * rim * lightCol * 0.3f;

            finalColor = (ambient + diffuseColor + specularColor + rimColor) * aoMixed;

            // Distance fog
            float fogAmount = 1.0f - exp(-totalDist * 0.025f);
            float3 fogColor = ambientCol * 0.1f;
            finalColor = mix(finalColor, fogColor, fogAmount);

            // Tone mapping (ACES-like)
            finalColor = finalColor * (finalColor + 0.5f) / (finalColor * (finalColor + 0.5f) + 0.5f);

            // Gamma correction
            finalColor = pow(fmax(finalColor, (float3)(0.0f)), (float3)(0.4545f));
        }

        color = (float4)(finalColor.x, finalColor.y, finalColor.z, 1.0f);
    } else {
        // Background
        float3 finalBg;
        if (renderMode == RENDER_DEPTH) {
            // Far = black for depth mode
            finalBg = (float3)(0.0f, 0.0f, 0.0f);
        }
        else if (renderMode == RENDER_NORMALS || renderMode == RENDER_AO ||
                 renderMode == RENDER_SHADOWS || renderMode == RENDER_SPECULAR ||
                 renderMode == RENDER_ITERATIONS) {
            // Neutral background for debug modes
            finalBg = (float3)(0.1f, 0.1f, 0.1f);
        }
        else {
            // Normal background with glow
            float glow = exp(-minDist * 8.0f) * glowIntensity;
            float3 glowColor = palette(0.6f, baseHue) * glow * lightCol;

            // Gradient background
            float t = rayDir.y * 0.5f + 0.5f;
            float3 bgColor = mix(
                ambientCol * 0.05f,
                ambientCol * 0.15f,
                t
            );

            // Subtle stars
            float stars = 0.0f;
            float3 starDir = rayDir * 100.0f;
            float starNoise = fract1(sin(dot3(floor(starDir), (float3)(12.9898f, 78.233f, 45.164f))) * 43758.5453f);
            if (starNoise > 0.997f) {
                stars = (starNoise - 0.997f) * 333.0f;
            }

            finalBg = bgColor + glowColor + (float3)(stars, stars, stars) * 0.5f;
        }

        color = (float4)(finalBg.x, finalBg.y, finalBg.z, 1.0f);
    }

        // Accumulate color for DoF
        accumulatedColor += (float3)(color.x, color.y, color.z);

    } // End DoF sample loop

    // Average accumulated samples
    float3 finalPixelColor = accumulatedColor / (float)numSamples;

    // Write output
    output[outputIdx] = clamp(finalPixelColor.x, 0.0f, 1.0f);
    output[outputIdx + 1] = clamp(finalPixelColor.y, 0.0f, 1.0f);
    output[outputIdx + 2] = clamp(finalPixelColor.z, 0.0f, 1.0f);
    output[outputIdx + 3] = 1.0f;
}