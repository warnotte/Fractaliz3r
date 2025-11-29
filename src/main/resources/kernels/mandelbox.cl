/**
 * Mandelbox 3D fractal OpenCL kernel
 * Distance estimator, lighting, and rendering specific to Mandelbox
 *
 * Note: This file is loaded AFTER common.cl which provides:
 * - Vector operations (normalize3, length3, dot3, cross3)
 * - Quaternion operations (rotateByQuaternion, getCameraRay)
 * - Constants (RENDER_*, STEP_FACTOR, etc.)
 * - Color utilities (palette, fresnel, iterationColor)
 * - DoF helpers (hash, randomInDisk)
 */

// ============================================================================
// Orbit trap structure for Mandelbox coloring
// ============================================================================

typedef struct {
    float minDist;      // Minimum distance during iteration
    float avgFold;      // Average fold amount
    float sphereHits;   // Number of sphere inversions
    int iterations;
} MandelboxTraps;

// ============================================================================
// Box fold operation
// ============================================================================

float3 boxFold(float3 z, float foldingLimit) {
    return clamp(z, -foldingLimit, foldingLimit) * 2.0f - z;
}

// ============================================================================
// Sphere fold operation
// ============================================================================

void sphereFold(float3* z, float* dz, float minRadius, float fixedRadius) {
    float minR2 = minRadius * minRadius;
    float fixR2 = fixedRadius * fixedRadius;
    float r2 = dot3(*z, *z);

    if (r2 < minR2) {
        // Inside inner sphere - invert through inner sphere
        float scale = fixR2 / minR2;
        *z *= scale;
        *dz *= scale;
    } else if (r2 < fixR2) {
        // Between spheres - invert through outer sphere
        float scale = fixR2 / r2;
        *z *= scale;
        *dz *= scale;
    }
}

// ============================================================================
// Mandelbox distance estimator with orbit traps
// ============================================================================

float mandelboxDE(float3 pos, float scale, float minRadius, float fixedRadius,
                  float foldingLimit, int maxIterations, MandelboxTraps* traps) {
    float3 z = pos;
    float3 offset = pos;
    float dz = 1.0f;

    traps->minDist = 1e10f;
    traps->avgFold = 0.0f;
    traps->sphereHits = 0.0f;
    traps->iterations = 0;

    int i;
    for (i = 0; i < maxIterations; i++) {
        // Box fold
        float3 oldZ = z;
        z = boxFold(z, foldingLimit);
        float foldAmount = length3(z - oldZ);
        traps->avgFold += foldAmount;

        // Sphere fold
        float r2Before = dot3(z, z);
        sphereFold(&z, &dz, minRadius, fixedRadius);
        float r2After = dot3(z, z);
        if (r2After != r2Before) {
            traps->sphereHits += 1.0f;
        }

        // Scale and translate
        z = z * scale + offset;
        dz = dz * fabs(scale) + 1.0f;

        // Track minimum distance for coloring
        float dist = length3(z);
        traps->minDist = fmin(traps->minDist, dist);

        // Bailout
        if (dist > 1000.0f) break;
    }

    traps->iterations = i;
    traps->avgFold /= (float)(i + 1);

    float r = length3(z);
    return r / fabs(dz);
}

/**
 * Simplified DE for shadows/AO (no orbit trap)
 */
float mandelboxDE_simple(float3 pos, float scale, float minRadius, float fixedRadius,
                         float foldingLimit, int maxIterations) {
    float3 z = pos;
    float3 offset = pos;
    float dz = 1.0f;

    for (int i = 0; i < maxIterations; i++) {
        z = boxFold(z, foldingLimit);
        sphereFold(&z, &dz, minRadius, fixedRadius);
        z = z * scale + offset;
        dz = dz * fabs(scale) + 1.0f;

        if (dot3(z, z) > 1000000.0f) break;
    }

    float r = length3(z);
    return r / fabs(dz);
}

// ============================================================================
// Tetrahedron normal calculation
// ============================================================================

float3 calcNormalTetra(float3 pos, float scale, float minRadius, float fixedRadius,
                       float foldingLimit, int maxIterations) {
    const float3 k1 = (float3)( 1.0f, -1.0f, -1.0f);
    const float3 k2 = (float3)(-1.0f, -1.0f,  1.0f);
    const float3 k3 = (float3)(-1.0f,  1.0f, -1.0f);
    const float3 k4 = (float3)( 1.0f,  1.0f,  1.0f);

    float e = NORMAL_EPSILON;

    float d1 = mandelboxDE_simple(pos + k1 * e, scale, minRadius, fixedRadius, foldingLimit, maxIterations);
    float d2 = mandelboxDE_simple(pos + k2 * e, scale, minRadius, fixedRadius, foldingLimit, maxIterations);
    float d3 = mandelboxDE_simple(pos + k3 * e, scale, minRadius, fixedRadius, foldingLimit, maxIterations);
    float d4 = mandelboxDE_simple(pos + k4 * e, scale, minRadius, fixedRadius, foldingLimit, maxIterations);

    float3 n = k1 * d1 + k2 * d2 + k3 * d3 + k4 * d4;
    return normalize3(n);
}

// ============================================================================
// Soft shadows
// ============================================================================

float calcSoftShadow(float3 ro, float3 rd, float mint, float maxt,
                     float softness, int shadowSteps, float scale, float minRadius,
                     float fixedRadius, float foldingLimit, int maxIterations) {
    float res = 1.0f;
    float t = mint;

    for (int i = 0; i < shadowSteps && t < maxt; i++) {
        float3 pos = ro + rd * t;
        float h = mandelboxDE_simple(pos, scale, minRadius, fixedRadius, foldingLimit, maxIterations);

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

float calcAO(float3 pos, float3 normal, int aoSteps, float scale, float minRadius,
             float fixedRadius, float foldingLimit, int maxIterations) {
    float ao = 0.0f;
    float sca = 1.0f;

    for (int i = 0; i < aoSteps; i++) {
        float hr = 0.01f + 0.12f * (float)(i + 1) / (float)aoSteps;
        float3 aoPos = pos + normal * hr;
        float dd = mandelboxDE_simple(aoPos, scale, minRadius, fixedRadius, foldingLimit, maxIterations);
        ao += (hr - dd) * sca;
        sca *= 0.6f;
    }

    return clamp(1.0f - 5.0f * ao, 0.0f, 1.0f);
}

// ============================================================================
// Material color from orbit traps
// ============================================================================

float3 getMandelboxColor(MandelboxTraps traps, float3 baseHue, float scale) {
    // Use various trap values for coloring
    float t1 = traps.minDist * 0.1f;
    float t2 = traps.avgFold * 0.5f;
    float t3 = traps.sphereHits * 0.1f;
    float t4 = (float)traps.iterations * 0.05f;

    float combined = t1 * 0.25f + t2 * 0.25f + t3 * 0.25f + t4 * 0.25f;

    // Add scale influence for variety
    combined += fabs(scale) * 0.1f;

    return palette(combined, baseHue);
}

// ============================================================================
// Main Mandelbox render kernel
// ============================================================================

__kernel void renderMandelbox(
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
    float scale,
    float minRadius,
    float fixedRadius,
    float foldingLimit,
    int maxIterations,
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

    // Camera basis vectors for DoF
    float3 camRight = rotateByQuaternion((float3)(1.0f, 0.0f, 0.0f), camQuat);
    float3 camUp = rotateByQuaternion((float3)(0.0f, 1.0f, 0.0f), camQuat);

    float3 focalPoint = camOrigin + baseCameraRay * focalDistance;
    int numSamples = (dofEnabled && aperture > 0.0001f) ? max(1, dofSamples) : 1;

    float3 accumulatedColor = (float3)(0.0f, 0.0f, 0.0f);

    // DoF sampling loop
    for (int sampleIdx = 0; sampleIdx < numSamples; sampleIdx++) {
        float3 rayOrigin = camOrigin;
        float3 rayDir = baseCameraRay;

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
        MandelboxTraps traps;
        bool hit = false;
        float minDist = 1e10f;

        for (int i = 0; i < maxRaySteps; i++) {
            pos = rayOrigin + rayDir * totalDist;
            dist = mandelboxDE(pos, scale, minRadius, fixedRadius, foldingLimit, maxIterations, &traps);

            minDist = fmin(minDist, dist);

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
            float3 normal = calcNormalTetra(pos, scale, minRadius, fixedRadius, foldingLimit, maxIterations);
            float3 light = normalize3((float3)(lightDir.x, lightDir.y, lightDir.z));
            float3 viewDir = -rayDir;

            float3 baseColor = getMandelboxColor(traps, baseHue, scale);

            float NdotL = fmax(dot3(normal, light), 0.0f);
            float diffuse = NdotL;

            float3 halfVec = normalize3(light + viewDir);
            float NdotH = fmax(dot3(normal, halfVec), 0.0f);
            float specular = pow(NdotH, specularPower) * specularIntensity;

            float NdotV = fmax(dot3(normal, viewDir), 0.0f);
            float fres = fresnel(NdotV, 0.04f);

            float shadowBias = 0.001f + totalDist * 0.001f;
            float shadow = calcSoftShadow(pos + normal * shadowBias, light,
                                          shadowBias, 15.0f, shadowSoftness, shadowSteps,
                                          scale, minRadius, fixedRadius, foldingLimit,
                                          max(5, maxIterations / 2));

            float ao = calcAO(pos, normal, aoSteps, scale, minRadius, fixedRadius,
                             foldingLimit, max(5, maxIterations / 2));

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