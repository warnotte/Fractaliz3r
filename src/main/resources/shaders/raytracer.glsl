/**
 * Generic Raytracer for Fractal Rendering
 *
 * This file is FRACTAL-AGNOSTIC. It requires the fractal to define:
 * - float DE(vec3 pos, out OrbitTrap trap)  - Distance estimator with orbit traps
 * - float DE_simple(vec3 pos)               - Simple DE for shadows/AO/normals
 * - vec3 getFactors(OrbitTrap trap)         - Geometric factors for material system
 * - OrbitTrap struct                        - Fractal-specific orbit data
 *
 * Because GLSL has global uniforms, this works seamlessly!
 */

in vec2 fragCoord;
in vec2 uv;

out vec4 FragColor;

// ============================================================================
// Ray Hit Result
// ============================================================================

struct RayHit {
    bool hit;
    vec3 pos;
    float dist;
    float minDist;  // For glow effect
    OrbitTrap trap;
    int steps;
};

// ============================================================================
// Normal Calculation (Tetrahedron Method with adaptive epsilon)
// ============================================================================

vec3 calcNormal(vec3 pos) {
    const vec3 k1 = vec3( 1.0, -1.0, -1.0);
    const vec3 k2 = vec3(-1.0, -1.0,  1.0);
    const vec3 k3 = vec3(-1.0,  1.0, -1.0);
    const vec3 k4 = vec3( 1.0,  1.0,  1.0);

    // RESTORE PRECISION:
    // Use an epsilon relative to the rendering quality.
    // High quality = smaller epsilon = sharper details (no fake smoothing).
    float qualityEpsilon = baseEpsilon / max(1.0, qualityMultiplier);
    float distToCamera = length(pos - camPos);
    
    // Adaptive epsilon: prevents artifacts on distant objects while keeping close details sharp
    float e = max(qualityEpsilon, distToCamera * 0.00005);

    return normalize(
        k1 * DE_simple(pos + k1 * e) +
        k2 * DE_simple(pos + k2 * e) +
        k3 * DE_simple(pos + k3 * e) +
        k4 * DE_simple(pos + k4 * e)
    );
}

// ============================================================================
// Soft Shadows
// ============================================================================

float calcShadow(vec3 ro, vec3 rd, float mint, float maxt) {
    float res = 1.0;
    float t = mint;

    int steps = int(float(shadowSteps) * qualityMultiplier);
    float qualityEpsilon = baseEpsilon / qualityMultiplier;

    for (int i = 0; i < steps && t < maxt; i++) {
        float h = DE_simple(ro + rd * t);

        // Use adaptive epsilon for shadows too
        float epsilon = computeAdaptiveEpsilon(t, qualityEpsilon, qualityMultiplier);

        if (h < epsilon) return 0.0;

        res = min(res, shadowSoftness * h / t);
        
        // Don't clamp min step too aggressively, allows catching fine details
        // but ensure we progress at least epsilon
        t += max(h, epsilon * 2.0);
    }

    return clamp(res, 0.0, 1.0);
}

// ============================================================================
// Ambient Occlusion
// ============================================================================

float calcAO(vec3 pos, vec3 normal) {
    float ao = 0.0;
    float scale = 1.0;

    int steps = int(float(aoSteps) * qualityMultiplier);

    for (int i = 0; i < steps; i++) {
        float hr = 0.01 + 0.12 * float(i + 1) / float(steps);
        float dd = DE_simple(pos + normal * hr);
        ao += (hr - dd) * scale;
        scale *= 0.6;
    }

    return clamp(1.0 - aoIntensity * ao, 0.0, 1.0);
}

// ============================================================================
// Subsurface Scattering Approximation
// ============================================================================

float calcSSS(vec3 pos, vec3 normal, vec3 lightDir) {
    float thickness = 0.0;
    float step = sssRadius / 5.0;
    for (int i = 1; i <= 5; i++) {
        float expectedDist = float(i) * step;
        float actualDist = DE_simple(pos - normal * expectedDist);
        thickness += max(0.0, expectedDist - actualDist);
    }
    // Wrap lighting: light coming from behind illuminates thin areas
    float wrap = max(0.0, (dot(-normal, lightDir) + 0.5) / 1.5);
    return wrap * exp(-thickness * 8.0);
}

// ============================================================================
// Ray Marching
// ============================================================================

RayHit rayMarch(Ray ray) {
    RayHit result;
    result.hit = false;
    result.dist = 0.0;
    result.minDist = 1e10;
    result.steps = 0;

    int effectiveMaxSteps = int(float(maxRaySteps) * qualityMultiplier);
    float qualityEpsilon = baseEpsilon / qualityMultiplier;

    for (int i = 0; i < effectiveMaxSteps; i++) {
        result.pos = ray.origin + ray.direction * result.dist;

        float d = DE(result.pos, result.trap);
        result.minDist = min(result.minDist, d);
        result.steps = i + 1;

        float epsilon = computeAdaptiveEpsilon(result.dist, qualityEpsilon, qualityMultiplier);

        if (d < epsilon) {
            result.hit = true;
            break;
        }

        float step = computeStep(d, qualityMultiplier, STEP_FACTOR);
        result.dist += step;

        if (result.dist > MAX_DISTANCE) break;
    }

    return result;
}

// ============================================================================
// Volumetric Fog Helper
// ============================================================================

vec3 computeVolumetricFog(Ray ray, float hitDist, vec3 surfaceColor, out float extinction) {
    extinction = 1.0;
    if (volumetricFogEnabled == 0 || fogDensity <= 0.0) return surfaceColor;

    float volAccum = 0.0;
    float stepSize = hitDist / float(fogSteps);
    vec3 light = normalize(lightDir);
    float phase = phaseHG(dot(ray.direction, light), fogScattering);
    
    uint seed = initRandom(gl_FragCoord.xy, sampleIndex);
    float offset = random(seed) * stepSize;
    
    for (int i = 0; i < fogSteps; i++) {
        float d = (float(i) + 0.5) * stepSize + offset;
        if (d > hitDist) break;
        
        vec3 p = ray.origin + ray.direction * d;
        float shadowBias = 0.01 + d * 0.005;
        float sh = calcShadow(p, light, shadowBias, 10.0);
        volAccum += sh * exp(-d * fogDensity) * stepSize;
    }
    
    extinction = exp(-hitDist * fogDensity);
    vec3 volumetricLight = lightColor * lightIntensity * fogColor * phase * volAccum * fogDensity;
    
    return surfaceColor * extinction + volumetricLight;
}

// ============================================================================
// Shading
// ============================================================================

// Forward declaration (defined in Path Tracing section below)
bool rayMarchSimple(Ray ray, out vec3 hitPos, out float hitDist);

// Simplified shading for reflection bounce (no recursion, no volumetrics)
vec3 shadeSimple(vec3 hitPos, Ray ray) {
    vec3 normal = calcNormal(hitPos);
    vec3 viewDir = -ray.direction;
    vec3 light = normalize(lightDir);

    OrbitTrap trap;
    DE(hitPos, trap);
    vec3 factors = getFactors(trap);
    vec3 baseColor = applyMaterial(factors);

    float NdotL = max(dot(normal, light), 0.0);
    float shadowBias = 0.005 + length(hitPos - camPos) * 0.01;
    float shadow = calcShadow(hitPos + normal * shadowBias, light, shadowBias, 15.0);
    float ao = calcAO(hitPos, normal);

    vec3 ambient = getAmbientLighting(normal) * baseColor * ao;
    vec3 diffuse = lightColor * lightIntensity * baseColor * NdotL * shadow;

    vec3 halfDir = normalize(light + viewDir);
    float spec = pow(max(dot(normal, halfDir), 0.0), specularPower);
    vec3 specular = lightColor * spec * specularIntensity * shadow;

    vec3 color = ambient + diffuse + specular;

    // Emission on reflected surface
    if (emissiveIntensity > 0.0) {
        float structural = factors.x;
        float depth = factors.z;
        float emFactor = mix(structural, 1.0 - depth, 0.5);
        emFactor = pow(clamp(emFactor, 0.0, 1.0), 2.0);
        color += baseColor * emissiveIntensity * emFactor;
    }

    return color;
}

vec3 shadeBackground(Ray ray, float minDist) {
    vec3 bg = sampleEnvironmentWithGlow(ray.direction, minDist);
    float extinction;
    return computeVolumetricFog(ray, 20.0, bg, extinction);
}

vec3 shade(RayHit hit, Ray ray) {
    vec3 normal = calcNormal(hit.pos);
    vec3 viewDir = -ray.direction;
    vec3 light = normalize(lightDir);

    // Base color from unified material system
    vec3 factors = getFactors(hit.trap);
    vec3 baseColor = applyMaterial(factors);

    // Diffuse (Lambert)
    float NdotL = max(dot(normal, light), 0.0);

    // Specular (Blinn-Phong)
    vec3 halfDir = normalize(light + viewDir);
    float spec = pow(max(dot(normal, halfDir), 0.0), specularPower);

    // Shadow
    // Increased bias to prevent banding/acne on detailed fractals
    float shadowBias = 0.005 + hit.dist * 0.01; 
    float shadow = calcShadow(hit.pos + normal * shadowBias, light, shadowBias, 15.0);

    // Ambient occlusion
    float ao = calcAO(hit.pos, normal);

    // Combine lighting - use environment-aware ambient
    vec3 ambient = getAmbientLighting(normal) * baseColor * ao;
    vec3 diffuse = lightColor * lightIntensity * baseColor * NdotL * shadow;
    vec3 specular = lightColor * spec * specularIntensity * shadow;

    // Material system adjustments
    if (materialType == MATERIAL_METALLIC) {
        // Metallic: Darken diffuse, boost reflections
        diffuse *= (1.0 - metalness);
        ambient *= (1.0 - metalness);
        
        if (useEnvMap != 0) {
            vec3 reflectDir = reflect(ray.direction, normal);
            vec3 envReflect = sampleEnvironment(reflectDir);
            float F0 = mix(0.04, 1.0, metalness);
            float fr = fresnelSchlick(max(dot(normal, viewDir), 0.0), F0);
            specular += envReflect * specularIntensity * fr;
        }
    } else if (materialType == MATERIAL_GLASS) {
        // Glass: High fresnel, reduced diffuse
        float fr = fresnelDielectric(max(dot(normal, viewDir), 0.0), ior);
        diffuse *= (1.0 - fr);
        ambient *= (1.0 - fr);
        
        if (useEnvMap != 0) {
            vec3 reflectDir = reflect(ray.direction, normal);
            specular += sampleEnvironment(reflectDir) * specularIntensity * fr;
        }
        // Fake transparency/refraction rim
        specular += lightColor * pow(1.0 - NdotL, 4.0) * 0.5 * specularIntensity;
    } else {
        // Standard Lambertian environment reflection (subtle)
        if (useEnvMap != 0 && envLightingMix > 0.0) {
            vec3 reflectDir = reflect(ray.direction, normal);
            vec3 envReflect = sampleEnvironment(reflectDir);
            float fresnelFactor = fresnel(viewDir, normal, 5.0);
            specular = mix(specular, envReflect * specularIntensity * fresnelFactor, envLightingMix * 0.5);
        }
    }

    // Fresnel rim lighting
    float rim = fresnel(viewDir, normal, 3.0);
    vec3 rimLight = lightColor * rim * 0.15;

    vec3 color = ambient + diffuse + specular + rimLight;

    // ====== EMISSIVE / SELF-ILLUMINATION ======
    if (emissiveIntensity > 0.0) {
        float structural = factors.x;
        float depth = factors.z;
        float emFactor = mix(structural, 1.0 - depth, 0.5);
        emFactor = pow(clamp(emFactor, 0.0, 1.0), 2.0);
        color += baseColor * emissiveIntensity * emFactor;
    }

    // ====== SUBSURFACE SCATTERING ======
    if (sssIntensity > 0.0) {
        float sss = calcSSS(hit.pos, normal, light);
        color += baseColor * sssColor * sss * sssIntensity * lightColor * lightIntensity;
    }

    // ====== RAY-MARCHED REFLECTIONS ======
    if (reflectionIntensity > 0.0 && (materialType == MATERIAL_METALLIC || materialType == MATERIAL_GLASS)) {
        vec3 reflectDir = reflect(ray.direction, normal);
        float fresnelFactor;
        if (materialType == MATERIAL_METALLIC) {
            float F0 = mix(0.04, 1.0, metalness);
            fresnelFactor = fresnelSchlick(max(dot(normal, viewDir), 0.0), F0);
        } else {
            fresnelFactor = fresnelDielectric(max(dot(normal, viewDir), 0.0), ior);
        }

        Ray reflectRay;
        reflectRay.origin = hit.pos + normal * 0.005;
        reflectRay.direction = reflectDir;
        vec3 reflHitPos;
        float reflHitDist;
        vec3 reflColor;
        if (rayMarchSimple(reflectRay, reflHitPos, reflHitDist)) {
            reflColor = shadeSimple(reflHitPos, reflectRay);
        } else {
            reflColor = sampleEnvironment(reflectDir);
        }
        color = mix(color, reflColor, reflectionIntensity * fresnelFactor);
    }

    // ====== VOLUMETRIC FOG (God Rays) ======
    float extinction;
    color = computeVolumetricFog(ray, hit.dist, color, extinction);

    // Fallback distance fog (if volumetric is disabled)
    if (volumetricFogEnabled == 0) {
        float fogFactor = 1.0 - exp(-hit.dist * 0.05);
        vec3 fogColorBase = (useEnvMap != 0) ? sampleEnvironment(ray.direction) * 0.3 : ambientColor * 0.5;
        color = mix(color, fogColorBase, fogFactor * 0.3);
    }

    return color;
}

// ============================================================================
// Background / Environment
// ============================================================================
// Note: Environment sampling functions are now in common.glsl:
// - sampleEnvironment(dir) - for path tracing (HDRI or procedural)
// - sampleEnvironmentWithGlow(dir, minDist) - for raytracing with glow effect

// ============================================================================
// Path Tracing
// ============================================================================

// Simple ray march for path tracing (no orbit traps needed)
bool rayMarchSimple(Ray ray, out vec3 hitPos, out float hitDist) {
    float t = 0.0;
    int effectiveMaxSteps = int(float(maxRaySteps) * qualityMultiplier * 1.0); // Full steps for bounces
    float qualityEpsilon = baseEpsilon / qualityMultiplier;

    for (int i = 0; i < effectiveMaxSteps; i++) {
        vec3 pos = ray.origin + ray.direction * t;
        float d = DE_simple(pos);

        float epsilon = computeAdaptiveEpsilon(t, qualityEpsilon, qualityMultiplier);

        if (d < epsilon) {
            hitPos = pos;
            hitDist = t;
            return true;
        }

        t += d * STEP_FACTOR;

        if (t > MAX_DISTANCE) break;
    }

    hitDist = t;
    return false;
}

// Path trace with multiple bounces - supports Lambertian, Metallic, and Glass materials
vec3 pathTrace(Ray ray, inout uint seed) {
    vec3 throughput = vec3(1.0);  // Path throughput (accumulated BRDF)
    vec3 radiance = vec3(0.0);    // Accumulated light
    const float FIREFLY_CLAMP = 8.0; // Limit maximum intensity per bounce

    Ray currentRay = ray;

    for (int bounce = 0; bounce <= maxBounces; bounce++) {
        vec3 hitPos;
        float hitDist;

        if (!rayMarchSimple(currentRay, hitPos, hitDist)) {
            // Ray escaped - add environment light
            float envScale = (bounce == 0) ? 1.0 : indirectMultiplier;
            vec3 envContribution = throughput * sampleEnvironment(currentRay.direction) * envScale;
            radiance += clamp(envContribution, 0.0, FIREFLY_CLAMP);
            break;
        }

        // Calculate normal at hit point
        vec3 normal = calcNormal(hitPos);
        vec3 faceNormal = (dot(currentRay.direction, normal) > 0.0) ? -normal : normal;

        // Get surface color (albedo)
        OrbitTrap trap;
        DE(hitPos, trap);
        vec3 albedo = applyMaterial(getFactors(trap));

        vec3 viewDir = -currentRay.direction;
        float cosTheta = max(dot(viewDir, faceNormal), 0.0);

        // Emissive contribution at hit point (additive, unaffected by shadows)
        if (emissiveIntensity > 0.0) {
            vec3 factors = getFactors(trap);
            float structural = factors.x;
            float depth = factors.z;
            float emFactor = mix(structural, 1.0 - depth, 0.5);
            emFactor = pow(clamp(emFactor, 0.0, 1.0), 2.0);
            radiance += clamp(throughput * albedo * emissiveIntensity * emFactor, 0.0, FIREFLY_CLAMP);
        }

        // Enforce minimum roughness to reduce fireflies
        float safeRoughness = max(roughness, 0.02);

        if (materialType == MATERIAL_GLASS) {
            // GLASS (Dielectric)
            float fr = fresnelDielectric(cosTheta, ior);
            if (safeRoughness > 0.01) fr = mix(fr, 0.5, safeRoughness * 0.5);

            if (random(seed) < fr) {
                // Reflection
                vec3 reflectDir = reflect(currentRay.direction, faceNormal);
                if (safeRoughness > 0.001) {
                    vec3 H = randomGGX(seed, faceNormal, safeRoughness);
                    reflectDir = reflect(-viewDir, H);
                }
                currentRay.origin = hitPos + faceNormal * 0.005;
                currentRay.direction = normalize(reflectDir);
                throughput *= mix(vec3(1.0), albedo, 0.05);
            } else {
                // Transmission (Refraction simulation)
                vec3 refractedDir;
                if (refractRay(currentRay.direction, faceNormal, 1.0/ior, refractedDir)) {
                    currentRay.origin = hitPos - faceNormal * 0.005;
                    float bend = clamp((ior - 1.0) * 2.0, 0.0, 1.0);
                    currentRay.direction = normalize(mix(currentRay.direction, refractedDir, bend));
                    throughput *= vec3(0.98, 1.0, 1.02) * albedo;
                } else {
                    currentRay.origin = hitPos + faceNormal * 0.005;
                    currentRay.direction = reflect(currentRay.direction, faceNormal);
                }
            }
        } else if (materialType == MATERIAL_METALLIC) {
            // METALLIC
            vec3 lightDirNorm = normalize(lightDir);
            vec3 H_light = normalize(lightDirNorm + viewDir);
            float NdotL = max(dot(faceNormal, lightDirNorm), 0.0);

            if (NdotL > 0.0) {
                Ray shadowRay;
                float shadowBias = 0.005 + hitDist * 0.01;
                shadowRay.origin = hitPos + faceNormal * shadowBias;
                shadowRay.direction = lightDirNorm;
                vec3 shPos; float shDist;
                if (!rayMarchSimple(shadowRay, shPos, shDist)) {
                    vec3 F0 = mix(vec3(0.04), albedo, metalness);
                    vec3 F = fresnelSchlickVec(max(dot(H_light, viewDir), 0.0), F0);
                    float a = safeRoughness * safeRoughness;
                    float D = a * a / (PI * pow(max(dot(faceNormal, H_light), 0.0) * dot(faceNormal, H_light) * (a * a - 1.0) + 1.0, 2.0));
                    vec3 spec = F * D * NdotL * lightColor * lightIntensity;
                    radiance += clamp(throughput * spec, 0.0, FIREFLY_CLAMP);
                }
            }
            // SSS contribution in path tracing (metallic)
            if (sssIntensity > 0.0) {
                float sss = calcSSS(hitPos, faceNormal, normalize(lightDir));
                radiance += clamp(throughput * albedo * sssColor * sss * sssIntensity * lightColor * lightIntensity, 0.0, FIREFLY_CLAMP);
            }
            vec3 H = randomGGX(seed, faceNormal, safeRoughness);
            vec3 reflectDir = reflect(-viewDir, H);
            vec3 F0 = mix(vec3(0.04), albedo, metalness);
            throughput *= fresnelSchlickVec(cosTheta, F0);
            currentRay.origin = hitPos + faceNormal * 0.005;
            currentRay.direction = normalize(reflectDir);
        } else {
            // LAMBERTIAN
            vec3 lightDirNorm = normalize(lightDir);
            float NdotL = max(dot(faceNormal, lightDirNorm), 0.0);
            if (NdotL > 0.0) {
                Ray shadowRay;
                float shadowBias = 0.005 + hitDist * 0.01;
                shadowRay.origin = hitPos + faceNormal * shadowBias;
                shadowRay.direction = lightDirNorm;
                vec3 shPos; float shDist;
                if (!rayMarchSimple(shadowRay, shPos, shDist)) {
                    radiance += clamp(throughput * albedo * lightColor * lightIntensity * NdotL / PI, 0.0, FIREFLY_CLAMP);
                }
            }
            // SSS contribution in path tracing
            if (sssIntensity > 0.0) {
                float sss = calcSSS(hitPos, faceNormal, normalize(lightDir));
                radiance += clamp(throughput * albedo * sssColor * sss * sssIntensity * lightColor * lightIntensity, 0.0, FIREFLY_CLAMP);
            }
            currentRay.origin = hitPos + faceNormal * 0.005;

            // Stochastic reflection for Lambertian surfaces
            float reflProb = reflectionIntensity * fresnelSchlick(cosTheta, 0.04);
            if (reflectionIntensity > 0.0 && random(seed) < reflProb) {
                // Specular reflection bounce (glossy via GGX)
                vec3 H = randomGGX(seed, faceNormal, max(safeRoughness, 0.3));
                currentRay.direction = normalize(reflect(-viewDir, H));
                throughput *= albedo / reflProb;
            } else {
                // Diffuse bounce
                currentRay.direction = randomCosineHemisphere(seed, faceNormal);
                throughput *= albedo;
            }
        }

        // Russian Roulette
        if (bounce >= 3) {
            float p = max(throughput.x, max(throughput.y, throughput.z));
            if (random(seed) > p) break;
            throughput /= p;
        }
    }

    return clamp(radiance, 0.0, FIREFLY_CLAMP * 2.0);
}

// ============================================================================
// Render Mode Dispatcher
// ============================================================================

vec3 renderByMode(RayHit hit, Ray ray, vec3 normal, float shadow, float ao) {
    vec3 factors = getFactors(hit.trap);
    vec3 baseColor = applyMaterial(factors);

    switch (renderMode) {
        case RENDER_MODE_NORMALS:
            return normal * 0.5 + 0.5;

        case RENDER_MODE_DEPTH:
            // Use logarithmic depth for better visualization of near objects
            // Maps distance 0.1 -> white, distance 10 -> black
            float logDepth = 1.0 - clamp(log(hit.dist + 0.1) / log(15.0), 0.0, 1.0);
            return vec3(logDepth);

        case RENDER_MODE_AO:
            return vec3(ao);

        case RENDER_MODE_SHADOW:
            return vec3(shadow);

        case RENDER_MODE_ITERATIONS:
            return hsv2rgb(vec3(float(hit.steps) / float(maxRaySteps), 0.8, 0.9));

        case RENDER_MODE_ORBIT_TRAP:
            return factors; // Visualise raw geometric factors

        case RENDER_MODE_DIFFUSE:
            return baseColor * max(dot(normal, normalize(lightDir)), 0.0);

        case RENDER_MODE_SPECULAR:
            vec3 halfDir = normalize(normalize(lightDir) - ray.direction);
            float spec = pow(max(dot(normal, halfDir), 0.0), specularPower);
            return vec3(spec);

        default: // RENDER_MODE_FINAL
            return shade(hit, ray);
    }
}

// ============================================================================
// Main Render Function
// ============================================================================

void main() {
    // Initialize random for this pixel/sample
    uint seed = initRandom(gl_FragCoord.xy, sampleIndex);

    // Anti-aliasing jitter
    vec2 jitter = (random2(seed) - 0.5) / resolution;

    // Screen UV with jitter
    vec2 screenUV = fragCoord + jitter * 2.0;

    // Get camera ray (with optional DoF)
    Ray ray = getCameraRayDOF(screenUV, seed);

    vec3 color;
    float depth = 100.0; // Default far distance

    // Choose rendering mode: Path Tracing or Raytracing
    if (pathTracingEnabled != 0 && renderMode == RENDER_MODE_FINAL) {
        // ====== PATH TRACING ======
        color = pathTrace(ray, seed);
        // For path tracing, get depth from center ray (first bounce)
        Ray centerRay = getCameraRay(fragCoord); // No DoF jitter
        RayHit depthHit = rayMarch(centerRay);
        depth = depthHit.hit ? depthHit.dist : 100.0;
        
        // Apply volumetric fog to the path tracing result
        float extinction;
        color = computeVolumetricFog(ray, depth, color, extinction);
    } else {
        // ====== CLASSIC RAYTRACING ======
        RayHit hit = rayMarch(ray);
        depth = hit.hit ? hit.dist : 100.0;

        if (hit.hit) {
            // STRICT COLORING:
            // Evaluate fractal exactly at the surface hit point.
            // This ensures perfect alignment between geometry (Normals/AO) and Material Color.
            DE(hit.pos, hit.trap);
            
            vec3 normal = calcNormal(hit.pos); 
            float shadowBias = 0.001 + hit.dist * 0.001;
            float shadow = calcShadow(hit.pos + normal * shadowBias, normalize(lightDir), shadowBias, 15.0);
            float ao = calcAO(hit.pos, normal);

            color = renderByMode(hit, ray, normal, shadow, ao);
        } else {
            // For debug modes, use simple background; for final mode use fancy background with volumetric fog
            if (renderMode == RENDER_MODE_FINAL) {
                color = shadeBackground(ray, hit.minDist);
            } else {
                // True black background for debug modes
                color = vec3(0.0);
            }
        }
    }

    // Output: RGB = color (accumulated), A = depth (for focus picking)
    // Depth is stored in alpha - will be averaged with colors, but that's fine for focus picking
    FragColor = vec4(color, depth);
}
