/**
 * Generic Raytracer for Fractal Rendering
 *
 * This file is FRACTAL-AGNOSTIC. It requires the fractal to define:
 * - float DE(vec3 pos, out OrbitTrap trap)  - Distance estimator with orbit traps
 * - float DE_simple(vec3 pos)               - Simple DE for shadows/AO/normals
 * - vec3 getColor(OrbitTrap trap)           - Color from orbit traps
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

    // Adaptive epsilon based on distance from camera
    // This prevents precision issues at different scales
    float distToCamera = length(pos - camPos);
    float e = max(NORMAL_EPSILON, distToCamera * 0.0001);

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

    for (int i = 0; i < steps && t < maxt; i++) {
        float h = DE_simple(ro + rd * t);

        if (h < 0.0001) return 0.0;

        res = min(res, shadowSoftness * h / t);
        t += clamp(h, 0.01, 0.5);
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
// Shading
// ============================================================================

vec3 shade(RayHit hit, Ray ray) {
    vec3 normal = calcNormal(hit.pos);
    vec3 viewDir = -ray.direction;
    vec3 light = normalize(lightDir);

    // Base color from orbit traps
    vec3 baseColor = getColor(hit.trap);

    // Diffuse (Lambert)
    float NdotL = max(dot(normal, light), 0.0);

    // Specular (Blinn-Phong)
    vec3 halfDir = normalize(light + viewDir);
    float spec = pow(max(dot(normal, halfDir), 0.0), specularPower);

    // Shadow
    float shadowBias = 0.001 + hit.dist * 0.001;
    float shadow = calcShadow(hit.pos + normal * shadowBias, light, shadowBias, 15.0);

    // Ambient occlusion
    float ao = calcAO(hit.pos, normal);

    // Combine lighting - use environment-aware ambient
    vec3 ambient = getAmbientLighting(normal) * baseColor * ao;
    vec3 diffuse = lightColor * lightIntensity * baseColor * NdotL * shadow;
    vec3 specular = lightColor * spec * specularIntensity * shadow;

    // Environment reflection for specular (if HDRI loaded)
    if (useEnvMap != 0 && envLightingMix > 0.0) {
        vec3 reflectDir = reflect(ray.direction, normal);
        vec3 envReflect = sampleEnvironment(reflectDir);
        float fresnelFactor = fresnel(viewDir, normal, 5.0);
        specular = mix(specular, envReflect * specularIntensity * fresnelFactor, envLightingMix * 0.5);
    }

    // Fresnel rim lighting
    float rim = fresnel(viewDir, normal, 3.0);
    vec3 rimLight = lightColor * rim * 0.15;

    // Distance fog - use environment color for fog if available
    float fogFactor = 1.0 - exp(-hit.dist * 0.05);
    vec3 fogColor = (useEnvMap != 0) ? sampleEnvironment(ray.direction) * 0.3 : ambientColor * 0.5;

    vec3 color = ambient + diffuse + specular + rimLight;
    color = mix(color, fogColor, fogFactor * 0.3);

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
    int effectiveMaxSteps = int(float(maxRaySteps) * qualityMultiplier * 0.5); // Fewer steps for bounces
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

    Ray currentRay = ray;

    for (int bounce = 0; bounce <= maxBounces; bounce++) {
        vec3 hitPos;
        float hitDist;

        if (!rayMarchSimple(currentRay, hitPos, hitDist)) {
            // Ray escaped - add environment light
            // First bounce gets full environment, subsequent bounces are scaled by indirectMultiplier
            float envScale = (bounce == 0) ? 1.0 : indirectMultiplier;
            radiance += throughput * sampleEnvironment(currentRay.direction) * envScale;
            break;
        }

        // Calculate normal at hit point
        vec3 normal = calcNormal(hitPos);

        // Ensure normal faces the ray (for correct shading)
        vec3 faceNormal = normal;
        if (dot(currentRay.direction, normal) > 0.0) {
            faceNormal = -normal;
        }

        // Get surface color (albedo) from orbit traps at hit position
        OrbitTrap trap;
        DE(hitPos, trap);
        vec3 albedo = getColor(trap);

        vec3 viewDir = -currentRay.direction;
        float cosTheta = max(dot(viewDir, faceNormal), 0.0);

        // ====================================================================
        // Material-specific behavior
        // ====================================================================

        if (materialType == MATERIAL_GLASS) {
            // ============================================================
            // GLASS (Dielectric) - For fractals: Surface refraction effect
            // ============================================================
            // Note: True volumetric glass doesn't work well with fractals
            // because there's no clear "inside" to traverse. Instead, we
            // simulate a glass-like surface with refraction and reflection.

            // Fresnel determines reflection vs transmission
            float fresnel = fresnelDielectric(cosTheta, ior);

            // Add roughness to Fresnel for frosted glass effect
            if (roughness > 0.01) {
                fresnel = mix(fresnel, 0.5, roughness * 0.5);
            }

            if (random(seed) < fresnel) {
                // Reflection path
                vec3 reflectDir = reflect(currentRay.direction, faceNormal);

                // Add roughness to reflection
                if (roughness > 0.001) {
                    vec3 H = randomGGX(seed, faceNormal, roughness);
                    reflectDir = reflect(-viewDir, H);
                    if (dot(reflectDir, faceNormal) < 0.0) {
                        reflectDir = reflect(currentRay.direction, faceNormal);
                    }
                }

                currentRay.origin = hitPos + faceNormal * 0.002;
                currentRay.direction = normalize(reflectDir);

                // Slight tint on reflection
                throughput *= mix(vec3(1.0), albedo, 0.05);
            } else {
                // Transmission path - simulate looking "through" the surface
                // by bending the ray and continuing into the environment
                vec3 refractedDir;
                float eta = 1.0 / ior;

                if (refractRay(currentRay.direction, faceNormal, eta, refractedDir)) {
                    // For fractals: instead of going "into" the solid,
                    // we bend the ray and let it continue to the environment
                    // This simulates the visual distortion of glass

                    // Offset away from surface to avoid self-intersection
                    currentRay.origin = hitPos + faceNormal * 0.002;

                    // Mix between refracted direction and original direction
                    // based on IOR - higher IOR = more bending
                    float bendStrength = clamp((ior - 1.0) * 2.0, 0.0, 1.0);
                    currentRay.direction = normalize(mix(currentRay.direction, refractedDir, bendStrength));

                    // Add chromatic dispersion effect for realism
                    vec3 dispersion = vec3(0.98, 1.0, 1.02);
                    throughput *= dispersion;
                } else {
                    // Total internal reflection
                    vec3 reflectDir = reflect(currentRay.direction, faceNormal);
                    currentRay.origin = hitPos + faceNormal * 0.002;
                    currentRay.direction = normalize(reflectDir);
                }

                // Glass tint (more transparent than reflective path)
                throughput *= mix(vec3(1.0), albedo, 0.02);
            }

        } else if (materialType == MATERIAL_METALLIC) {
            // ============================================================
            // METALLIC - Specular reflection with roughness
            // ============================================================

            // Direct lighting for metals (specular only)
            vec3 lightDirNorm = normalize(lightDir);
            vec3 H_light = normalize(lightDirNorm + viewDir);
            float NdotH = max(dot(faceNormal, H_light), 0.0);
            float NdotL = max(dot(faceNormal, lightDirNorm), 0.0);

            if (NdotL > 0.0) {
                // Shadow ray
                Ray shadowRay;
                shadowRay.origin = hitPos + faceNormal * 0.001;
                shadowRay.direction = lightDirNorm;

                vec3 shadowHitPos;
                float shadowDist;
                bool inShadow = rayMarchSimple(shadowRay, shadowHitPos, shadowDist);

                if (!inShadow) {
                    // Metal F0 is the albedo color (colored metals)
                    vec3 F0 = mix(vec3(0.04), albedo, metalness);
                    vec3 F = fresnelSchlickVec(max(dot(H_light, viewDir), 0.0), F0);

                    // Simplified specular term
                    float a = roughness * roughness;
                    float D = a * a / (PI * pow(NdotH * NdotH * (a * a - 1.0) + 1.0, 2.0));

                    vec3 specular = F * D * NdotL * lightColor * lightIntensity;
                    radiance += throughput * specular;
                }
            }

            // Sample reflection direction with GGX
            vec3 H = randomGGX(seed, faceNormal, max(roughness, 0.001));
            vec3 reflectDir = reflect(-viewDir, H);

            // Ensure reflection is in correct hemisphere
            if (dot(reflectDir, faceNormal) < 0.0) {
                reflectDir = reflect(currentRay.direction, faceNormal);
            }

            // Fresnel for metals (F0 = albedo for metallic materials)
            vec3 F0 = mix(vec3(0.04), albedo, metalness);
            vec3 F = fresnelSchlickVec(cosTheta, F0);

            throughput *= F;

            currentRay.origin = hitPos + faceNormal * 0.001;
            currentRay.direction = normalize(reflectDir);

        } else {
            // ============================================================
            // LAMBERTIAN (Diffuse) - Original behavior
            // ============================================================

            // Direct lighting (Next Event Estimation)
            vec3 lightDirNorm = normalize(lightDir);
            float NdotL = max(dot(faceNormal, lightDirNorm), 0.0);

            if (NdotL > 0.0) {
                // Shadow ray
                Ray shadowRay;
                shadowRay.origin = hitPos + faceNormal * 0.001;
                shadowRay.direction = lightDirNorm;

                vec3 shadowHitPos;
                float shadowDist;
                bool inShadow = rayMarchSimple(shadowRay, shadowHitPos, shadowDist);

                if (!inShadow) {
                    // Direct light contribution
                    vec3 directLight = lightColor * lightIntensity * NdotL;
                    radiance += throughput * albedo * directLight / PI;
                }
            }

            // Sample new direction (cosine-weighted hemisphere)
            vec3 newDir = randomCosineHemisphere(seed, faceNormal);

            // Update throughput with BRDF (Lambertian: albedo/PI, cosine already in sampling)
            throughput *= albedo;

            currentRay.origin = hitPos + faceNormal * 0.001;
            currentRay.direction = newDir;
        }

        // Russian Roulette for path termination
        if (bounce >= 2) {
            float p = max(throughput.x, max(throughput.y, throughput.z));
            if (random(seed) > p) break;
            throughput /= p;
        }
    }

    return radiance;
}

// ============================================================================
// Render Mode Dispatcher
// ============================================================================

vec3 renderByMode(RayHit hit, Ray ray, vec3 normal, float shadow, float ao) {
    vec3 baseColor = getColor(hit.trap);

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
            return baseColor;

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
    } else {
        // ====== CLASSIC RAYTRACING ======
        RayHit hit = rayMarch(ray);
        depth = hit.hit ? hit.dist : 100.0;

        if (hit.hit) {
            vec3 normal = calcNormal(hit.pos);
            float shadowBias = 0.001 + hit.dist * 0.001;
            float shadow = calcShadow(hit.pos + normal * shadowBias, normalize(lightDir), shadowBias, 15.0);
            float ao = calcAO(hit.pos, normal);

            color = renderByMode(hit, ray, normal, shadow, ao);
        } else {
            // For debug modes, use simple background; for final mode use fancy background with glow
            if (renderMode == RENDER_MODE_FINAL) {
                color = sampleEnvironmentWithGlow(ray.direction, hit.minDist);
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
