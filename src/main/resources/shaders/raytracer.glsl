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
// Normal Calculation (Tetrahedron Method)
// ============================================================================

vec3 calcNormal(vec3 pos) {
    const vec3 k1 = vec3( 1.0, -1.0, -1.0);
    const vec3 k2 = vec3(-1.0, -1.0,  1.0);
    const vec3 k3 = vec3(-1.0,  1.0, -1.0);
    const vec3 k4 = vec3( 1.0,  1.0,  1.0);
    const float e = NORMAL_EPSILON;

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

    // Combine lighting
    vec3 ambient = ambientColor * ambientIntensity * baseColor * ao;
    vec3 diffuse = lightColor * lightIntensity * baseColor * NdotL * shadow;
    vec3 specular = lightColor * spec * specularIntensity * shadow;

    // Fresnel rim lighting
    float rim = fresnel(viewDir, normal, 3.0);
    vec3 rimLight = lightColor * rim * 0.15;

    // Distance fog
    float fogFactor = 1.0 - exp(-hit.dist * 0.05);
    vec3 fogColor = ambientColor * 0.5;

    vec3 color = ambient + diffuse + specular + rimLight;
    color = mix(color, fogColor, fogFactor * 0.3);

    return color;
}

// ============================================================================
// Background
// ============================================================================

vec3 background(Ray ray, float minDist) {
    // Gradient background
    float t = 0.5 + 0.5 * ray.direction.y;
    vec3 bg = mix(vec3(0.1, 0.1, 0.15), vec3(0.02, 0.02, 0.05), t);

    // Glow effect based on how close we got
    float glow = exp(-minDist * 10.0) * glowIntensity;
    bg += fractalPalette(minDist * 2.0) * glow;

    // Simple stars
    vec3 starDir = normalize(ray.direction);
    float stars = pow(max(0.0, sin(starDir.x * 100.0) * sin(starDir.y * 100.0) * sin(starDir.z * 100.0)), 20.0);
    bg += vec3(stars * 0.3);

    return bg;
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

    // Ray march
    RayHit hit = rayMarch(ray);

    // Shade
    vec3 color;
    if (hit.hit) {
        vec3 normal = calcNormal(hit.pos);
        float shadowBias = 0.001 + hit.dist * 0.001;
        float shadow = calcShadow(hit.pos + normal * shadowBias, normalize(lightDir), shadowBias, 15.0);
        float ao = calcAO(hit.pos, normal);

        color = renderByMode(hit, ray, normal, shadow, ao);
    } else {
        // For debug modes, use simple background; for final mode use fancy background with glow
        if (renderMode == RENDER_MODE_FINAL) {
            color = background(ray, hit.minDist);
        } else {
            // True black background for debug modes
            color = vec3(0.0);
        }
    }

    // Output (will be accumulated, tone mapping done in display shader)
    FragColor = vec4(color, 1.0);
}
