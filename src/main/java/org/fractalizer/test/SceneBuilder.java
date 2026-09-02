package org.fractalizer.test;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.graph.*;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * Fluent builder for .frac scene files.
 * Provides sensible defaults so you only specify what you care about.
 *
 * Example:
 * <pre>
 * SceneBuilder.mandelbulb()
 *     .camera(0, 0.5, -3).lookAt(0, 0, 0)
 *     .param("power", 8.0)
 *     .duration(6).fps(30)
 *     .track("mandelbulb.power").key(0, 3f).key(3, 8f).key(6, 3f).done()
 *     .orbitCamera(new float[]{0,0,0}, 3f, 0.5f, 0, 180, 6.0)
 *     .writeTo("output.frac");
 * </pre>
 */
public class SceneBuilder {

    private final FractalConfig config;
    private final List<TrackBuilder> trackBuilders = new ArrayList<>();
    private float[] cameraPos;
    private float[] cameraQuat;

    private SceneBuilder(String fractalType) {
        config = new FractalConfig();
        config.version = "1.0";
        config.timestamp = Instant.now().toString();
        config.fractalType = fractalType;

        // Sensible defaults
        config.camera.position = new float[]{0, 0, -3};
        config.camera.quaternion = new float[]{1, 0, 0, 0};
        config.camera.fov = 60;
        config.camera.moveSpeed = 0.1f;

        config.rendering.maxRaySteps = 200;
        config.rendering.epsilon = 1e-4f;
        config.rendering.qualityMultiplier = 1.0f;
        config.rendering.shadowSoftness = 16;
        config.rendering.shadowSteps = 128;
        config.rendering.aoSteps = 5;
        config.rendering.aoIntensity = 0.5f;
        config.rendering.glowIntensity = 0.15f;
        config.rendering.specularIntensity = 0.5f;
        config.rendering.specularPower = 32;

        config.lighting.direction = new float[]{2, 3, -2};
        config.lighting.color = new float[]{1, 0.95f, 0.9f};
        config.lighting.intensity = 1.2f;
        config.lighting.ambientColor = new float[]{0.1f, 0.15f, 0.25f};
        config.lighting.ambientIntensity = 0.3f;
        config.lighting.extraType = 0;
        config.lighting.extraAttachToCamera = true;
        config.lighting.extraPosition = new float[]{0, 0, 0};
        config.lighting.extraDirection = new float[]{0, 0, 1};
        config.lighting.extraColor = new float[]{1, 0.95f, 0.9f};
        config.lighting.extraIntensity = 1.5f;
        config.lighting.extraRange = 2;
        config.lighting.extraAreaRadius = 0.03f;
        config.lighting.extraConeAngle = 35;
        config.lighting.extraConeSoftness = 0.3f;

        config.material.hue = new float[]{0, 0.33f, 0.67f};
        config.material.paletteIndex = 0;
        config.material.colorStrength = 1;
        config.material.type = 0;
        config.material.metalness = 0.3f;
        config.material.ior = 1.5f;
        config.material.gradientStops = defaultGradient();

        config.effects.skyType = 1;
        config.effects.cloudDensity = 0.5f;
        config.effects.skySpeed = 1;
        config.effects.skyParallax = 0.25f;
        config.effects.skyIntensity = 1;
        config.effects.roughness = 0.5f;
        config.effects.maxBounces = 4;
        config.effects.indirectMultiplier = 0.5f;
        config.effects.shutterAngle = 180;
        config.effects.neeEnabled = true;
        config.effects.fogColor = new float[]{0.5f, 0.6f, 0.7f};
        config.effects.fogDensity = 0.15f;
        config.effects.fogScattering = 0.5f;
        config.effects.fogSteps = 32;
        config.effects.dofSamples = 16;
        config.effects.aperture = 0.02f;
        config.material.sssColor = new float[]{1, 0.4f, 0.2f};
        config.material.sssRadius = 0.1f;

        config.fractalParams = new HashMap<>();
    }

    // ========================================================================
    // Factory methods for common fractals with good defaults
    // ========================================================================

    public static SceneBuilder mandelbulb() {
        return new SceneBuilder("MANDELBULB")
            .param("maxIterations", 15).param("power", 8.0).param("bailout", 2.0);
    }

    public static SceneBuilder menger() {
        SceneBuilder b = new SceneBuilder("MENGER_SPONGE")
            .param("maxIterations", 6).param("scale", 3.0)
            .param("offsetX", 1.0).param("offsetY", 1.0).param("offsetZ", 1.0);
        b.config.camera.position = new float[]{0, 0, -4};
        return b;
    }

    public static SceneBuilder mandelbox() {
        return new SceneBuilder("MANDELBOX")
            .param("maxIterations", 15).param("scale", -1.5)
            .param("minRadius", 0.5).param("fixedRadius", 1.0).param("foldingLimit", 1.0);
    }

    public static SceneBuilder kaleidoscopic() {
        return new SceneBuilder("KALEIDOSCOPIC_IFS")
            .param("maxIterations", 20).param("scale", 2.0)
            .param("offsetX", 1.0).param("offsetY", 1.0).param("offsetZ", 1.0);
    }

    public static SceneBuilder quaternionJulia() {
        return new SceneBuilder("QUATERNION_JULIA_4D")
            .param("maxIterations", 15).param("bailout", 4.0)
            .param("juliaCx", -0.2).param("juliaCy", 0.8)
            .param("juliaCz", 0.0).param("juliaCw", 0.0);
    }

    public static SceneBuilder sierpinski() {
        return new SceneBuilder("SIERPINSKI")
            .param("maxIterations", 15).param("scale", 2.0);
    }

    public static SceneBuilder apollonian() {
        return new SceneBuilder("APOLLONIAN")
            .param("maxIterations", 12).param("scale", 1.0).param("foldRadius", 0.8);
    }

    public static SceneBuilder bristorbrot() {
        return new SceneBuilder("BRISTORBROT")
            .param("maxIterations", 15).param("bailout", 2.0);
    }

    public static SceneBuilder create(String fractalType) {
        return new SceneBuilder(fractalType);
    }

    // ========================================================================
    // Node Graph factory
    // ========================================================================

    public static SceneBuilder nodeGraph(GraphNode root) {
        SceneBuilder b = new SceneBuilder("NODE_GRAPH");
        b.config.fractalParams.put("graph", FractalConfig.serializeGraphNode(root));
        return b;
    }

    // ========================================================================
    // Node Graph helpers — static builders for graph tree construction
    // ========================================================================

    /** Leaf node wrapping a built-in fractal type with default params. */
    public static FractalNode fractal(FractalType type) {
        return new FractalNode(type);
    }

    // --- CSG operations ---

    public static CSGNode union(GraphNode left, GraphNode right) {
        return new CSGNode(CSGNode.Op.UNION, left, right);
    }

    public static CSGNode union(GraphNode left, GraphNode right, float blend) {
        return new CSGNode(CSGNode.Op.UNION, left, right, blend);
    }

    public static CSGNode intersect(GraphNode left, GraphNode right) {
        return new CSGNode(CSGNode.Op.INTERSECT, left, right);
    }

    public static CSGNode intersect(GraphNode left, GraphNode right, float blend) {
        return new CSGNode(CSGNode.Op.INTERSECT, left, right, blend);
    }

    public static CSGNode subtract(GraphNode left, GraphNode right) {
        return new CSGNode(CSGNode.Op.SUBTRACT, left, right);
    }

    public static CSGNode subtract(GraphNode left, GraphNode right, float blend) {
        return new CSGNode(CSGNode.Op.SUBTRACT, left, right, blend);
    }

    public static CSGNode morphGraph(GraphNode left, GraphNode right, float blend) {
        return new CSGNode(CSGNode.Op.MORPH, left, right, blend);
    }

    // --- Transform wrappers ---

    public static TransformNode translate(GraphNode child, float x, float y, float z) {
        return new TransformNode(child, new float[]{x, y, z});
    }

    public static TransformNode rotate(GraphNode child, float rx, float ry, float rz) {
        return new TransformNode(child, new float[]{0, 0, 0}, new float[]{rx, ry, rz}, 1.0f);
    }

    public static TransformNode scale(GraphNode child, float s) {
        return new TransformNode(child, new float[]{0, 0, 0}, new float[]{0, 0, 0}, s);
    }

    public static TransformNode transform(GraphNode child, float[] offset, float[] rotation, float scale) {
        return new TransformNode(child, offset, rotation, scale);
    }

    public static TransformNode twist(GraphNode child, float strength, int axis) {
        TransformNode tn = new TransformNode(child, new float[]{0, 0, 0});
        tn.setMode(TransformNode.Mode.TWIST);
        tn.setScale(strength);
        tn.setAxis(axis);
        return tn;
    }

    public static TransformNode bend(GraphNode child, float strength, int axis) {
        TransformNode tn = new TransformNode(child, new float[]{0, 0, 0});
        tn.setMode(TransformNode.Mode.BEND);
        tn.setScale(strength);
        tn.setAxis(axis);
        return tn;
    }

    public static TransformNode taper(GraphNode child, float strength, int axis) {
        TransformNode tn = new TransformNode(child, new float[]{0, 0, 0});
        tn.setMode(TransformNode.Mode.TAPER);
        tn.setScale(strength);
        tn.setAxis(axis);
        return tn;
    }

    public static TransformNode mirror(GraphNode child, int axis) {
        TransformNode tn = new TransformNode(child, new float[]{0, 0, 0});
        tn.setMode(TransformNode.Mode.MIRROR);
        tn.setAxis(axis);
        return tn;
    }

    public static TransformNode repeat(GraphNode child, float px, float py, float pz) {
        TransformNode tn = new TransformNode(child, new float[]{px, py, pz});
        tn.setMode(TransformNode.Mode.REPETITION);
        return tn;
    }

    public static TransformNode repeat1D(GraphNode child, int axis, float period) {
        TransformNode tn = new TransformNode(child, new float[]{0, 0, 0});
        tn.setMode(TransformNode.Mode.REPETITION_1D);
        tn.setAxis(axis);
        tn.setFrequency(period);
        return tn;
    }

    // ========================================================================
    // Effects (per-node surface effects)
    // ========================================================================

    public static EffectNode erode(GraphNode child, float strength, float time, float scale) {
        EffectNode en = new EffectNode(child, EffectNode.EffectType.EROSION);
        en.setStrength(strength);
        en.setTime(time);
        en.setScale(scale);
        return en;
    }

    public static EffectNode erode(GraphNode child, float strength, float time, float scale, int erosionType) {
        EffectNode en = erode(child, strength, time, scale);
        en.setErosionType(erosionType);
        return en;
    }

    public static EffectNode crystallize(GraphNode child, float strength, float time, float scale, float sharpness) {
        EffectNode en = new EffectNode(child, EffectNode.EffectType.CRYSTAL);
        en.setStrength(strength);
        en.setTime(time);
        en.setScale(scale);
        en.setSharpness(sharpness);
        return en;
    }

    public static EffectNode mossify(GraphNode child, float strength, float time, float scale) {
        EffectNode en = new EffectNode(child, EffectNode.EffectType.MOSS);
        en.setStrength(strength);
        en.setTime(time);
        en.setScale(scale);
        return en;
    }

    // ========================================================================
    // Camera
    // ========================================================================

    public SceneBuilder camera(float x, float y, float z) {
        config.camera.position = new float[]{x, y, z};
        cameraPos = config.camera.position;
        return this;
    }

    public SceneBuilder lookAt(float tx, float ty, float tz) {
        float[] target = {tx, ty, tz};
        float[] eye = config.camera.position;
        config.camera.quaternion = CameraUtils.lookAt(eye, target);
        cameraQuat = config.camera.quaternion;
        return this;
    }

    public SceneBuilder fov(float degrees) {
        config.camera.fov = degrees;
        return this;
    }

    /**
     * Add an orbital camera animation around a center point.
     * Automatically creates camPos and camQuat tracks with spline interpolation.
     */
    public SceneBuilder orbitCamera(float[] center, float radius, float height,
                                     float startAngle, float endAngle, double durationSec) {
        int numKeyframes = Math.max(3, (int)(Math.abs(endAngle - startAngle) / 30) + 1);
        float[][][] orbit = CameraUtils.orbit(center, radius, height, startAngle, endAngle, numKeyframes);

        TrackBuilder posTrack = new TrackBuilder(this, "camPos", "float[]",
            Arrays.asList((double) center[0], (double) center[1], (double)(center[2] - radius)));
        posTrack.spline = true;

        TrackBuilder quatTrack = new TrackBuilder(this, "camQuat", "float[]",
            Arrays.asList(0.0, 0.0, 0.0, 1.0));
        quatTrack.spline = true;

        for (int i = 0; i < numKeyframes; i++) {
            double t = numKeyframes == 1 ? 0 : (double) i / (numKeyframes - 1) * durationSec;
            float[] pos = orbit[i][0];
            float[] quat = orbit[i][1];

            posTrack.keyframes.add(new KF(t, Arrays.asList((double) pos[0], (double) pos[1], (double) pos[2]), "LINEAR"));
            quatTrack.keyframes.add(new KF(t, Arrays.asList((double) quat[0], (double) quat[1], (double) quat[2], (double) quat[3]), "LINEAR"));
        }

        trackBuilders.add(posTrack);
        trackBuilders.add(quatTrack);

        // Set initial camera position
        config.camera.position = orbit[0][0];
        config.camera.quaternion = orbit[0][1];

        return this;
    }

    /**
     * Dolly shot: straight-line move from start to end while looking at target.
     */
    public SceneBuilder dollyCamera(float[] startPos, float[] endPos, float[] target, double durationSec) {
        int numKeyframes = Math.max(3, (int)(durationSec * 2) + 1);
        float[][][] path = CameraUtils.dolly(startPos, endPos, target, numKeyframes);
        return applyCameraPath(path, durationSec);
    }

    /**
     * Spiral approach: orbit while closing in, with height change.
     */
    public SceneBuilder spiralCamera(float[] center, float startRadius, float endRadius,
                                      float startHeight, float endHeight,
                                      float startAngle, float endAngle, double durationSec) {
        int numKeyframes = Math.max(4, (int)(Math.abs(endAngle - startAngle) / 25) + 1);
        float[][][] path = CameraUtils.spiral(center, startRadius, endRadius,
            startHeight, endHeight, startAngle, endAngle, numKeyframes);
        return applyCameraPath(path, durationSec);
    }

    /**
     * Crane shot: arc from low to high while looking at target.
     */
    public SceneBuilder craneCamera(float[] center, float radius, float angle,
                                     float startHeight, float endHeight, double durationSec) {
        int numKeyframes = Math.max(3, (int)(durationSec * 2) + 1);
        float[][][] path = CameraUtils.crane(center, radius, angle, startHeight, endHeight, numKeyframes);
        return applyCameraPath(path, durationSec);
    }

    /** Shared logic: inject a camera path as camPos + camQuat tracks. */
    private SceneBuilder applyCameraPath(float[][][] path, double durationSec) {
        int n = path.length;
        TrackBuilder posTrack = new TrackBuilder(this, "camPos", "float[]",
            Arrays.asList((double) path[0][0][0], (double) path[0][0][1], (double) path[0][0][2]));
        posTrack.spline = true;

        TrackBuilder quatTrack = new TrackBuilder(this, "camQuat", "float[]",
            Arrays.asList((double) path[0][1][0], (double) path[0][1][1],
                          (double) path[0][1][2], (double) path[0][1][3]));
        quatTrack.spline = true;

        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 0 : (double) i / (n - 1) * durationSec;
            float[] pos = path[i][0];
            float[] quat = path[i][1];
            posTrack.keyframes.add(new KF(t, Arrays.asList((double) pos[0], (double) pos[1], (double) pos[2]), "LINEAR"));
            quatTrack.keyframes.add(new KF(t, Arrays.asList((double) quat[0], (double) quat[1], (double) quat[2], (double) quat[3]), "LINEAR"));
        }

        trackBuilders.add(posTrack);
        trackBuilders.add(quatTrack);
        config.camera.position = path[0][0];
        config.camera.quaternion = path[0][1];
        return this;
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    public SceneBuilder pathTracing(boolean enabled) {
        config.effects.pathTracingEnabled = enabled;
        return this;
    }

    public SceneBuilder skyType(int type) {
        config.effects.skyType = type;
        return this;
    }

    public SceneBuilder skyTime(float time) {
        config.effects.skyTime = time;
        return this;
    }

    public SceneBuilder dof(float focalDist, float aperture) {
        config.effects.dofEnabled = true;
        config.effects.focalDistance = focalDist;
        config.effects.aperture = aperture;
        return this;
    }

    public SceneBuilder palette(int index) {
        config.material.paletteIndex = index;
        return this;
    }

    public SceneBuilder metalness(float m) {
        config.material.metalness = m;
        return this;
    }

    public SceneBuilder roughness(float r) {
        config.effects.roughness = r;
        return this;
    }

    // ========================================================================
    // Effects (one-liners for common settings)
    // ========================================================================

    public SceneBuilder erosion(float strength, float time, float scale) {
        config.effects.erosionEnabled = true;
        config.effects.erosionStrength = strength;
        config.effects.erosionTime = time;
        config.effects.erosionScale = scale;
        return this;
    }

    public SceneBuilder erosionType(int type) {
        config.effects.erosionType = type;
        return this;
    }

    public SceneBuilder crystal(float strength, float time, float scale) {
        config.effects.crystalEnabled = true;
        config.effects.crystalStrength = strength;
        config.effects.crystalTime = time;
        config.effects.crystalScale = scale;
        return this;
    }

    public SceneBuilder moss(float strength, float time, float scale) {
        config.effects.mossEnabled = true;
        config.effects.mossStrength = strength;
        config.effects.mossTime = time;
        config.effects.mossScale = scale;
        return this;
    }

    @Deprecated public SceneBuilder twist(float strength, float frequency) { return this; }
    @Deprecated public SceneBuilder bend(float strength, float frequency) { return this; }
    @Deprecated public SceneBuilder taper(float strength, float frequency) { return this; }
    @Deprecated public SceneBuilder repetition(float frequency) { return this; }
    @Deprecated public SceneBuilder repetition3D(float frequency) { return this; }
    @Deprecated public SceneBuilder distortionAxis(int axis) { return this; }

    public SceneBuilder morph(String secondaryKernel) {
        config.effects.booleanEnabled = true;
        config.effects.booleanOp = 5;
        config.effects.boolSecondaryType = secondaryKernel;
        config.effects.boolBlend = 0;
        return this;
    }

    public SceneBuilder booleanOp(String secondaryKernel, int op, float blend) {
        config.effects.booleanEnabled = true;
        config.effects.booleanOp = op;
        config.effects.boolSecondaryType = secondaryKernel;
        config.effects.boolBlend = blend;
        return this;
    }

    public SceneBuilder boolOffset(float x, float y, float z) {
        config.effects.boolOffsetX = x;
        config.effects.boolOffsetY = y;
        config.effects.boolOffsetZ = z;
        return this;
    }

    public SceneBuilder boolScale(float s) {
        config.effects.boolScale = s;
        return this;
    }

    public SceneBuilder boolRotation(float rx, float ry, float rz) {
        config.effects.boolRotX = rx;
        config.effects.boolRotY = ry;
        config.effects.boolRotZ = rz;
        return this;
    }

    public SceneBuilder nesting(String secondaryKernel, float threshold, float repeatScale, float mix) {
        config.effects.booleanEnabled = true;
        config.effects.booleanOp = 4;
        config.effects.boolSecondaryType = secondaryKernel;
        config.effects.nestThreshold = threshold;
        config.effects.nestRepeatScale = repeatScale;
        config.effects.nestMix = mix;
        return this;
    }

    public SceneBuilder fog(float density) {
        config.effects.volumetricFogEnabled = true;
        config.effects.fogDensity = density;
        return this;
    }

    public SceneBuilder fogColor(float r, float g, float b) {
        config.effects.fogColor = new float[]{r, g, b};
        return this;
    }

    // ========================================================================
    // Lighting
    // ========================================================================

    public SceneBuilder lightDir(float x, float y, float z) {
        config.lighting.direction = new float[]{x, y, z};
        return this;
    }

    public SceneBuilder lightIntensity(float i) {
        config.lighting.intensity = i;
        return this;
    }

    public SceneBuilder lightColor(float r, float g, float b) {
        config.lighting.color = new float[]{r, g, b};
        return this;
    }

    public SceneBuilder ambientColor(float r, float g, float b) {
        config.lighting.ambientColor = new float[]{r, g, b};
        return this;
    }

    public SceneBuilder ambientIntensity(float i) {
        config.lighting.ambientIntensity = i;
        return this;
    }

    // ========================================================================
    // Material & Post-processing
    // ========================================================================

    public SceneBuilder colorStrength(float s) {
        config.material.colorStrength = s;
        return this;
    }

    public SceneBuilder paletteOffset(float offset) {
        config.material.paletteOffset = offset;
        return this;
    }

    public SceneBuilder materialType(int type) {
        config.material.type = type;
        return this;
    }

    public SceneBuilder reflectionIntensity(float r) {
        config.material.reflectionIntensity = r;
        return this;
    }

    public SceneBuilder sss(float intensity, float radius) {
        config.material.sssIntensity = intensity;
        config.material.sssRadius = radius;
        return this;
    }

    public SceneBuilder sssColor(float r, float g, float b) {
        config.material.sssColor = new float[]{r, g, b};
        return this;
    }

    public SceneBuilder mossColor(float r, float g, float b) {
        config.effects.mossColorR = r;
        config.effects.mossColorG = g;
        config.effects.mossColorB = b;
        return this;
    }

    public SceneBuilder lensDirt(float intensity) {
        config.effects.lensEffectsEnabled = true;
        config.effects.lensDirtIntensity = intensity;
        return this;
    }

    public SceneBuilder maxRaySteps(int steps) {
        config.rendering.maxRaySteps = steps;
        return this;
    }

    public SceneBuilder glowIntensity(float g) {
        config.rendering.glowIntensity = g;
        return this;
    }

    // ========================================================================
    // Fractal-specific params
    // ========================================================================

    /** Replace the palette gradient. Each stop is {position, r, g, b} in 0..1.
     *  This is what actually colours the fractal — paletteIndex does not. */
    public SceneBuilder gradient(float[]... stops) {
        List<FractalConfig.GradientStopConfig> list = new ArrayList<>();
        for (float[] st : stops) list.add(gs(st[0], st[1], st[2], st[3]));
        config.material.gradientStops = list;
        return this;
    }

    /** Deep-zoom iteration LOD: extra DE iterations per octave of camera clearance,
     *  and their ceiling. 0 = off (the default), which compiles the feature out. */
    public SceneBuilder detailLOD(float perOctave, int ceiling) {
        config.effects.detailLOD = perOctave;
        config.effects.detailLODMax = ceiling;
        return this;
    }

    /** 0=Standard 1=Bands 2=Distance 3=Angular 4=Blend 5=Contour, plus the
     *  scale-invariant modes 9-12. Angular builds its hue from an atan2 of two factors,
     *  so it traverses the whole palette even when the factor field itself is narrow. */
    /** Fresnel rim light, 0..1. At the historical 0.15 it puts white on every grazing
     *  angle, which on a fractal is most of the surface; 0 lets the palette through. */
    /** Nebula colour independent of the fractal palette. Without this the Space sky is
     *  tinted by the same gradient as the object, so the whole frame reads as one hue. */
    public SceneBuilder nebula(float r, float g, float b, float tint) {
        config.effects.nebulaColor = new float[]{r, g, b};
        config.effects.nebulaTint = tint;
        return this;
    }

    public SceneBuilder rimIntensity(float v) {
        config.rendering.rimIntensity = v;
        return this;
    }

    public SceneBuilder coloringMode(int mode) {
        config.material.coloringMode = mode;
        return this;
    }

    public SceneBuilder param(String name, Object value) {
        config.fractalParams.put(name, value);
        return this;
    }

    // ========================================================================
    // Animation
    // ========================================================================

    public SceneBuilder duration(double seconds) {
        ensureAnimation();
        config.animation.duration = seconds;
        return this;
    }

    public SceneBuilder fps(double frameRate) {
        ensureAnimation();
        config.animation.frameRate = frameRate;
        return this;
    }

    public SceneBuilder looping(boolean loop) {
        ensureAnimation();
        config.animation.looping = loop;
        return this;
    }

    /**
     * Start building an animation track.
     * Track types: "Float", "Integer", "float[]"
     */
    public TrackBuilder track(String name) {
        return track(name, "Float", 0.0);
    }

    public TrackBuilder track(String name, String valueType, Object defaultValue) {
        TrackBuilder tb = new TrackBuilder(this, name, valueType, defaultValue);
        trackBuilders.add(tb);
        return tb;
    }

    // ========================================================================
    // Meta
    // ========================================================================

    public SceneBuilder name(String name) {
        config.name = name;
        return this;
    }

    public SceneBuilder description(String desc) {
        config.description = desc;
        return this;
    }

    // ========================================================================
    // Build & Output
    // ========================================================================

    public FractalConfig build() {
        // Compile tracks into animation config
        if (!trackBuilders.isEmpty()) {
            ensureAnimation();
            config.animation.tracks = new ArrayList<>();
            for (TrackBuilder tb : trackBuilders) {
                config.animation.tracks.add(tb.buildTrack());
            }
        }
        return config;
    }

    public String toJson() {
        return FractalConfigManager.toJson(build());
    }

    public void writeTo(String path) throws IOException {
        FractalConfigManager.save(build(), new File(path));
    }

    public void writeTo(File file) throws IOException {
        FractalConfigManager.save(build(), file);
    }

    // ========================================================================
    // Internal
    // ========================================================================

    private void ensureAnimation() {
        if (config.animation == null) {
            config.animation = new FractalConfig.AnimationConfig();
            config.animation.duration = 10;
            config.animation.frameRate = 30;
        }
    }

    private static List<FractalConfig.GradientStopConfig> defaultGradient() {
        List<FractalConfig.GradientStopConfig> stops = new ArrayList<>();
        stops.add(gs(0.0f, 0, 0, 0.04f));
        stops.add(gs(0.25f, 0.35f, 0, 0.15f));
        stops.add(gs(0.5f, 0.9f, 0.3f, 0));
        stops.add(gs(0.75f, 1, 0.85f, 0.2f));
        stops.add(gs(1.0f, 1, 1, 0.95f));
        return stops;
    }

    private static FractalConfig.GradientStopConfig gs(float pos, float r, float g, float b) {
        FractalConfig.GradientStopConfig s = new FractalConfig.GradientStopConfig();
        s.position = pos; s.r = r; s.g = g; s.b = b;
        return s;
    }

    // ========================================================================
    // Track builder
    // ========================================================================

    public static class TrackBuilder {
        private final SceneBuilder parent;
        private final String name;
        private final String valueType;
        private final Object defaultValue;
        boolean spline = false;
        final List<KF> keyframes = new ArrayList<>();

        TrackBuilder(SceneBuilder parent, String name, String valueType, Object defaultValue) {
            this.parent = parent;
            this.name = name;
            this.valueType = valueType;
            this.defaultValue = defaultValue;
        }

        /** Add a keyframe. For Float tracks, value is a Number. */
        public TrackBuilder key(double time, Object value) {
            return key(time, value, "EASE_IN_OUT_CUBIC");
        }

        public TrackBuilder key(double time, Object value, String easing) {
            keyframes.add(new KF(time, value, easing));
            return this;
        }

        /** Shortcut: linear keyframe */
        public TrackBuilder lin(double time, Object value) {
            return key(time, value, "LINEAR");
        }

        public TrackBuilder spline(boolean enabled) {
            this.spline = enabled;
            return this;
        }

        /** Finish this track and return to the scene builder. */
        public SceneBuilder done() {
            return parent;
        }

        FractalConfig.TrackConfig buildTrack() {
            FractalConfig.TrackConfig tc = new FractalConfig.TrackConfig();
            tc.name = name;
            tc.valueType = valueType;
            tc.defaultValue = defaultValue;
            tc.splineInterpolation = spline;
            tc.keyframes = new ArrayList<>();
            for (KF kf : keyframes) {
                FractalConfig.KeyframeConfig kc = new FractalConfig.KeyframeConfig();
                kc.time = kf.time;
                kc.value = kf.value;
                kc.easing = kf.easing;
                tc.keyframes.add(kc);
            }
            return tc;
        }
    }

    record KF(double time, Object value, String easing) {}
}
