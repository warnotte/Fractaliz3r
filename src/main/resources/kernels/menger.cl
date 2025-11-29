/**
 * Menger Sponge 3D fractal OpenCL kernel
 * Distance estimator, lighting, and rendering specific to Menger Sponge
 *
 * Note: This file is loaded AFTER common.cl which provides:
 * - Vector operations (normalize3, length3, dot3, cross3)
 * - Quaternion operations (rotateByQuaternion, getCameraRay)
 * - Constants (RENDER_*, STEP_FACTOR, etc.)
 * - Color utilities (palette, fresnel, iterationColor)
 * - DoF helpers (hash, randomInDisk)
 */

// ============================================================================
// Orbit trap structure for rich coloring
// ============================================================================

typedef struct {
    float minDist;    // Minimum distance to surface
    float edge;       // Edge detection for highlighting
    float depth;      // Depth into the fractal
    float corner;     // Corner proximity
    int iterations;   // Number of iterations completed
} MengerOrbitTraps;

// ============================================================================
// Helper: max component of vector
// ============================================================================

float maxcomp3(float3 v) {
    return fmax(v.x, fmax(v.y, v.z));
}

// ============================================================================
// Box distance
// ============================================================================

float sdBox(float3 p, float3 b) {
    float3 d = fabs(p) - b;
    return fmin(maxcomp3(d), 0.0f) + length3(fmax(d, (float3)(0.0f)));
}

// ============================================================================
// Cross distance (infinite cross shape)
// ============================================================================

float sdCross(float3 p) {
    float da = fmax(fabs(p.x), fabs(p.y));
    float db = fmax(fabs(p.y), fabs(p.z));
    float dc = fmax(fabs(p.z), fabs(p.x));
    return fmin(da, fmin(db, dc)) - 1.0f;
}

// ============================================================================
// Menger Sponge distance estimator with orbit traps
// ============================================================================

float mengerDE(float3 pos, int maxIterations, float scale, float3 offset,
               float distanceHint, MengerOrbitTraps* traps) {

    // Initialize traps
    traps->minDist = 1e10f;
    traps->edge = 1e10f;
    traps->depth = 0.0f;
    traps->corner = 1e10f;
    traps->iterations = 0;

    float3 p = pos;

    // Start with unit box
    float d = sdBox(p, (float3)(1.0f));

    float s = 1.0f;

    // Dynamic iterations based on distance
    int dynIter = maxIterations;
    if (distanceHint < 0.1f && distanceHint > 0.0f) {
        dynIter = maxIterations + 2;
        dynIter = min(dynIter, maxIterations + 4);
    }

    for (int i = 0; i < dynIter; i++) {
        // Scale to [-1, 1] box and apply offset
        float3 a = fmod(p * s, 2.0f * offset) - offset;
        s *= scale;

        // Reflect to first octant
        float3 r = fabs(offset - scale * fabs(a));

        // Track orbit traps
        traps->minDist = fmin(traps->minDist, length3(r) / s);
        traps->edge = fmin(traps->edge, fabs(r.x - r.y) + fabs(r.y - r.z));
        traps->corner = fmin(traps->corner, length3(r - offset) / s);

        // Cross distance
        float da = fmax(r.x, r.y);
        float db = fmax(r.y, r.z);
        float dc = fmax(r.z, r.x);
        float c = (fmin(da, fmin(db, dc)) - 1.0f) / s;

        d = fmax(d, c);

        traps->iterations = i + 1;
    }

    traps->depth = (float)traps->iterations / (float)maxIterations;

    return fmax(d, 1e-7f);
}

/**
 * Simplified DE for shadows/AO (fixed iterations, no orbit trap)
 */
float mengerDE_simple(float3 pos, int maxIterations, float scale, float3 offset) {
    float3 p = pos;

    float d = sdBox(p, (float3)(1.0f));
    float s = 1.0f;

    for (int i = 0; i < maxIterations; i++) {
        float3 a = fmod(p * s, 2.0f * offset) - offset;
        s *= scale;

        float3 r = fabs(offset - scale * fabs(a));

        float da = fmax(r.x, r.y);
        float db = fmax(r.y, r.z);
        float dc = fmax(r.z, r.x);
        float c = (fmin(da, fmin(db, dc)) - 1.0f) / s;

        d = fmax(d, c);
    }

    return fmax(d, 1e-7f);
}

// ============================================================================
// Normal calculation (tetrahedron method)
// ============================================================================

float3 calcNormalTetraMenger(float3 pos, int maxIterations, float scale, float3 offset) {
    const float3 k1 = (float3)( 1.0f, -1.0f, -1.0f);
    const float3 k2 = (float3)(-1.0f, -1.0f,  1.0f);
    const float3 k3 = (float3)(-1.0f,  1.0f, -1.0f);
    const float3 k4 = (float3)( 1.0f,  1.0f,  1.0f);

    float e = NORMAL_EPSILON;

    float d1 = mengerDE_simple(pos + k1 * e, maxIterations, scale, offset);
    float d2 = mengerDE_simple(pos + k2 * e, maxIterations, scale, offset);
    float d3 = mengerDE_simple(pos + k3 * e, maxIterations, scale, offset);
    float d4 = mengerDE_simple(pos + k4 * e, maxIterations, scale, offset);

    float3 n = k1 * d1 + k2 * d2 + k3 * d3 + k4 * d4;
    return normalize3(n);
}

// ============================================================================
// Soft shadows
// ============================================================================

float calcSoftShadowMenger(float3 ro, float3 rd, float mint, float maxt,
                           float softness, int shadowSteps,
                           int maxIterations, float scale, float3 offset) {
    float res = 1.0f;
    float t = mint;

    for (int i = 0; i < shadowSteps && t < maxt; i++) {
        float3 pos = ro + rd * t;
        float h = mengerDE_simple(pos, maxIterations, scale, offset);

        if (h < 0.0001f) {
            return 0.0f;
        }

        res = fmin(res, h * softness / t);
        t += clamp(h, 0.01f, 0.5f);
    }

    return clamp(res, 0.0f, 1.0f);
}

// ============================================================================
// Ambient occlusion
// ============================================================================

float calcAOMenger(float3 pos, float3 normal, int aoSteps,
                   int maxIterations, float scale, float3 offset) {
    float ao = 0.0f;
    float sca = 1.0f;

    for (int i = 0; i < aoSteps; i++) {
        float hr = 0.01f + 0.12f * (float)(i + 1) / (float)aoSteps;
        float3 aoPos = pos + normal * hr;
        float dd = mengerDE_simple(aoPos, maxIterations, scale, offset);
        ao += (hr - dd) * sca;
        sca *= 0.6f;
    }

    return clamp(1.0f - 5.0f * ao, 0.0f, 1.0f);
}

// ============================================================================
// Material color from orbit traps
// ============================================================================

float3 getMengerOrbitColor(MengerOrbitTraps traps, float3 baseHue) {
    // Menger creates nice geometric patterns
    float t1 = traps.minDist * 3.0f;
    float t2 = traps.edge * 0.5f;
    float t3 = traps.depth;
    float t4 = traps.corner * 2.0f;

    float combined = t1 * 0.3f + t2 * 0.25f + t3 * 0.25f + t4 * 0.2f;
    return palette(combined, baseHue);
}

// ============================================================================
// Main Menger Sponge render kernel
// ============================================================================

__kernel void renderMenger(
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
    int maxIterations,
    float scale,
    float4 offset,
    int maxRaySteps,
    float baseEpsilon,
    // Light direction
    float4 lightDir,
    // Light color and intensity
    float4 lightColor,
    // Ambient color
    float4 ambientColor,
    // Material base hue
    float4 materialHue,
    // Rendering quality
    float shadowSoftness,
    int shadowSteps,
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
    float3 offsetVec = (float3)(offset.x, offset.y, offset.z);

    // Get camera basis vectors for DoF lens offset
    float3 camRight = rotateByQuaternion((float3)(1.0f, 0.0f, 0.0f), camQuat);
    float3 camUp = rotateByQuaternion((float3)(0.0f, 1.0f, 0.0f), camQuat);

    // Calculate focal point
    float3 focalPoint = camOrigin + baseCameraRay * focalDistance;

    // Number of DoF samples
    int numSamples = (dofEnabled && aperture > 0.0001f) ? max(1, dofSamples) : 1;

    // Accumulator for multi-sample DoF
    float3 accumulatedColor = (float3)(0.0f, 0.0f, 0.0f);

    // DoF sampling loop
    for (int sampleIdx = 0; sampleIdx < numSamples; sampleIdx++) {
        float3 rayOrigin = camOrigin;
        float3 rayDir = baseCameraRay;

        // Apply thin-lens model if DoF is enabled
        if (dofEnabled && aperture > 0.0001f && numSamples > 1) {
            float2 seed = (float2)((float)pixelX + (float)sampleIdx * 0.1f,
                                   (float)pixelY + (float)sampleIdx * 0.37f);
            float2 lensOffset = randomInDisk(seed) * aperture;

            rayOrigin = camOrigin + camRight * lensOffset.x + camUp * lensOffset.y;
            rayDir = normalize3(focalPoint - rayOrigin);
        }

        // Ray marching
        float totalDist = 0.0f;
        float3 pos = rayOrigin;
        float dist = 0.0f;
        MengerOrbitTraps traps;
        bool hit = false;
        float minDist = 1e10f;
        float lastDist = 1e10f;

        for (int i = 0; i < maxRaySteps; i++) {
            pos = rayOrigin + rayDir * totalDist;
            dist = mengerDE(pos, maxIterations, scale, offsetVec, lastDist, &traps);

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

        // Shading
        float4 color;

        float3 lightCol = (float3)(lightColor.x, lightColor.y, lightColor.z);
        float lightInt = lightColor.w;
        float3 ambientCol = (float3)(ambientColor.x, ambientColor.y, ambientColor.z);
        float ambientInt = ambientColor.w;
        float3 baseHue = (float3)(materialHue.x, materialHue.y, materialHue.z);

        if (hit) {
            float3 normal = calcNormalTetraMenger(pos, maxIterations, scale, offsetVec);
            float3 light = normalize3((float3)(lightDir.x, lightDir.y, lightDir.z));
            float3 viewDir = -rayDir;

            float3 baseColor = getMengerOrbitColor(traps, baseHue);

            float NdotL = fmax(dot3(normal, light), 0.0f);
            float diffuse = NdotL;

            float3 halfVec = normalize3(light + viewDir);
            float NdotH = fmax(dot3(normal, halfVec), 0.0f);
            float specular = pow(NdotH, specularPower) * specularIntensity;

            float NdotV = fmax(dot3(normal, viewDir), 0.0f);
            float fres = fresnel(NdotV, 0.04f);

            float shadowBias = 0.001f + totalDist * 0.001f;
            float shadow = calcSoftShadowMenger(pos + normal * shadowBias, light,
                                                shadowBias, 15.0f, shadowSoftness, shadowSteps,
                                                max(3, maxIterations - 2), scale, offsetVec);

            float ao = calcAOMenger(pos, normal, aoSteps, max(3, maxIterations - 2), scale, offsetVec);

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
                float aoMixed = mix(1.0f, ao, aoIntensity);
                finalColor = baseColor * diffuse * shadow * aoMixed;
                finalColor = pow(fmax(finalColor, (float3)(0.0f)), (float3)(0.4545f));
            }
            else if (renderMode == RENDER_SPECULAR) {
                float spec = specular * shadow * (1.0f + fres);
                finalColor = (float3)(spec, spec, spec);
            }
            else if (renderMode == RENDER_ORBIT_TRAP) {
                finalColor = baseColor;
                finalColor = pow(fmax(finalColor, (float3)(0.0f)), (float3)(0.4545f));
            }
            else if (renderMode == RENDER_ITERATIONS) {
                finalColor = iterationColor(traps.iterations, maxIterations);
            }
            else {
                // RENDER_FINAL
                float aoMixed = mix(1.0f, ao, aoIntensity);
                float rim = pow(1.0f - NdotV, 4.0f) * 0.5f;

                float3 ambient = baseColor * ambientCol * ambientInt;
                float3 diffuseColor = baseColor * lightCol * diffuse * lightInt * shadow;
                float3 specularColor = lightCol * specular * shadow * (1.0f + fres);
                float3 rimColor = baseColor * rim * lightCol * 0.3f;

                finalColor = (ambient + diffuseColor + specularColor + rimColor) * aoMixed;

                float fogAmount = 1.0f - exp(-totalDist * 0.025f);
                float3 fogColor = ambientCol * 0.1f;
                finalColor = mix(finalColor, fogColor, fogAmount);

                finalColor = finalColor * (finalColor + 0.5f) / (finalColor * (finalColor + 0.5f) + 0.5f);
                finalColor = pow(fmax(finalColor, (float3)(0.0f)), (float3)(0.4545f));
            }

            color = (float4)(finalColor.x, finalColor.y, finalColor.z, 1.0f);
        } else {
            // Background
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
                float glow = exp(-minDist * 8.0f) * glowIntensity;
                float3 glowColor = palette(0.6f, baseHue) * glow * lightCol;

                float t = rayDir.y * 0.5f + 0.5f;
                float3 bgColor = mix(ambientCol * 0.05f, ambientCol * 0.15f, t);

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

        accumulatedColor += (float3)(color.x, color.y, color.z);
    }

    float3 finalPixelColor = accumulatedColor / (float)numSamples;

    output[outputIdx] = clamp(finalPixelColor.x, 0.0f, 1.0f);
    output[outputIdx + 1] = clamp(finalPixelColor.y, 0.0f, 1.0f);
    output[outputIdx + 2] = clamp(finalPixelColor.z, 0.0f, 1.0f);
    output[outputIdx + 3] = 1.0f;
}