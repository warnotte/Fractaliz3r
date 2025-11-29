/**
 * Kaleidoscopic IFS 3D fractal OpenCL kernel
 * Creates symmetrical fractals using reflection and scaling operations.
 * Inspired by Kali sets and Pseudo-Kleinian fractals.
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
    float minDist;    // Minimum distance during iteration
    float sumDist;    // Accumulated distance
    float foldCount;  // How many folds occurred
    float escape;     // Distance at escape
    int iterations;   // Number of iterations completed
} KaleidoOrbitTraps;

// ============================================================================
// Rotation helpers for angled folds
// ============================================================================

float3 rotateX(float3 p, float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return (float3)(p.x, c * p.y - s * p.z, s * p.y + c * p.z);
}

float3 rotateY(float3 p, float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return (float3)(c * p.x + s * p.z, p.y, -s * p.x + c * p.z);
}

float3 rotateZ(float3 p, float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return (float3)(c * p.x - s * p.y, s * p.x + c * p.y, p.z);
}

// ============================================================================
// Sphere fold (like Mandelbox)
// ============================================================================

void sphereFold(float3* z, float* dz, float minRadius, float fixedRadius) {
    float r2 = dot3(*z, *z);
    float minR2 = minRadius * minRadius;
    float fixedR2 = fixedRadius * fixedRadius;

    if (r2 < minR2) {
        float temp = fixedR2 / minR2;
        *z *= temp;
        *dz *= temp;
    } else if (r2 < fixedR2) {
        float temp = fixedR2 / r2;
        *z *= temp;
        *dz *= temp;
    }
}

// ============================================================================
// Plane fold (reflect if on wrong side of plane)
// ============================================================================

void planeFold(float3* z, float3 n, float d) {
    float dist = dot3(*z, n) - d;
    if (dist < 0.0f) {
        *z -= 2.0f * dist * n;
    }
}

// ============================================================================
// Kaleidoscopic IFS distance estimator with orbit traps
// ============================================================================

float kaleidoscopicDE(float3 pos, int maxIterations, float scale,
                      float foldAngleX, float foldAngleY,
                      float3 offset, float minRadius,
                      float distanceHint, KaleidoOrbitTraps* traps) {

    // Initialize traps
    traps->minDist = 1e10f;
    traps->sumDist = 0.0f;
    traps->foldCount = 0.0f;
    traps->escape = 0.0f;
    traps->iterations = 0;

    float3 z = pos;
    float dz = 1.0f;

    // Precompute fold plane normals based on angles
    float3 foldN1 = (float3)(cos(foldAngleX), sin(foldAngleX), 0.0f);
    float3 foldN2 = (float3)(0.0f, cos(foldAngleY), sin(foldAngleY));

    // Dynamic iterations
    int dynIter = maxIterations;
    if (distanceHint < 0.1f && distanceHint > 0.0f) {
        dynIter = min(maxIterations + 3, maxIterations * 2);
    }

    float foldCount = 0.0f;

    for (int i = 0; i < dynIter; i++) {
        // Absolute value folds (creates kaleidoscope effect)
        z = fabs(z);

        // Plane folds
        float d1 = dot3(z, foldN1);
        if (d1 < 0.0f) {
            z -= 2.0f * d1 * foldN1;
            foldCount += 1.0f;
        }

        float d2 = dot3(z, foldN2);
        if (d2 < 0.0f) {
            z -= 2.0f * d2 * foldN2;
            foldCount += 1.0f;
        }

        // Apply sphere fold if minRadius > 0
        if (minRadius > 0.001f) {
            sphereFold(&z, &dz, minRadius, 1.0f);
        }

        // Scale and translate
        z = z * scale - offset * (scale - 1.0f);
        dz = dz * fabs(scale) + 1.0f;

        // Track orbit traps
        float dist = length3(z);
        traps->minDist = fmin(traps->minDist, dist / dz);
        traps->sumDist += dist;

        traps->iterations = i + 1;

        // Early bailout
        if (dist > 1000.0f) {
            traps->escape = dist;
            break;
        }
    }

    traps->foldCount = foldCount / (float)traps->iterations;

    // Return distance estimate
    float de = (length3(z) - 2.0f) / dz;
    return fmax(de, 1e-7f);
}

/**
 * Simplified DE for shadows/AO (fixed iterations, no orbit trap)
 */
float kaleidoscopicDE_simple(float3 pos, int maxIterations, float scale,
                             float foldAngleX, float foldAngleY,
                             float3 offset, float minRadius) {
    float3 z = pos;
    float dz = 1.0f;

    float3 foldN1 = (float3)(cos(foldAngleX), sin(foldAngleX), 0.0f);
    float3 foldN2 = (float3)(0.0f, cos(foldAngleY), sin(foldAngleY));

    for (int i = 0; i < maxIterations; i++) {
        z = fabs(z);

        float d1 = dot3(z, foldN1);
        if (d1 < 0.0f) z -= 2.0f * d1 * foldN1;

        float d2 = dot3(z, foldN2);
        if (d2 < 0.0f) z -= 2.0f * d2 * foldN2;

        if (minRadius > 0.001f) {
            sphereFold(&z, &dz, minRadius, 1.0f);
        }

        z = z * scale - offset * (scale - 1.0f);
        dz = dz * fabs(scale) + 1.0f;

        if (length3(z) > 1000.0f) break;
    }

    float de = (length3(z) - 2.0f) / dz;
    return fmax(de, 1e-7f);
}

// ============================================================================
// Normal calculation (tetrahedron method)
// ============================================================================

float3 calcNormalTetraKaleido(float3 pos, int maxIterations, float scale,
                              float foldAngleX, float foldAngleY,
                              float3 offset, float minRadius) {
    const float3 k1 = (float3)( 1.0f, -1.0f, -1.0f);
    const float3 k2 = (float3)(-1.0f, -1.0f,  1.0f);
    const float3 k3 = (float3)(-1.0f,  1.0f, -1.0f);
    const float3 k4 = (float3)( 1.0f,  1.0f,  1.0f);

    float e = NORMAL_EPSILON;

    float d1 = kaleidoscopicDE_simple(pos + k1 * e, maxIterations, scale, foldAngleX, foldAngleY, offset, minRadius);
    float d2 = kaleidoscopicDE_simple(pos + k2 * e, maxIterations, scale, foldAngleX, foldAngleY, offset, minRadius);
    float d3 = kaleidoscopicDE_simple(pos + k3 * e, maxIterations, scale, foldAngleX, foldAngleY, offset, minRadius);
    float d4 = kaleidoscopicDE_simple(pos + k4 * e, maxIterations, scale, foldAngleX, foldAngleY, offset, minRadius);

    float3 n = k1 * d1 + k2 * d2 + k3 * d3 + k4 * d4;
    return normalize3(n);
}

// ============================================================================
// Soft shadows
// ============================================================================

float calcSoftShadowKaleido(float3 ro, float3 rd, float mint, float maxt,
                            float softness, int shadowSteps,
                            int maxIterations, float scale,
                            float foldAngleX, float foldAngleY,
                            float3 offset, float minRadius) {
    float res = 1.0f;
    float t = mint;

    for (int i = 0; i < shadowSteps && t < maxt; i++) {
        float3 p = ro + rd * t;
        float h = kaleidoscopicDE_simple(p, maxIterations, scale, foldAngleX, foldAngleY, offset, minRadius);

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

float calcAOKaleido(float3 pos, float3 normal, int aoSteps,
                    int maxIterations, float scale,
                    float foldAngleX, float foldAngleY,
                    float3 offset, float minRadius) {
    float ao = 0.0f;
    float sca = 1.0f;

    for (int i = 0; i < aoSteps; i++) {
        float hr = 0.01f + 0.12f * (float)(i + 1) / (float)aoSteps;
        float3 aoPos = pos + normal * hr;
        float dd = kaleidoscopicDE_simple(aoPos, maxIterations, scale, foldAngleX, foldAngleY, offset, minRadius);
        ao += (hr - dd) * sca;
        sca *= 0.6f;
    }

    return clamp(1.0f - 5.0f * ao, 0.0f, 1.0f);
}

// ============================================================================
// Material color from orbit traps
// ============================================================================

float3 getKaleidoOrbitColor(KaleidoOrbitTraps traps, float3 baseHue) {
    // Kaleidoscopic creates beautiful swirling patterns
    float t1 = traps.minDist * 4.0f;
    float t2 = traps.sumDist * 0.001f;
    float t3 = traps.foldCount;
    float t4 = (float)traps.iterations * 0.1f;

    float combined = t1 * 0.35f + t2 * 0.25f + t3 * 0.2f + t4 * 0.2f;
    return palette(combined, baseHue);
}

// ============================================================================
// Main Kaleidoscopic IFS render kernel
// ============================================================================

__kernel void renderKaleidoscopic(
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
    float foldAngleX,
    float foldAngleY,
    float4 offset,
    float minRadius,
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
        KaleidoOrbitTraps traps;
        bool hit = false;
        float minDist = 1e10f;
        float lastDist = 1e10f;

        for (int i = 0; i < maxRaySteps; i++) {
            pos = rayOrigin + rayDir * totalDist;
            dist = kaleidoscopicDE(pos, maxIterations, scale, foldAngleX, foldAngleY,
                                   offsetVec, minRadius, lastDist, &traps);

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
            float3 normal = calcNormalTetraKaleido(pos, maxIterations, scale, foldAngleX, foldAngleY, offsetVec, minRadius);
            float3 light = normalize3((float3)(lightDir.x, lightDir.y, lightDir.z));
            float3 viewDir = -rayDir;

            float3 baseColor = getKaleidoOrbitColor(traps, baseHue);

            float NdotL = fmax(dot3(normal, light), 0.0f);
            float diffuse = NdotL;

            float3 halfVec = normalize3(light + viewDir);
            float NdotH = fmax(dot3(normal, halfVec), 0.0f);
            float specular = pow(NdotH, specularPower) * specularIntensity;

            float NdotV = fmax(dot3(normal, viewDir), 0.0f);
            float fres = fresnel(NdotV, 0.04f);

            int reducedIter = max(6, maxIterations - 3);
            float shadowBias = 0.001f + totalDist * 0.001f;
            float shadow = calcSoftShadowKaleido(pos + normal * shadowBias, light,
                                                 shadowBias, 15.0f, shadowSoftness, shadowSteps,
                                                 reducedIter, scale, foldAngleX, foldAngleY, offsetVec, minRadius);

            float ao = calcAOKaleido(pos, normal, aoSteps, reducedIter, scale, foldAngleX, foldAngleY, offsetVec, minRadius);

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
