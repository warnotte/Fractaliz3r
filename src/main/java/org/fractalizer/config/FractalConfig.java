package org.fractalizer.config;

import javafx.scene.paint.Color;
import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete fractal configuration for save/load.
 * Designed for clean JSON serialization with Gson.
 */
public class FractalConfig {

    // Metadata
    public String version = "1.0";
    public String timestamp;
    public String name;
    public String description;

    // Fractal type
    public String fractalType;

    // Camera
    public CameraConfig camera = new CameraConfig();

    // Common rendering parameters
    public RenderingConfig rendering = new RenderingConfig();

    // Lighting
    public LightingConfig lighting = new LightingConfig();

    // Material
    public MaterialConfig material = new MaterialConfig();

    // Effects (DoF, Path Tracing, etc.)
    public EffectsConfig effects = new EffectsConfig();

    // Fractal-specific parameters (dynamic based on type)
    public Map<String, Object> fractalParams = new HashMap<>();

    // Animation (optional - only present if animation has keyframes)
    public AnimationConfig animation;

    // ========================================================================
    // Nested configuration classes
    // ========================================================================

    public static class CameraConfig {
        public float[] position = {0, 0, -3};
        public float[] quaternion = {1, 0, 0, 0};  // w, x, y, z
        public float fov = 60;  // degrees
        public float moveSpeed = 0.1f;
    }

    public static class RenderingConfig {
        public int maxRaySteps = 200;
        public float epsilon = 0.0001f;
        public float qualityMultiplier = 1.0f;
        public int renderMode = 0;

        // Shadows
        public float shadowSoftness = 16f;
        public int shadowSteps = 128;

        // AO
        public int aoSteps = 5;
        public float aoIntensity = 0.5f;

        // Glow
        public float glowIntensity = 0.15f;

        // Specular
        public float specularIntensity = 0.5f;
        public float specularPower = 32f;
    }

    public static class LightingConfig {
        public float[] direction = {2, 3, -2};
        public float[] color = {1, 0.95f, 0.9f};
        public float intensity = 1.2f;

        public float[] ambientColor = {0.1f, 0.15f, 0.25f};
        public float ambientIntensity = 0.3f;

        // Additional light source
        public int extraType = AbstractFractalParams.EXTRA_LIGHT_OFF;
        public boolean extraAttachToCamera = true;
        public float[] extraPosition = {0f, 0f, 0f};
        public float[] extraDirection = {0f, 0f, 1f};
        public float[] extraColor = {1f, 0.95f, 0.9f};
        public float extraIntensity = 1.5f;
        public float extraRange = 2.0f;
        public float extraAreaRadius = 0.03f;
        public float extraConeAngle = 35.0f;
        public float extraConeSoftness = 0.3f;
    }

    public static class MaterialConfig {
        public float[] hue = {0, 0.33f, 0.67f};
        public int paletteIndex = 0;
        public float colorStrength = 1.0f;
        public float paletteOffset = 0.0f;
        public int trapMode = 0;
        public int type = 0;  // 0=Lambertian, 1=Metallic, 2=Glass
        public float metalness = 0.9f;
        public float ior = 1.5f;
        public List<GradientStopConfig> gradientStops;

        // Advanced Effects
        public float reflectionIntensity = 0.0f;
        public float emissiveIntensity = 0.0f;
        public float sssIntensity = 0.0f;
        public float sssRadius = 0.1f;
        public float[] sssColor = {1.0f, 0.4f, 0.2f};
    }

    public static class GradientStopConfig {
        public double position;
        public double r, g, b;

        public GradientStopConfig() {}

        public GradientStopConfig(double position, double r, double g, double b) {
            this.position = position;
            this.r = r;
            this.g = g;
            this.b = b;
        }
    }

    public static class EffectsConfig {
        // DoF
        public boolean dofEnabled = false;
        public float focalDistance = 2.5f;
        public float aperture = 0.02f;
        public int dofSamples = 16;
        public float anamorphicRatio = 1.0f;
        public int bokehBlades = 0;
        public float bokehRotation = 0.0f;
        public float opticalVignettingStrength = 0.0f;
        public boolean tiltShiftEnabled = false;
        public float tiltAngleX = 0.0f;
        public float tiltAngleY = 0.0f;
        public float dofChromaticStrength = 0.0f;

        // Path Tracing
        public boolean pathTracingEnabled = false;
        public int maxBounces = 4;
        public float roughness = 0.5f;
        public float skyIntensity = 1.0f;
        public float indirectMultiplier = 0.5f;
        
        // Procedural Sky
        public int skyType = 0;
        public float cloudDensity = 0.5f;
        public float skySpeed = 1.0f;
        public float skyTime = 0.0f;
        public float skyParallax = 0.25f;
        public int projectionMode = 0;

        // Lens Effects
        public boolean lensEffectsEnabled = false;
        public float lensDirtIntensity = 0.0f;
        public float starburstIntensity = 0.0f;

        // Volumetric Fog
        public boolean volumetricFogEnabled = false;
        public float fogDensity = 0.15f;
        public float[] fogColor = {0.5f, 0.6f, 0.7f};
        public float fogScattering = 0.5f;
        public int fogSteps = 32;

        // Motion Blur
        public float shutterAngle = 180f;

        // NEE + MIS
        public boolean neeEnabled = true;

        // Adaptive Sampling
        public boolean adaptiveSampling = false;
        public float varianceThreshold = 0.0005f;
        public int minAdaptiveSamples = 16;

        // Erosion
        public boolean erosionEnabled = false;
        public float erosionStrength = 0.5f;
        public float erosionTime = 0.0f;
        public float erosionScale = 1.0f;
        public int erosionType = 0;

        // Crystallization
        public boolean crystalEnabled = false;
        public float crystalStrength = 0.5f;
        public float crystalTime = 0.0f;
        public float crystalScale = 1.0f;
        public float crystalSharpness = 2.0f;

        // Moss/Lichen
        public boolean mossEnabled = false;
        public float mossStrength = 0.5f;
        public float mossTime = 0.0f;
        public float mossScale = 1.0f;
        public float mossColorR = 0.15f;
        public float mossColorG = 0.35f;
        public float mossColorB = 0.08f;
        public float mossNormalThreshold = 0.3f;

        // Domain Distortion
        public boolean distortionEnabled = false;
        public int distortionType = 0;
        public int distortionAxis = 1;
        public float distortionStrength = 0.0f;
        public float distortionFrequency = 1.0f;
        public float distortionOffset = 0.0f;

        // Boolean Operations
        public boolean booleanEnabled = false;
        public int booleanOp = 1;
        public String boolSecondaryType = null;
        public float boolOffsetX = 0.5f, boolOffsetY = 0f, boolOffsetZ = 0f;
        public float boolScale = 1.0f;
        public float boolBlend = 0.0f;
        public float boolRotX = 0f, boolRotY = 0f, boolRotZ = 0f;
        public float nestThreshold = 0.1f;
        public float nestRepeatScale = 5.0f;
        public float nestRotation = 0.0f;
        public float nestMix = 1.0f;
    }

    public static class AnimationConfig {
        public double duration = 10.0;
        public double frameRate = 30.0;
        public boolean looping = false;
        public List<TrackConfig> tracks = new ArrayList<>();
    }

    public static class TrackConfig {
        public String name;
        public String valueType;  // "Float", "Double", "Integer", "float[]"
        public Object defaultValue;
        public boolean splineInterpolation = false;
        public List<KeyframeConfig> keyframes = new ArrayList<>();
    }

    public static class KeyframeConfig {
        public double time;
        public Object value;
        public String easing = "LINEAR";
    }

    // ========================================================================
    // Factory methods
    // ========================================================================

    /**
     * Create a config from current parameters.
     */
    public static FractalConfig fromParams(AbstractFractalParams params) {
        FractalConfig config = new FractalConfig();
        config.timestamp = Instant.now().toString();
        config.fractalType = params.getType().name();

        // Camera
        Camera cam = params.getCamera();
        float[] pos = cam.getPosition();
        float[] quat = cam.getQuaternion();
        config.camera.position = new float[]{pos[0], pos[1], pos[2]};
        config.camera.quaternion = new float[]{quat[0], quat[1], quat[2], quat[3]};
        config.camera.fov = (float) Math.toDegrees(params.getFov());
        config.camera.moveSpeed = cam.getMoveSpeed();

        // Rendering
        config.rendering.maxRaySteps = params.getMaxRaySteps();
        config.rendering.epsilon = params.getEpsilon();
        config.rendering.qualityMultiplier = params.getQualityMultiplier();
        config.rendering.renderMode = params.getRenderMode();
        config.rendering.shadowSoftness = params.getShadowSoftness();
        config.rendering.shadowSteps = params.getShadowSteps();
        config.rendering.aoSteps = params.getAoSteps();
        config.rendering.aoIntensity = params.getAoIntensity();
        config.rendering.glowIntensity = params.getGlowIntensity();
        config.rendering.specularIntensity = params.getSpecularIntensity();
        config.rendering.specularPower = params.getSpecularPower();

        // Lighting
        config.lighting.direction = new float[]{params.getLightX(), params.getLightY(), params.getLightZ()};
        config.lighting.color = new float[]{params.getLightR(), params.getLightG(), params.getLightB()};
        config.lighting.intensity = params.getLightIntensity();
        config.lighting.ambientColor = new float[]{params.getAmbientR(), params.getAmbientG(), params.getAmbientB()};
        config.lighting.ambientIntensity = params.getAmbientIntensity();
        config.lighting.extraType = params.getExtraLightType();
        config.lighting.extraAttachToCamera = params.isExtraLightAttachToCamera();
        config.lighting.extraPosition = new float[]{params.getExtraLightX(), params.getExtraLightY(), params.getExtraLightZ()};
        config.lighting.extraDirection = new float[]{params.getExtraLightDirX(), params.getExtraLightDirY(), params.getExtraLightDirZ()};
        config.lighting.extraColor = new float[]{params.getExtraLightR(), params.getExtraLightG(), params.getExtraLightB()};
        config.lighting.extraIntensity = params.getExtraLightIntensity();
        config.lighting.extraRange = params.getExtraLightRange();
        config.lighting.extraAreaRadius = params.getExtraLightAreaRadius();
        config.lighting.extraConeAngle = params.getExtraLightConeAngle();
        config.lighting.extraConeSoftness = params.getExtraLightConeSoftness();

        // Material
        config.material.hue = new float[]{params.getHueR(), params.getHueG(), params.getHueB()};
        config.material.paletteIndex = params.getPaletteIndex();
        config.material.colorStrength = params.getColorStrength();
        config.material.paletteOffset = params.getPaletteOffset();
        config.material.trapMode = params.getTrapMode();
        config.material.type = params.getMaterialType();
        config.material.metalness = params.getMetalness();
        config.material.ior = params.getIor();

        // Advanced Effects
        config.material.reflectionIntensity = params.getReflectionIntensity();
        config.material.emissiveIntensity = params.getEmissiveIntensity();
        config.material.sssIntensity = params.getSssIntensity();
        config.material.sssRadius = params.getSssRadius();
        config.material.sssColor = params.getSssColor().clone();

        // Serialize custom gradient stops
        GradientPalette gradient = params.getCustomGradient();
        if (gradient != null) {
            config.material.gradientStops = new ArrayList<>();
            for (GradientPalette.ColorStop stop : gradient.getStops()) {
                config.material.gradientStops.add(new GradientStopConfig(
                    stop.position(), stop.color().getRed(), stop.color().getGreen(), stop.color().getBlue()
                ));
            }
        }

        // Effects
        config.effects.dofEnabled = params.isDofEnabled();
        config.effects.focalDistance = params.getFocalDistance();
        config.effects.aperture = params.getAperture();
        config.effects.dofSamples = params.getDofSamples();
        config.effects.anamorphicRatio = params.getAnamorphicRatio();
        config.effects.bokehBlades = params.getBokehBlades();
        config.effects.bokehRotation = params.getBokehRotation();
        config.effects.opticalVignettingStrength = params.getOpticalVignettingStrength();
        config.effects.tiltShiftEnabled = params.isTiltShiftEnabled();
        config.effects.tiltAngleX = params.getTiltAngleX();
        config.effects.tiltAngleY = params.getTiltAngleY();
        config.effects.dofChromaticStrength = params.getDofChromaticStrength();
        config.effects.pathTracingEnabled = params.isPathTracingEnabled();
        config.effects.maxBounces = params.getMaxBounces();
        config.effects.roughness = params.getRoughness();
        config.effects.skyIntensity = params.getSkyIntensity();
        config.effects.indirectMultiplier = params.getIndirectMultiplier();
        config.effects.skyType = params.getSkyType();
        config.effects.cloudDensity = params.getCloudDensity();
        config.effects.skySpeed = params.getSkySpeed();
        config.effects.skyTime = params.getSkyTime();
        config.effects.skyParallax = params.getSkyParallax();
        config.effects.projectionMode = params.getProjectionMode();
        config.effects.lensEffectsEnabled = params.isLensEffectsEnabled();
        config.effects.lensDirtIntensity = params.getLensDirtIntensity();
        config.effects.starburstIntensity = params.getStarburstIntensity();
        config.effects.volumetricFogEnabled = params.isVolumetricFogEnabled();
        config.effects.fogDensity = params.getFogDensity();
        config.effects.fogColor = params.getFogColor().clone();
        config.effects.fogScattering = params.getFogScattering();
        config.effects.fogSteps = params.getFogSteps();
        config.effects.shutterAngle = params.getShutterAngle();
        config.effects.neeEnabled = params.isNeeEnabled();
        config.effects.adaptiveSampling = params.isAdaptiveSampling();
        config.effects.varianceThreshold = params.getVarianceThreshold();
        config.effects.minAdaptiveSamples = params.getMinAdaptiveSamples();
        config.effects.erosionEnabled = params.isErosionEnabled();
        config.effects.erosionStrength = params.getErosionStrength();
        config.effects.erosionTime = params.getErosionTime();
        config.effects.erosionScale = params.getErosionScale();
        config.effects.erosionType = params.getErosionType();
        config.effects.crystalEnabled = params.isCrystalEnabled();
        config.effects.crystalStrength = params.getCrystalStrength();
        config.effects.crystalTime = params.getCrystalTime();
        config.effects.crystalScale = params.getCrystalScale();
        config.effects.crystalSharpness = params.getCrystalSharpness();
        config.effects.mossEnabled = params.isMossEnabled();
        config.effects.mossStrength = params.getMossStrength();
        config.effects.mossTime = params.getMossTime();
        config.effects.mossScale = params.getMossScale();
        config.effects.mossColorR = params.getMossColorR();
        config.effects.mossColorG = params.getMossColorG();
        config.effects.mossColorB = params.getMossColorB();
        config.effects.mossNormalThreshold = params.getMossNormalThreshold();
        config.effects.distortionEnabled = params.isDistortionEnabled();
        config.effects.distortionType = params.getDistortionType();
        config.effects.distortionAxis = params.getDistortionAxis();
        config.effects.distortionStrength = params.getDistortionStrength();
        config.effects.distortionFrequency = params.getDistortionFrequency();
        config.effects.distortionOffset = params.getDistortionOffset();
        config.effects.booleanEnabled = params.isBooleanEnabled();
        config.effects.booleanOp = params.getBooleanOp();
        config.effects.boolSecondaryType = params.getBoolSecondaryType();
        config.effects.boolOffsetX = params.getBoolOffsetX();
        config.effects.boolOffsetY = params.getBoolOffsetY();
        config.effects.boolOffsetZ = params.getBoolOffsetZ();
        config.effects.boolScale = params.getBoolScale();
        config.effects.boolBlend = params.getBoolBlend();
        config.effects.boolRotX = params.getBoolRotX();
        config.effects.boolRotY = params.getBoolRotY();
        config.effects.boolRotZ = params.getBoolRotZ();
        config.effects.nestThreshold = params.getNestThreshold();
        config.effects.nestRepeatScale = params.getNestRepeatScale();
        config.effects.nestRotation = params.getNestRotation();
        config.effects.nestMix = params.getNestMix();
        // Fractal-specific parameters
        extractFractalParams(params, config.fractalParams);

        return config;
    }

    /**
     * Apply this config to parameters.
     */
    public void applyTo(AbstractFractalParams params) {
        // Camera
        Camera cam = params.getCamera();
        cam.setPosition(camera.position[0], camera.position[1], camera.position[2]);
        cam.setQuaternion(camera.quaternion[0], camera.quaternion[1],
                          camera.quaternion[2], camera.quaternion[3]);
        params.setFovDegrees(camera.fov);
        cam.setMoveSpeed(camera.moveSpeed);

        // Rendering
        params.setMaxRaySteps(rendering.maxRaySteps);
        params.setEpsilon(rendering.epsilon);
        params.setQualityMultiplier(rendering.qualityMultiplier);
        params.setRenderMode(rendering.renderMode);
        params.setShadowSoftness(rendering.shadowSoftness);
        params.setShadowSteps(rendering.shadowSteps);
        params.setAoSteps(rendering.aoSteps);
        params.setAoIntensity(rendering.aoIntensity);
        params.setGlowIntensity(rendering.glowIntensity);
        params.setSpecularIntensity(rendering.specularIntensity);
        params.setSpecularPower(rendering.specularPower);

        // Lighting
        params.setLightDirection(lighting.direction[0], lighting.direction[1], lighting.direction[2]);
        params.setLightColor(lighting.color[0], lighting.color[1], lighting.color[2]);
        params.setLightIntensity(lighting.intensity);
        params.setAmbientColor(lighting.ambientColor[0], lighting.ambientColor[1], lighting.ambientColor[2]);
        params.setAmbientIntensity(lighting.ambientIntensity);
        params.setExtraLightType(lighting.extraType);
        params.setExtraLightAttachToCamera(true);
        if (lighting.extraPosition != null && lighting.extraPosition.length == 3) {
            params.setExtraLightPosition(lighting.extraPosition[0], lighting.extraPosition[1], lighting.extraPosition[2]);
        }
        if (lighting.extraDirection != null && lighting.extraDirection.length == 3) {
            params.setExtraLightDirection(lighting.extraDirection[0], lighting.extraDirection[1], lighting.extraDirection[2]);
        }
        if (lighting.extraColor != null && lighting.extraColor.length == 3) {
            params.setExtraLightColor(lighting.extraColor[0], lighting.extraColor[1], lighting.extraColor[2]);
        }
        params.setExtraLightIntensity(lighting.extraIntensity);
        params.setExtraLightRange(lighting.extraRange);
        params.setExtraLightAreaRadius(lighting.extraAreaRadius);
        params.setExtraLightConeAngle(lighting.extraConeAngle);
        params.setExtraLightConeSoftness(lighting.extraConeSoftness);

        // Material
        params.setMaterialHue(material.hue[0], material.hue[1], material.hue[2]);
        params.setPaletteIndex(material.paletteIndex);
        params.setColorStrength(material.colorStrength);
        params.setPaletteOffset(material.paletteOffset);
        params.setTrapMode(material.trapMode);
        params.setMaterialType(material.type);
        params.setMetalness(material.metalness);
        params.setIor(material.ior);

        // Advanced Effects
        params.setReflectionIntensity(material.reflectionIntensity);
        params.setEmissiveIntensity(material.emissiveIntensity);
        params.setSssIntensity(material.sssIntensity);
        params.setSssRadius(material.sssRadius);
        if (material.sssColor != null && material.sssColor.length == 3) {
            params.setSssColor(material.sssColor[0], material.sssColor[1], material.sssColor[2]);
        }

        // Deserialize custom gradient stops
        if (material.gradientStops != null && material.gradientStops.size() >= 2) {
            List<GradientPalette.ColorStop> stops = new ArrayList<>();
            for (GradientStopConfig sc : material.gradientStops) {
                stops.add(new GradientPalette.ColorStop(sc.position, Color.color(
                    Math.max(0, Math.min(1, sc.r)),
                    Math.max(0, Math.min(1, sc.g)),
                    Math.max(0, Math.min(1, sc.b))
                )));
            }
            params.setCustomGradient(new GradientPalette(stops));
        }

        // Effects
        params.setDofEnabled(effects.dofEnabled);
        params.setFocalDistance(effects.focalDistance);
        params.setAperture(effects.aperture);
        params.setDofSamples(effects.dofSamples);
        params.setAnamorphicRatio(effects.anamorphicRatio);
        params.setBokehBlades(effects.bokehBlades);
        params.setBokehRotation(effects.bokehRotation);
        params.setOpticalVignettingStrength(effects.opticalVignettingStrength);
        params.setTiltShiftEnabled(effects.tiltShiftEnabled);
        params.setTiltAngleX(effects.tiltAngleX);
        params.setTiltAngleY(effects.tiltAngleY);
        params.setDofChromaticStrength(effects.dofChromaticStrength);
        params.setPathTracingEnabled(effects.pathTracingEnabled);
        params.setMaxBounces(effects.maxBounces);
        params.setRoughness(effects.roughness);
        params.setSkyIntensity(effects.skyIntensity);
        params.setIndirectMultiplier(effects.indirectMultiplier);
        params.setSkyType(effects.skyType);
        params.setCloudDensity(effects.cloudDensity);
        params.setSkySpeed(effects.skySpeed);
        params.setSkyTime(effects.skyTime);
        params.setSkyParallax(effects.skyParallax);
        params.setProjectionMode(effects.projectionMode);
        params.setLensEffectsEnabled(effects.lensEffectsEnabled);
        params.setLensDirtIntensity(effects.lensDirtIntensity);
        params.setStarburstIntensity(effects.starburstIntensity);
        params.setVolumetricFogEnabled(effects.volumetricFogEnabled);
        params.setFogDensity(effects.fogDensity);
        if (effects.fogColor != null && effects.fogColor.length == 3) {
            params.setFogColor(effects.fogColor[0], effects.fogColor[1], effects.fogColor[2]);
        }
        params.setFogScattering(effects.fogScattering);
        params.setFogSteps(effects.fogSteps);
        params.setShutterAngle(effects.shutterAngle);
        params.setNeeEnabled(effects.neeEnabled);
        params.setAdaptiveSampling(effects.adaptiveSampling);
        params.setVarianceThreshold(effects.varianceThreshold);
        params.setMinAdaptiveSamples(effects.minAdaptiveSamples);
        params.setErosionEnabled(effects.erosionEnabled);
        params.setErosionStrength(effects.erosionStrength);
        params.setErosionTime(effects.erosionTime);
        params.setErosionScale(effects.erosionScale);
        params.setErosionType(effects.erosionType);
        params.setCrystalEnabled(effects.crystalEnabled);
        params.setCrystalStrength(effects.crystalStrength);
        params.setCrystalTime(effects.crystalTime);
        params.setCrystalScale(effects.crystalScale);
        params.setCrystalSharpness(effects.crystalSharpness);
        params.setMossEnabled(effects.mossEnabled);
        params.setMossStrength(effects.mossStrength);
        params.setMossTime(effects.mossTime);
        params.setMossScale(effects.mossScale);
        params.setMossColor(effects.mossColorR, effects.mossColorG, effects.mossColorB);
        params.setMossNormalThreshold(effects.mossNormalThreshold);
        params.setDistortionEnabled(effects.distortionEnabled);
        params.setDistortionType(effects.distortionType);
        params.setDistortionAxis(effects.distortionAxis);
        params.setDistortionStrength(effects.distortionStrength);
        params.setDistortionFrequency(effects.distortionFrequency);
        params.setDistortionOffset(effects.distortionOffset);
        params.setBooleanEnabled(effects.booleanEnabled);
        params.setBooleanOp(effects.booleanOp);
        params.setBoolSecondaryType(effects.boolSecondaryType);
        params.setBoolOffsetX(effects.boolOffsetX);
        params.setBoolOffsetY(effects.boolOffsetY);
        params.setBoolOffsetZ(effects.boolOffsetZ);
        params.setBoolScale(effects.boolScale);
        params.setBoolBlend(effects.boolBlend);
        params.setBoolRotX(effects.boolRotX);
        params.setBoolRotY(effects.boolRotY);
        params.setBoolRotZ(effects.boolRotZ);
        params.setNestThreshold(effects.nestThreshold);
        params.setNestRepeatScale(effects.nestRepeatScale);
        params.setNestRotation(effects.nestRotation);
        params.setNestMix(effects.nestMix);
        // Fractal-specific parameters
        applyFractalParams(params, fractalParams);
    }

    /**
     * Get the fractal type enum from this config.
     */
    public FractalType getFractalTypeEnum() {
        try {
            return FractalType.valueOf(fractalType);
        } catch (IllegalArgumentException e) {
            return FractalType.MANDELBULB;  // Default fallback
        }
    }

    // ========================================================================
    // Helper methods for fractal-specific params
    // ========================================================================

    private static void extractFractalParams(AbstractFractalParams params, Map<String, Object> map) {
        if (params instanceof MandelbulbParams mb) {
            map.put("power", mb.getPower());
            map.put("maxIterations", mb.getMaxIterations());
            map.put("bailout", mb.getBailout());
        } else if (params instanceof MandelboxParams mbx) {
            map.put("scale", mbx.getScale());
            map.put("minRadius", mbx.getMinRadius());
            map.put("fixedRadius", mbx.getFixedRadius());
            map.put("foldingLimit", mbx.getFoldingLimit());
            map.put("maxIterations", mbx.getMaxIterations());
        } else if (params instanceof MengerSpongeParams ms) {
            map.put("maxIterations", ms.getMaxIterations());
            map.put("scale", ms.getScale());
            map.put("offsetX", ms.getOffsetX());
            map.put("offsetY", ms.getOffsetY());
            map.put("offsetZ", ms.getOffsetZ());
        } else if (params instanceof KaleidoscopicIFSParams k) {
            map.put("maxIterations", k.getMaxIterations());
            map.put("scale", k.getScale());
            map.put("foldAngleX", k.getFoldAngleX());
            map.put("foldAngleY", k.getFoldAngleY());
            map.put("offsetX", k.getOffsetX());
            map.put("offsetY", k.getOffsetY());
            map.put("offsetZ", k.getOffsetZ());
        } else if (params instanceof PolyhedralIFSParams p) {
            map.put("polyType", p.getPolyType().name());
            map.put("maxIterations", p.getMaxIterations());
            map.put("scale", p.getScale());
            map.put("offsetX", p.getOffsetX());
            map.put("offsetY", p.getOffsetY());
            map.put("offsetZ", p.getOffsetZ());
            map.put("shiftX", p.getShiftX());
            map.put("shiftY", p.getShiftY());
            map.put("shiftZ", p.getShiftZ());
            map.put("rot1X", p.getRot1X());
            map.put("rot1Y", p.getRot1Y());
            map.put("rot1Z", p.getRot1Z());
            map.put("rot2X", p.getRot2X());
            map.put("rot2Y", p.getRot2Y());
            map.put("rot2Z", p.getRot2Z());
        } else if (params instanceof SierpinskiParams si) {
            map.put("maxIterations", si.getMaxIterations());
            map.put("scale", si.getScale());
        } else if (params instanceof PseudoKleinianParams pk) {
            map.put("maxIterations", pk.getMaxIterations());
            map.put("cSizeX", pk.getCSizeX());
            map.put("cSizeY", pk.getCSizeY());
            map.put("cSizeZ", pk.getCSizeZ());
            map.put("size", pk.getSize());
            map.put("deOffset", pk.getDEOffset());
            map.put("foldCx", pk.getFoldCx());
            map.put("foldCy", pk.getFoldCy());
            map.put("foldCz", pk.getFoldCz());
        } else if (params instanceof ApollonianParams ap) {
            map.put("maxIterations", ap.getMaxIterations());
            map.put("scale", ap.getScale());
            map.put("foldRadius", ap.getFoldRadius());
        } else if (params instanceof BristorbrotParams br) {
            map.put("maxIterations", br.getMaxIterations());
            map.put("bailout", br.getBailout());
            map.put("juliaCx", br.getJuliaCx());
            map.put("juliaCy", br.getJuliaCy());
            map.put("juliaCz", br.getJuliaCz());
        } else if (params instanceof QuaternionJulia4DParams qj) {
            map.put("maxIterations", qj.getMaxIterations());
            map.put("bailout", qj.getBailout());
            map.put("juliaCx", qj.getJuliaCx());
            map.put("juliaCy", qj.getJuliaCy());
            map.put("juliaCz", qj.getJuliaCz());
            map.put("juliaCw", qj.getJuliaCw());
            map.put("sliceW", qj.getSliceW());
            map.put("rotXW", qj.getRotXW());
            map.put("rotYW", qj.getRotYW());
            map.put("rotZW", qj.getRotZW());
        } else if (params instanceof FractalTerrainParams ft) {
            map.put("terrainHeight", ft.getTerrainHeight());
            map.put("terrainFrequency", ft.getTerrainFrequency());
            map.put("octaves", ft.getOctaves());
            map.put("lacunarity", ft.getLacunarity());
            map.put("roughness", ft.getRoughness());
            map.put("warpStrength", ft.getWarpStrength());
            map.put("ridgeSharpness", ft.getRidgeSharpness());
            map.put("terrainOffset", ft.getTerrainOffset());
        } else if (params instanceof CustomShaderParams csp) {
            map.put("shaderSource", csp.getShaderSource());
            if (!csp.getUniformValues().isEmpty()) {
                map.put("uniformValues", new java.util.LinkedHashMap<>(csp.getUniformValues()));
            }
        } else if (params instanceof TestSceneParams ts) {
            map.put("sceneScale", ts.getSceneScale());
        } else if (params instanceof CornellBoxParams cb) {
            map.put("sceneScale", cb.getSceneScale());
            map.put("glassSphereX", cb.getGlassSphereX());
            map.put("glassSphereY", cb.getGlassSphereY());
            map.put("glassSphereZ", cb.getGlassSphereZ());
            map.put("glassSphereRadius", cb.getGlassSphereRadius());
            map.put("metalSphereX", cb.getMetalSphereX());
            map.put("metalSphereY", cb.getMetalSphereY());
            map.put("metalSphereZ", cb.getMetalSphereZ());
            map.put("metalSphereRadius", cb.getMetalSphereRadius());
            map.put("lightPanelX", cb.getLightPanelX());
            map.put("lightPanelY", cb.getLightPanelY());
            map.put("lightPanelZ", cb.getLightPanelZ());
            map.put("lightPanelW", cb.getLightPanelW());
            map.put("lightPanelD", cb.getLightPanelD());
        }
    }

    private static void applyFractalParams(AbstractFractalParams params, Map<String, Object> map) {
        if (params instanceof MandelbulbParams mb) {
            if (map.containsKey("power")) mb.power(getFloat(map, "power"));
            if (map.containsKey("maxIterations")) mb.iterations(getInt(map, "maxIterations"));
            if (map.containsKey("bailout")) mb.setBailout(getFloat(map, "bailout"));
        } else if (params instanceof MandelboxParams mbx) {
            if (map.containsKey("scale")) mbx.setScale(getFloat(map, "scale"));
            if (map.containsKey("minRadius")) mbx.setMinRadius(getFloat(map, "minRadius"));
            if (map.containsKey("fixedRadius")) mbx.setFixedRadius(getFloat(map, "fixedRadius"));
            if (map.containsKey("foldingLimit")) mbx.setFoldingLimit(getFloat(map, "foldingLimit"));
            if (map.containsKey("maxIterations")) mbx.setMaxIterations(getInt(map, "maxIterations"));
        } else if (params instanceof MengerSpongeParams ms) {
            if (map.containsKey("maxIterations")) ms.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("scale")) ms.setScale(getFloat(map, "scale"));
            if (map.containsKey("offsetX") || map.containsKey("offsetY") || map.containsKey("offsetZ")) {
                ms.setOffset(getFloat(map, "offsetX"), getFloat(map, "offsetY"), getFloat(map, "offsetZ"));
            }
        } else if (params instanceof KaleidoscopicIFSParams k) {
            if (map.containsKey("maxIterations")) k.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("scale")) k.setScale(getFloat(map, "scale"));
            if (map.containsKey("foldAngleX")) k.setFoldAngleX(getFloat(map, "foldAngleX"));
            if (map.containsKey("foldAngleY")) k.setFoldAngleY(getFloat(map, "foldAngleY"));
            if (map.containsKey("offsetX")) k.setOffset(getFloat(map, "offsetX"),
                                                        getFloat(map, "offsetY"),
                                                        getFloat(map, "offsetZ"));
        } else if (params instanceof PolyhedralIFSParams p) {
            if (map.containsKey("polyType")) p.setPolyType(PolyhedralIFSParams.PolyType.valueOf((String)map.get("polyType")));
            if (map.containsKey("maxIterations")) p.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("scale")) p.setScale(getFloat(map, "scale"));
            if (map.containsKey("offsetX")) p.setOffsetX(getFloat(map, "offsetX"));
            if (map.containsKey("offsetY")) p.setOffsetY(getFloat(map, "offsetY"));
            if (map.containsKey("offsetZ")) p.setOffsetZ(getFloat(map, "offsetZ"));
            if (map.containsKey("shiftX")) p.setShiftX(getFloat(map, "shiftX"));
            if (map.containsKey("shiftY")) p.setShiftY(getFloat(map, "shiftY"));
            if (map.containsKey("shiftZ")) p.setShiftZ(getFloat(map, "shiftZ"));
            if (map.containsKey("rot1X")) p.setRot1X(getFloat(map, "rot1X"));
            if (map.containsKey("rot1Y")) p.setRot1Y(getFloat(map, "rot1Y"));
            if (map.containsKey("rot1Z")) p.setRot1Z(getFloat(map, "rot1Z"));
            if (map.containsKey("rot2X")) p.setRot2X(getFloat(map, "rot2X"));
            if (map.containsKey("rot2Y")) p.setRot2Y(getFloat(map, "rot2Y"));
            if (map.containsKey("rot2Z")) p.setRot2Z(getFloat(map, "rot2Z"));
        } else if (params instanceof SierpinskiParams si) {
            if (map.containsKey("maxIterations")) si.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("scale")) si.setScale(getFloat(map, "scale"));
        } else if (params instanceof PseudoKleinianParams pk) {
            if (map.containsKey("maxIterations")) pk.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("cSizeX")) pk.setCSizeX(getFloat(map, "cSizeX"));
            if (map.containsKey("cSizeY")) pk.setCSizeY(getFloat(map, "cSizeY"));
            if (map.containsKey("cSizeZ")) pk.setCSizeZ(getFloat(map, "cSizeZ"));
            if (map.containsKey("size")) pk.setSize(getFloat(map, "size"));
            if (map.containsKey("deOffset")) pk.setDEOffset(getFloat(map, "deOffset"));
            if (map.containsKey("foldCx")) pk.setFoldCx(getFloat(map, "foldCx"));
            if (map.containsKey("foldCy")) pk.setFoldCy(getFloat(map, "foldCy"));
            if (map.containsKey("foldCz")) pk.setFoldCz(getFloat(map, "foldCz"));
        } else if (params instanceof ApollonianParams ap) {
            if (map.containsKey("maxIterations")) ap.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("scale")) ap.setScale(getFloat(map, "scale"));
            if (map.containsKey("foldRadius")) ap.setFoldRadius(getFloat(map, "foldRadius"));
        } else if (params instanceof BristorbrotParams br) {
            if (map.containsKey("maxIterations")) br.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("bailout")) br.setBailout(getFloat(map, "bailout"));
            if (map.containsKey("juliaCx")) br.setJuliaCx(getFloat(map, "juliaCx"));
            if (map.containsKey("juliaCy")) br.setJuliaCy(getFloat(map, "juliaCy"));
            if (map.containsKey("juliaCz")) br.setJuliaCz(getFloat(map, "juliaCz"));
        } else if (params instanceof QuaternionJulia4DParams qj) {
            if (map.containsKey("maxIterations")) qj.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("bailout")) qj.setBailout(getFloat(map, "bailout"));
            if (map.containsKey("juliaCx")) qj.setJuliaCx(getFloat(map, "juliaCx"));
            if (map.containsKey("juliaCy")) qj.setJuliaCy(getFloat(map, "juliaCy"));
            if (map.containsKey("juliaCz")) qj.setJuliaCz(getFloat(map, "juliaCz"));
            if (map.containsKey("juliaCw")) qj.setJuliaCw(getFloat(map, "juliaCw"));
            if (map.containsKey("sliceW")) qj.setSliceW(getFloat(map, "sliceW"));
            if (map.containsKey("rotXW")) qj.setRotXW(getFloat(map, "rotXW"));
            if (map.containsKey("rotYW")) qj.setRotYW(getFloat(map, "rotYW"));
            if (map.containsKey("rotZW")) qj.setRotZW(getFloat(map, "rotZW"));
        } else if (params instanceof FractalTerrainParams ft) {
            if (map.containsKey("terrainHeight")) ft.setTerrainHeight(getFloat(map, "terrainHeight"));
            if (map.containsKey("terrainFrequency")) ft.setTerrainFrequency(getFloat(map, "terrainFrequency"));
            if (map.containsKey("octaves")) ft.setOctaves(getInt(map, "octaves"));
            if (map.containsKey("lacunarity")) ft.setLacunarity(getFloat(map, "lacunarity"));
            if (map.containsKey("roughness")) ft.setRoughness(getFloat(map, "roughness"));
            if (map.containsKey("warpStrength")) ft.setWarpStrength(getFloat(map, "warpStrength"));
            if (map.containsKey("ridgeSharpness")) ft.setRidgeSharpness(getFloat(map, "ridgeSharpness"));
            if (map.containsKey("terrainOffset")) ft.setTerrainOffset(getFloat(map, "terrainOffset"));
        } else if (params instanceof CustomShaderParams csp) {
            if (map.containsKey("shaderSource")) csp.setShaderSource((String) map.get("shaderSource"));
            if (map.containsKey("uniformValues") && map.get("uniformValues") instanceof Map<?,?> rawMap) {
                csp.clearUniformValues();
                for (var entry : rawMap.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    Object val = entry.getValue();
                    // Gson deserializes float[] as List<Double> — convert back
                    if (val instanceof List<?> list) {
                        float[] arr = new float[list.size()];
                        for (int i = 0; i < list.size(); i++) {
                            arr[i] = ((Number) list.get(i)).floatValue();
                        }
                        csp.setUniformValue(key, arr);
                    } else if (val instanceof Number n) {
                        // Gson deserializes all numbers as Double — keep as Number,
                        // rebuildSliders() will convert to int/float based on @param type
                        csp.setUniformValue(key, n);
                    }
                }
            }
        } else if (params instanceof TestSceneParams ts) {
            if (map.containsKey("sceneScale")) ts.setSceneScale(getFloat(map, "sceneScale"));
        } else if (params instanceof CornellBoxParams cb) {
            if (map.containsKey("sceneScale")) cb.setSceneScale(getFloat(map, "sceneScale"));
            if (map.containsKey("glassSphereX")) cb.setGlassSphereX(getFloat(map, "glassSphereX"));
            if (map.containsKey("glassSphereY")) cb.setGlassSphereY(getFloat(map, "glassSphereY"));
            if (map.containsKey("glassSphereZ")) cb.setGlassSphereZ(getFloat(map, "glassSphereZ"));
            if (map.containsKey("glassSphereRadius")) cb.setGlassSphereRadius(getFloat(map, "glassSphereRadius"));
            if (map.containsKey("metalSphereX")) cb.setMetalSphereX(getFloat(map, "metalSphereX"));
            if (map.containsKey("metalSphereY")) cb.setMetalSphereY(getFloat(map, "metalSphereY"));
            if (map.containsKey("metalSphereZ")) cb.setMetalSphereZ(getFloat(map, "metalSphereZ"));
            if (map.containsKey("metalSphereRadius")) cb.setMetalSphereRadius(getFloat(map, "metalSphereRadius"));
            if (map.containsKey("lightPanelX")) cb.setLightPanelX(getFloat(map, "lightPanelX"));
            if (map.containsKey("lightPanelY")) cb.setLightPanelY(getFloat(map, "lightPanelY"));
            if (map.containsKey("lightPanelZ")) cb.setLightPanelZ(getFloat(map, "lightPanelZ"));
            if (map.containsKey("lightPanelW")) cb.setLightPanelW(getFloat(map, "lightPanelW"));
            if (map.containsKey("lightPanelD")) cb.setLightPanelD(getFloat(map, "lightPanelD"));
        }
    }

    private static float getFloat(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.floatValue();
        return 0f;
    }

    private static int getInt(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return 0;
    }
}
