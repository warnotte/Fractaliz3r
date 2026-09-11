/**
 * Photon pass: caustics from the sun.
 *
 * Appended to the scene program (common + scene + raytracer) under PHOTON_PASS, in place
 * of the camera main. One fragment is one photon: emitted from a disk facing the sun over
 * the scene, marched like a camera ray, bent by glass (dispersed when dispersion is on)
 * and by smooth metal, and when it lands on a diffuse surface after at least one such
 * bounce, connected to the camera: the pixel it is seen in and the radiance it adds there
 * go out through the two attachments, and the splat pass adds them to the accumulation.
 *
 * What the path tracer cannot reach from the camera, a delta sun behind a chain of
 * specular surfaces, is exactly what lands here; what it does reach (direct light, rough
 * metal through its NEE) is left to it, so nothing is counted twice. See docs/RENDERING.md
 * "Caustics".
 */

layout(location = 0) out vec4 photonPixel;   // xy: where on the tile, in [0,1]; zw: where on the emission square
layout(location = 1) out vec4 photonColor;   // rgb: the radiance it adds to that pixel; a: flags, 1 landed, 2 met glass or metal

uniform int photonSide;          // the pass traces photonSide * photonSide photons
uniform float causticRadius;     // half the side of the emitting square, scene units
uniform vec3 causticCenter;      // the square is centred over this point, facing the sun
uniform int causticDebug;        // 1: photons that met nothing specular land too (the energy check)

// Where the previous passes found glass or metal: the emission square as a grid of cells,
// those that produced a specular hit listed by index, and the same set as a map, both
// snapshots taken together by the engine so the density below is exactly what was drawn.
uniform sampler2D causticCellList;    // causticActiveCells indices, one per texel of row 0
uniform sampler2D causticCellActive;  // CAUSTIC_GRID x CAUSTIC_GRID, 1 where the cell is in the list
uniform int causticActiveCells;
const int CAUSTIC_GRID = 64;
const float CAUSTIC_EXPLORE = 0.25;   // the share of photons drawn over the whole square regardless

bool gLanded = false;
bool gSpecular = false;

// Above this roughness a metal's reflection of the sun is what the path tracer's NEE at the
// metal already computes; below it that estimate is a needle the firefly clamp flattens to
// nothing, and the photons take over.
const float CAUSTIC_METAL_ROUGHNESS = 0.1;

// A single photon may not add more than this many times what a photon drawn uniformly
// over the square adds to a pixel at the distance of the caustic centre: the fireflies of
// a landing right under the camera are capped, ordinary landings (and the up to four
// times heavier photons of the exploring quarter) never are.
const float CAUSTIC_CLAMP = 64.0;

// Connect x to the camera: which pixel sees it, is it visible from there, and what its
// reflected radiance f (the BRDF already applied) adds to that pixel. The contribution of a
// photon of flux phi seen by a pinhole is phi * f * cosX / (d^2 * A_pixel * cosCam^3): the
// pixel's footprint on the surface is A_pixel * cosCam^3 * d^2 / cosX, and the radiance is
// the flux per footprint area times the BRDF. With depth of field the eye is a point of the
// lens and the pixel is the one whose centre ray meets the lens ray on the focal sphere,
// as getCameraRayDOF builds its rays.
void land(vec3 x, vec3 n, vec3 f, inout uint seed) {
    if (projectionMode != 0) return;                       // no caustics in the 360 projection
    vec3 forward = rotateByQuaternion(vec3(0, 0, 1), camQuat);
    vec3 right   = rotateByQuaternion(vec3(1, 0, 0), camQuat);
    vec3 up      = rotateByQuaternion(vec3(0, 1, 0), camQuat);
    vec3 eye = camPos;
    vec3 imagePoint = x;
    if (dofEnabled != 0 && aperture >= 0.0001) {
        float r = sqrt(random(seed));
        float theta = TAU * random(seed);
        vec2 disk = r * vec2(cos(theta), sin(theta));
        if (bokehBlades >= 3) {
            float len = length(disk);
            if (len > 0.0001) {
                float t = atan(disk.y, disk.x) + bokehRotation;
                float nb = float(bokehBlades);
                float halfSector = PI / nb;
                float sectorT = mod(t + halfSector, TAU / nb) - halfSector;
                len = len * cos(PI / nb) / cos(sectorT);
                disk = len * vec2(cos(t), sin(t));
            }
        }
        disk *= aperture;
        disk.y *= anamorphicRatio;
        eye = camPos + right * disk.x + up * disk.y;
        vec3 d = normalize(x - eye);
        vec3 oc = eye - camPos;
        float b = dot(oc, d);
        float c = dot(oc, oc) - focalDistance * focalDistance;
        float disc = b * b - c;
        if (disc < 0.0) return;
        float s = -b + sqrt(disc);
        if (s <= 0.0) return;
        imagePoint = eye + d * s;
    }

    vec3 toX = x - eye;
    float d2 = dot(toX, toX);
    float d = sqrt(d2);
    vec3 dir = toX / d;
    float cosCam = dot(dir, forward);
    if (cosCam <= 0.001) return;
    float cosX = dot(n, -dir);
    if (cosX <= 0.0) return;

    vec3 ip = imagePoint - camPos;
    float z = dot(ip, forward);
    if (z <= 0.0) return;
    float halfH = tan(radians(fov) * 0.5);
    float halfW = halfH * fullResolution.x / fullResolution.y;
    vec2 ndc = vec2(dot(ip, right) / (z * halfW), dot(ip, up) / (z * halfH));
    vec2 tileUV = (ndc * 0.5 + 0.5 - tileOffset) / tileScale;
    if (any(lessThan(tileUV, vec2(0.0))) || any(greaterThanEqual(tileUV, vec2(1.0)))) return;

    Ray toEye;
    toEye.origin = x + n * surfaceBias(d);
    toEye.direction = -dir;
    vec3 vp; float vd; int vm;
    if (rayMarchSimple(toEye, vp, vd, vm) && vd < d - 0.01) return;

    float pixelArea = (2.0 * halfW / fullResolution.x) * (2.0 * halfH / fullResolution.y);
    vec3 c = f * cosX / (d2 * pixelArea * cosCam * cosCam * cosCam);
    float sceneD = max(length(causticCenter - camPos), 0.1);
    float sunLum = dot(lightColor * lightIntensity, vec3(0.2126, 0.7152, 0.0722));
    float reference = sunLum * 4.0 * causticRadius * causticRadius / float(photonSide * photonSide) / (PI * pixelArea * sceneD * sceneD);
    float bound = CAUSTIC_CLAMP * reference;
    photonPixel.xy = tileUV;
    photonColor.rgb = min(c, vec3(bound));
    gLanded = true;
}

// The photon's flight, from the square to wherever it stops; sets the flags.
void trace(Ray ray, vec3 flux, inout uint seed) {
    vec3 weight = vec3(1.0);

    float lambda = 0.0;
    bool dispersed = false;
    if (dispersionEnabled != 0) lambda = 400.0 + 300.0 * random(seed);
    bool specular = false;

    for (int bounce = 0; bounce <= maxBounces; bounce++) {
        vec3 hitPos; float hitDist; int hitMat;
        if (!rayMarchSimple(ray, hitPos, hitDist, hitMat)) return;
        if (bounce == 0 && hitDist < 1e-3) return;            // the disk started inside something

        vec3 normal = calcNormal(hitPos);
        vec3 faceN = dot(ray.direction, normal) > 0.0 ? -normal : normal;

        if (hitMat == MAT_OCEAN) {
            float fr = fresnelDielectric(max(dot(-ray.direction, normal), 0.0), 1.33);
            if (random(seed) < fr) {
                ray.direction = reflect(ray.direction, normal);
                ray.origin = hitPos + normal * 0.005;
                weight *= 0.95;
                specular = true; gSpecular = true;
                continue;
            }
            if (specular || causticDebug != 0) land(hitPos, faceN, flux * weight * oceanColor / PI, seed);
            return;
        }

        OrbitTrap trap;
        DE(hitPos, trap);
        vec3 mf = getFactors(trap);
#ifdef BOOLEAN_OPS
        mf = morphFactors(hitPos, mf);
#endif
        vec3 albedo = applyMaterial(remapTrapFactors(mf, hitPos), hitPos, normal, ray.direction);
        int localMatType = materialType;
        float localIor = ior;
        float localMetalness = metalness;
        float safeRoughness = max(roughness, 0.02);
        float localEmissive = emissiveIntensity;
#ifdef HAS_MATERIALS
        if (trap.matId >= 0) {
            MaterialData mat = materials[trap.matId];
            int mType = int(mat.type);
            int mColorMode = int(mat.colorMode);
            if (mColorMode == 1) albedo = vec3(mat.albedoR, mat.albedoG, mat.albedoB);
            else if (mColorMode == 2) albedo *= vec3(mat.albedoR, mat.albedoG, mat.albedoB);
            if (mType >= 0) localMatType = mType;
            if (mat.roughness >= 0.0) safeRoughness = max(mat.roughness, 0.02);
            if (mat.metallic >= 0.0) localMetalness = mat.metallic;
            if (mat.ior >= 0.0) localIor = mat.ior;
            if (mat.emission >= 0.0) localEmissive = mat.emission;
            if (localEmissive > 0.0) return;                  // a pure emitter, as the path tracer treats it
        }
#endif

        if (localMatType == MATERIAL_GLASS) {
            bool entering = dot(ray.direction, normal) < 0.0;
            float fr = fresnelDielectric(max(dot(-ray.direction, faceN), 0.0), localIor);
            if (safeRoughness > 0.01) fr = mix(fr, 0.5, safeRoughness * 0.5);
            if (random(seed) < fr) {
                vec3 reflectDir = reflect(ray.direction, faceN);
                if (safeRoughness > 0.001) reflectDir = reflect(ray.direction, randomGGX(seed, faceN, safeRoughness));
                ray.origin = hitPos + faceN * 0.005;
                ray.direction = normalize(reflectDir);
                weight *= mix(vec3(1.0), albedo, 0.05);
            } else {
                if (dispersionEnabled != 0) {
                    localIor = dispersedIor(localIor, lambda);
                    if (!dispersed) { weight *= spectralTint(lambda); dispersed = true; }
                }
                float entryEta = entering ? (1.0 / localIor) : localIor;
                vec3 refractedDir;
                if (!refractRay(ray.direction, faceN, entryEta, refractedDir)) {
                    ray.origin = hitPos + faceN * 0.005;
                    ray.direction = reflect(ray.direction, faceN);
                    weight *= mix(vec3(1.0), albedo, 0.05);
                } else if (entering) {
                    vec3 interiorDir = normalize(refractedDir);
                    float t = 0.02;
                    vec3 exitPos = hitPos;
                    for (int gs = 0; gs < 128; gs++) {
                        exitPos = hitPos + interiorDir * t;
                        float dd = sceneDE_simple(exitPos);
                        if (dd > 0.0) { exitPos -= interiorDir * dd; break; }
                        t += max(abs(dd), 0.002);
                        if (t > 10.0) break;
                    }
                    vec3 exitNormal = calcNormal(exitPos);
                    vec3 exitFaceN = dot(interiorDir, exitNormal) > 0.0 ? -exitNormal : exitNormal;
                    vec3 exitRefracted;
                    ray.direction = refractRay(interiorDir, exitFaceN, localIor, exitRefracted) ? normalize(exitRefracted) : interiorDir;
                    ray.origin = exitPos + exitNormal * 0.005;
                    weight *= vec3(0.98, 1.0, 1.02) * albedo;
                } else {
                    ray.origin = hitPos - faceN * 0.005;
                    ray.direction = normalize(refractedDir);
                    weight *= vec3(0.98, 1.0, 1.02) * albedo;
                }
            }
            specular = true; gSpecular = true;
        } else if (localMatType == MATERIAL_METALLIC) {
            if (safeRoughness > CAUSTIC_METAL_ROUGHNESS) return;   // the path tracer's estimate stands
            vec3 viewDir = -ray.direction;
            float NdotV = max(dot(viewDir, faceN), 0.001);
            vec3 F0 = mix(vec3(0.04), albedo, localMetalness);
            vec3 H = randomGGX(seed, faceN, safeRoughness);
            vec3 reflectDir = reflect(-viewDir, H);
            float NdotH = max(dot(faceN, H), 0.001);
            float VdotH = max(dot(viewDir, H), 0.001);
            float NdotL = max(dot(faceN, reflectDir), 0.0);
            float a = safeRoughness * safeRoughness;
            float a2 = a * a;
            vec3 F = fresnelSchlickVec(VdotH, F0);
            float G = smithG2GGX(max(NdotL, 0.001), NdotV, a2);
            weight *= F * G * VdotH / (NdotV * NdotH);
            ray.origin = hitPos + faceN * 0.005;
            ray.direction = normalize(reflectDir);
            specular = true; gSpecular = true;
        } else {
            if (specular || causticDebug != 0) land(hitPos, faceN, flux * weight * albedo / PI, seed);
            return;
        }

        if (bounce >= 3) {
            float p = max(weight.x, max(weight.y, weight.z));
            if (random(seed) > p) return;
            weight /= p;
        }
    }
}

void main() {
    photonPixel = vec4(0.0);
    photonColor = vec4(0.0);
    uint seed = initRandom(gl_FragCoord.xy + vec2(0.5, 7919.0), sampleIndex);

    // Emission: a point of the square facing the sun, sent the way the sun shines. A
    // quarter of the photons are drawn over the whole square, the rest over the cells that
    // have produced a specular hit so far; each carries the flux its draw stands for, so
    // the estimate is the same whatever the map, only less noisy where it matters.
    vec3 L = normalize(lightDir);
    vec3 u = normalize(cross(abs(L.y) < 0.999 ? vec3(0, 1, 0) : vec3(1, 0, 0), L));
    vec3 v = cross(L, u);
    float side = 2.0 * causticRadius;
    vec2 sq;
    if (causticActiveCells > 0 && random(seed) >= CAUSTIC_EXPLORE) {
        int i = min(int(random(seed) * float(causticActiveCells)), causticActiveCells - 1);
        int k = int(texelFetch(causticCellList, ivec2(i, 0), 0).r + 0.5);
        sq = (vec2(k % CAUSTIC_GRID, k / CAUSTIC_GRID) + vec2(random(seed), random(seed))) / float(CAUSTIC_GRID);
    } else {
        sq = vec2(random(seed), random(seed));
    }
    float density = 1.0 / (side * side);
    if (causticActiveCells > 0) {
        float inList = texelFetch(causticCellActive, ivec2(sq * float(CAUSTIC_GRID)), 0).r;
        float cellArea = (side / float(CAUSTIC_GRID)) * (side / float(CAUSTIC_GRID));
        density = CAUSTIC_EXPLORE / (side * side) + (1.0 - CAUSTIC_EXPLORE) * inList / (float(causticActiveCells) * cellArea);
    }
    photonPixel.zw = sq;
    Ray ray;
    ray.origin = causticCenter + L * (2.0 * causticRadius + 2.0) + u * (side * (sq.x - 0.5)) + v * (side * (sq.y - 0.5));
    ray.direction = -jitterLightDir(L, seed, shadowSoftness);
    vec3 flux = lightColor * lightIntensity / (density * float(photonSide * photonSide));

    trace(ray, flux, seed);
    photonColor.a = (gLanded ? 1.0 : 0.0) + (gSpecular ? 2.0 : 0.0);
}
