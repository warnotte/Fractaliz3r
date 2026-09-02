package org.fractalizer.config;

import javafx.scene.paint.Color;
import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.*;
import org.fractalizer.graph.*;

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
        public float previewScale = 0.5f;
        public boolean previewFastShading = true;
        public int renderMode = 0;

        // Shadows
        public float shadowSoftness = 16f;
        public int shadowSteps = 128;

        // AO
        public int aoSteps = 5;
        public float aoIntensity = 0.5f;

        // Glow
        public float glowIntensity = 0.15f;
        public float rimIntensity = 0.15f;

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
        public int coloringMode = 0;
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
        public float[] nebulaColor = {0.25f, 0.35f, 0.75f};
        public float nebulaTint = 0f;
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

        // Raymarcher improvements
        public boolean coneTracingEnabled = true;
        public float detailLOD = 0.0f;
        public int detailLODMax = 24;
        public float fudgeFactor = 1.0f;
        public int refinementSteps = 4;
        public float stepRelaxation = 0.0f;

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

        // Ocean & Floor
        public boolean oceanEnabled = false;
        public float oceanHeight = -1.0f;
        public float[] oceanColor = {0.05f, 0.15f, 0.3f};
        public float oceanWaveScale = 2.0f;
        public float oceanWaveHeight = 0.1f;
        public float oceanSpeed = 1.0f;
        public float oceanTime = 0.0f;
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
        config.rendering.previewScale = params.getPreviewScale();
        config.rendering.previewFastShading = params.isPreviewFastShading();
        config.rendering.renderMode = params.getRenderMode();
        config.rendering.shadowSoftness = params.getShadowSoftness();
        config.rendering.shadowSteps = params.getShadowSteps();
        config.rendering.aoSteps = params.getAoSteps();
        config.rendering.aoIntensity = params.getAoIntensity();
        config.rendering.glowIntensity = params.getGlowIntensity();
        config.rendering.rimIntensity = params.getRimIntensity();
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
        config.material.coloringMode = params.getColoringMode();
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
        config.effects.nebulaColor = params.getNebulaColor();
        config.effects.nebulaTint = params.getNebulaTint();
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
        config.effects.coneTracingEnabled = params.isConeTracingEnabled();
        config.effects.detailLOD = params.getDetailLOD();
        config.effects.detailLODMax = params.getDetailLODMax();
        config.effects.fudgeFactor = params.getFudgeFactor();
        config.effects.refinementSteps = params.getRefinementSteps();
        config.effects.stepRelaxation = params.getStepRelaxation();
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

        // Ocean & Floor
        config.effects.oceanEnabled = params.isOceanEnabled();
        config.effects.oceanHeight = params.getOceanHeight();
        config.effects.oceanColor = new float[]{params.getOceanColorR(), params.getOceanColorG(), params.getOceanColorB()};
        config.effects.oceanWaveScale = params.getOceanWaveScale();
        config.effects.oceanWaveHeight = params.getOceanWaveHeight();
        config.effects.oceanSpeed = params.getOceanSpeed();
        config.effects.oceanTime = params.getOceanTime();

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
        params.setPreviewScale(rendering.previewScale);
        params.setPreviewFastShading(rendering.previewFastShading);
        params.setRenderMode(rendering.renderMode);
        params.setShadowSoftness(rendering.shadowSoftness);
        params.setShadowSteps(rendering.shadowSteps);
        params.setAoSteps(rendering.aoSteps);
        params.setAoIntensity(rendering.aoIntensity);
        params.setGlowIntensity(rendering.glowIntensity);
        params.setRimIntensity(rendering.rimIntensity);
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
        params.setColoringMode(material.coloringMode);
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
        if (effects.nebulaColor != null && effects.nebulaColor.length == 3) {
            params.setNebulaColor(effects.nebulaColor[0], effects.nebulaColor[1], effects.nebulaColor[2]);
        }
        params.setNebulaTint(effects.nebulaTint);
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
        params.setConeTracingEnabled(effects.coneTracingEnabled);
        params.setDetailLOD(effects.detailLOD);
        params.setDetailLODMax(effects.detailLODMax);
        params.setFudgeFactor(effects.fudgeFactor);
        params.setRefinementSteps(effects.refinementSteps);
        params.setStepRelaxation(effects.stepRelaxation);
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

        // Ocean & Floor
        params.setOceanEnabled(effects.oceanEnabled);
        params.setOceanHeight(effects.oceanHeight);
        if (effects.oceanColor != null && effects.oceanColor.length == 3) {
            params.setOceanColor(effects.oceanColor[0], effects.oceanColor[1], effects.oceanColor[2]);
        }
        params.setOceanWaveScale(effects.oceanWaveScale);
        params.setOceanWaveHeight(effects.oceanWaveHeight);
        params.setOceanSpeed(effects.oceanSpeed);
        params.setOceanTime(effects.oceanTime);

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
            map.put("radiolaria", mb.getRadiolaria());
            map.put("radiolariaFactor", mb.getRadiolariaFactor());
            map.put("juliaCx", mb.getJuliaCx());
            map.put("juliaCy", mb.getJuliaCy());
            map.put("juliaCz", mb.getJuliaCz());
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
            map.put("rotAngle", ms.getRotAngle());
        } else if (params instanceof KaleidoscopicIFSParams k) {
            map.put("maxIterations", k.getMaxIterations());
            map.put("scale", k.getScale());
            map.put("foldAngleX", k.getFoldAngleX());
            map.put("foldAngleY", k.getFoldAngleY());
            map.put("offsetX", k.getOffsetX());
            map.put("offsetY", k.getOffsetY());
            map.put("offsetZ", k.getOffsetZ());
            map.put("basePrimitive", k.getBasePrimitive().name());
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
            map.put("basePrimitive", p.getBasePrimitive().name());
        } else if (params instanceof SierpinskiParams si) {
            map.put("maxIterations", si.getMaxIterations());
            map.put("scale", si.getScale());
            map.put("basePrimitive", si.getBasePrimitive().name());
        } else if (params instanceof SphereflakeParams sf) {
            map.put("maxIterations", sf.getMaxIterations());
            map.put("childScale", sf.getChildScale());
            map.put("spacing", sf.getSpacing());
            map.put("rotAngleX", sf.getRotAngleX());
            map.put("rotAngleY", sf.getRotAngleY());
            map.put("rotAngleZ", sf.getRotAngleZ());
            map.put("offsetY", sf.getOffsetY());
            map.put("offsetZ", sf.getOffsetZ());
            map.put("basePrimitive", sf.getBasePrimitive().name());
        } else if (params instanceof KochSurfaceParams ks) {
            map.put("maxIterations", ks.getMaxIterations());
            map.put("scale", ks.getScale());
            map.put("basePrimitive", ks.getBasePrimitive().name());
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
            map.put("basePrimitive", ap.getBasePrimitive().name());
        } else if (params instanceof BristorbrotParams br) {
            map.put("maxIterations", br.getMaxIterations());
            map.put("bailout", br.getBailout());
            map.put("juliaCx", br.getJuliaCx());
            map.put("juliaCy", br.getJuliaCy());
            map.put("juliaCz", br.getJuliaCz());
        } else if (params instanceof MandelorusParams ml) {
            map.put("maxIterations", ml.getMaxIterations());
            map.put("bailout", ml.getBailout());
            map.put("ringRadius", ml.getRingRadius());
            map.put("torusTwist", ml.getTorusTwist());
            map.put("power", ml.getPower());
            map.put("ringPhase", ml.getRingPhase());
            map.put("crossPhase", ml.getCrossPhase());
            map.put("vertScale", ml.getVertScale());
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
        } else if (params instanceof MengerAdvancedParams ma) {
            map.put("maxIterations", ma.getMaxIterations());
            map.put("scale", ma.getScale());
            map.put("offset", ma.getOffset());
            map.put("rotX", ma.getRotX());
            map.put("rotZ", ma.getRotZ());
            map.put("innerFold", ma.getInnerFold());
            map.put("zScale", ma.getZScale());
        } else if (params instanceof MengerSpongeTestParams mst) {
            map.put("maxIterations", mst.getMaxIterations());
            map.put("scale", mst.getScale());
            map.put("offset", mst.getOffset());
            map.put("rotX", mst.getRotX());
            map.put("rotZ", mst.getRotZ());
            map.put("zShift", mst.getZShift());
            map.put("centerZ", mst.getCenterZ());
        } else if (params instanceof NodeGraphParams ngp) {
            if (ngp.getGraphRoot() != null) {
                map.put("graph", serializeGraphNode(ngp.getGraphRoot()));
            }
        } else if (params instanceof CustomShaderParams csp) {
            map.put("shaderSource", csp.getShaderSource());
            if (!csp.getUniformValues().isEmpty()) {
                map.put("uniformValues", new java.util.LinkedHashMap<>(csp.getUniformValues()));
            }
        }
    }

    private static void applyFractalParams(AbstractFractalParams params, Map<String, Object> map) {
        if (params instanceof MandelbulbParams mb) {
            if (map.containsKey("power")) mb.power(getFloat(map, "power"));
            if (map.containsKey("maxIterations")) mb.iterations(getInt(map, "maxIterations"));
            if (map.containsKey("bailout")) mb.setBailout(getFloat(map, "bailout"));
            if (map.containsKey("radiolaria")) mb.setRadiolaria(getFloat(map, "radiolaria"));
            if (map.containsKey("radiolariaFactor")) mb.setRadiolariaFactor(getFloat(map, "radiolariaFactor"));
            if (map.containsKey("juliaCx")) mb.setJuliaCx(getFloat(map, "juliaCx"));
            if (map.containsKey("juliaCy")) mb.setJuliaCy(getFloat(map, "juliaCy"));
            if (map.containsKey("juliaCz")) mb.setJuliaCz(getFloat(map, "juliaCz"));
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
            if (map.containsKey("rotAngle")) ms.setRotAngle(getFloat(map, "rotAngle"));
        } else if (params instanceof KaleidoscopicIFSParams k) {
            if (map.containsKey("maxIterations")) k.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("scale")) k.setScale(getFloat(map, "scale"));
            if (map.containsKey("foldAngleX")) k.setFoldAngleX(getFloat(map, "foldAngleX"));
            if (map.containsKey("foldAngleY")) k.setFoldAngleY(getFloat(map, "foldAngleY"));
            if (map.containsKey("offsetX")) k.setOffset(getFloat(map, "offsetX"),
                                                        getFloat(map, "offsetY"),
                                                        getFloat(map, "offsetZ"));
            if (map.containsKey("basePrimitive"))
                k.setBasePrimitive(AbstractFractalParams.BasePrimitive.valueOf((String) map.get("basePrimitive")));
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
            if (map.containsKey("basePrimitive"))
                p.setBasePrimitive(AbstractFractalParams.BasePrimitive.valueOf((String) map.get("basePrimitive")));
        } else if (params instanceof SierpinskiParams si) {
            if (map.containsKey("maxIterations")) si.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("scale")) si.setScale(getFloat(map, "scale"));
            if (map.containsKey("basePrimitive"))
                si.setBasePrimitive(AbstractFractalParams.BasePrimitive.valueOf((String) map.get("basePrimitive")));
        } else if (params instanceof SphereflakeParams sf) {
            if (map.containsKey("maxIterations")) sf.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("childScale")) sf.setChildScale(getFloat(map, "childScale"));
            if (map.containsKey("spacing")) sf.setSpacing(getFloat(map, "spacing"));
            if (map.containsKey("rotAngleX")) sf.setRotAngleX(getFloat(map, "rotAngleX"));
            if (map.containsKey("rotAngleY")) sf.setRotAngleY(getFloat(map, "rotAngleY"));
            if (map.containsKey("rotAngleZ")) sf.setRotAngleZ(getFloat(map, "rotAngleZ"));
            if (map.containsKey("rotAngle")) sf.setRotAngleX(getFloat(map, "rotAngle")); // legacy compat
            if (map.containsKey("offsetY")) sf.setOffsetY(getFloat(map, "offsetY"));
            if (map.containsKey("offsetZ")) sf.setOffsetZ(getFloat(map, "offsetZ"));
            if (map.containsKey("basePrimitive"))
                sf.setBasePrimitive(AbstractFractalParams.BasePrimitive.valueOf((String) map.get("basePrimitive")));
        } else if (params instanceof KochSurfaceParams ks) {
            if (map.containsKey("maxIterations")) ks.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("scale")) ks.setScale(getFloat(map, "scale"));
            if (map.containsKey("basePrimitive"))
                ks.setBasePrimitive(AbstractFractalParams.BasePrimitive.valueOf((String) map.get("basePrimitive")));
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
            if (map.containsKey("basePrimitive"))
                ap.setBasePrimitive(AbstractFractalParams.BasePrimitive.valueOf((String) map.get("basePrimitive")));
        } else if (params instanceof BristorbrotParams br) {
            if (map.containsKey("maxIterations")) br.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("bailout")) br.setBailout(getFloat(map, "bailout"));
            if (map.containsKey("juliaCx")) br.setJuliaCx(getFloat(map, "juliaCx"));
            if (map.containsKey("juliaCy")) br.setJuliaCy(getFloat(map, "juliaCy"));
            if (map.containsKey("juliaCz")) br.setJuliaCz(getFloat(map, "juliaCz"));
        } else if (params instanceof MandelorusParams ml) {
            if (map.containsKey("maxIterations")) ml.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("bailout")) ml.setBailout(getFloat(map, "bailout"));
            if (map.containsKey("ringRadius")) ml.setRingRadius(getFloat(map, "ringRadius"));
            if (map.containsKey("torusTwist")) ml.setTorusTwist(getFloat(map, "torusTwist"));
            if (map.containsKey("power")) ml.setPower(getFloat(map, "power"));
            if (map.containsKey("ringPhase")) ml.setRingPhase(getFloat(map, "ringPhase"));
            if (map.containsKey("crossPhase")) ml.setCrossPhase(getFloat(map, "crossPhase"));
            if (map.containsKey("vertScale")) ml.setVertScale(getFloat(map, "vertScale"));
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
        } else if (params instanceof MengerAdvancedParams ma) {
            if (map.containsKey("maxIterations")) ma.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("scale")) ma.setScale(getFloat(map, "scale"));
            if (map.containsKey("offset")) ma.setOffset(getFloat(map, "offset"));
            if (map.containsKey("rotX")) ma.setRotX(getFloat(map, "rotX"));
            if (map.containsKey("rotZ")) ma.setRotZ(getFloat(map, "rotZ"));
            if (map.containsKey("innerFold")) ma.setInnerFold(getFloat(map, "innerFold"));
            if (map.containsKey("zScale")) ma.setZScale(getFloat(map, "zScale"));
        } else if (params instanceof MengerSpongeTestParams mst) {
            if (map.containsKey("maxIterations")) mst.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("scale")) mst.setScale(getFloat(map, "scale"));
            if (map.containsKey("offset")) mst.setOffset(getFloat(map, "offset"));
            if (map.containsKey("rotX")) mst.setRotX(getFloat(map, "rotX"));
            if (map.containsKey("rotZ")) mst.setRotZ(getFloat(map, "rotZ"));
            if (map.containsKey("zShift")) mst.setZShift(getFloat(map, "zShift"));
            if (map.containsKey("centerZ")) mst.setCenterZ(getFloat(map, "centerZ"));
        } else if (params instanceof NodeGraphParams ngp) {
            if (map.containsKey("graph") && map.get("graph") instanceof Map<?,?> graphMap) {
                // New format: deserialize full graph tree
                @SuppressWarnings("unchecked")
                Map<String, Object> gm = (Map<String, Object>) graphMap;
                GraphNode root = deserializeGraphNode(gm);
                if (root != null) {
                    ngp.setGraphRoot(root);
                }
            } else if (ngp.getRootFractalParams() != null) {
                // Legacy format: apply flat params to the root FractalNode's inner params
                applyFractalParams(ngp.getRootFractalParams(), map);
            }
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
        }
    }

    // ========================================================================
    // Graph node serialization
    // ========================================================================

    public static Map<String, Object> serializeGraphNode(GraphNode node) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        // Persist stable node name for animation track binding
        if (node.getName() != null) {
            map.put("name", node.getName());
        }
        if (node instanceof PrimitiveNode pn) {
            map.put("type", "primitive");
            map.put("primitiveType", pn.getPrimitiveType().name());
            map.put("sizeX", (double) pn.getSizeX());
            map.put("sizeY", (double) pn.getSizeY());
            map.put("sizeZ", (double) pn.getSizeZ());
            map.put("rounding", (double) pn.getRounding());
            map.put("shell", (double) pn.getShell());
        } else if (node instanceof FractalNode fn) {
            map.put("type", "fractal");
            map.put("fractalType", fn.getFractalType().name());
            // Serialize per-node fractal params (reuses extractFractalParams)
            if (fn.getFractalParams() != null) {
                Map<String, Object> params = new java.util.LinkedHashMap<>();
                extractFractalParams(fn.getFractalParams(), params);
                if (!params.isEmpty()) map.put("params", params);
            }
        } else if (node instanceof HybridNode hn) {
            map.put("type", "hybrid");
            map.put("maxIterations", hn.getMaxIterations());
            map.put("bailout", (double) hn.getBailout());
            map.put("deMode", hn.getDeMode().name());
            map.put("juliaCx", (double) hn.getJuliaCx());
            map.put("juliaCy", (double) hn.getJuliaCy());
            map.put("juliaCz", (double) hn.getJuliaCz());
            java.util.List<Object> stepList = new java.util.ArrayList<>();
            for (HybridNode.Step st : hn.getSteps()) {
                Map<String, Object> sm = new java.util.LinkedHashMap<>();
                sm.put("step", st.getType().name());
                sm.put("power", (double) st.getPower());
                sm.put("scale", (double) st.getScale());
                sm.put("minRadius", (double) st.getMinRadius());
                sm.put("fixedRadius", (double) st.getFixedRadius());
                sm.put("foldLimit", (double) st.getFoldLimit());
                sm.put("offsetX", (double) st.getOffsetX());
                sm.put("offsetY", (double) st.getOffsetY());
                sm.put("offsetZ", (double) st.getOffsetZ());
                sm.put("rotX", (double) st.getRotX());
                sm.put("rotY", (double) st.getRotY());
                sm.put("rotZ", (double) st.getRotZ());
                sm.put("radius", (double) st.getRadius());
                stepList.add(sm);
            }
            map.put("steps", stepList);
        } else if (node instanceof CSGNode csn) {
            map.put("type", "csg");
            map.put("op", csn.getOp().name());
            map.put("blend", (double) csn.getBlend());
            map.put("left", serializeGraphNode(csn.getLeft()));
            map.put("right", serializeGraphNode(csn.getRight()));
        } else if (node instanceof EffectNode en) {
            map.put("type", "effect");
            map.put("effectType", en.getEffectType().name());
            map.put("strength", (double) en.getStrength());
            map.put("time", (double) en.getTime());
            map.put("scale", (double) en.getScale());
            map.put("erosionType", en.getErosionType());
            map.put("sharpness", (double) en.getSharpness());
            map.put("child", serializeGraphNode(en.getChild()));
        } else if (node instanceof MaterialNode mn) {
            map.put("type", "material");
            map.put("materialType", mn.getMaterialType());
            map.put("colorMode", mn.getColorMode());
            map.put("colorR", (double) mn.getColorR());
            map.put("colorG", (double) mn.getColorG());
            map.put("colorB", (double) mn.getColorB());
            map.put("roughness", (double) mn.getRoughness());
            map.put("metallic", (double) mn.getMetallic());
            map.put("ior", (double) mn.getIor());
            map.put("emission", (double) mn.getEmission());
            map.put("child", serializeGraphNode(mn.getChild()));
        } else if (node instanceof TransformNode tn) {
            map.put("type", "transform");
            map.put("mode", tn.getMode().name());
            map.put("axis", tn.getAxis());
            float[] off = tn.getOffset();
            float[] rot = tn.getRotation();
            map.put("offset", List.of((double) off[0], (double) off[1], (double) off[2]));
            map.put("rotation", List.of((double) rot[0], (double) rot[1], (double) rot[2]));
            map.put("scale", (double) tn.getScale());
            map.put("frequency", (double) tn.getFrequency());
            map.put("child", serializeGraphNode(tn.getChild()));
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    public static GraphNode deserializeGraphNode(Map<String, Object> map) {
        String type = (String) map.get("type");
        if (type == null) return null;

        GraphNode result = switch (type) {
            case "primitive" -> {
                PrimitiveNode.PrimitiveType pt;
                try { pt = PrimitiveNode.PrimitiveType.valueOf((String) map.get("primitiveType")); }
                catch (Exception e) { pt = PrimitiveNode.PrimitiveType.SPHERE; }
                PrimitiveNode pn = new PrimitiveNode(pt);
                if (map.containsKey("sizeX")) pn.setSizeX(((Number) map.get("sizeX")).floatValue());
                if (map.containsKey("sizeY")) pn.setSizeY(((Number) map.get("sizeY")).floatValue());
                if (map.containsKey("sizeZ")) pn.setSizeZ(((Number) map.get("sizeZ")).floatValue());
                if (map.containsKey("rounding")) pn.setRounding(((Number) map.get("rounding")).floatValue());
                if (map.containsKey("shell")) pn.setShell(((Number) map.get("shell")).floatValue());
                yield pn;
            }
            case "hybrid" -> {
                HybridNode hn = new HybridNode();
                hn.getSteps().clear();
                Object rawSteps = map.get("steps");
                if (rawSteps instanceof java.util.List<?> list) {
                    for (Object o : list) {
                        if (!(o instanceof Map<?, ?> raw)) continue;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> sm = (Map<String, Object>) raw;
                        HybridNode.StepType stp;
                        try { stp = HybridNode.StepType.valueOf((String) sm.get("step")); }
                        catch (Exception e) { continue; }
                        HybridNode.Step st = new HybridNode.Step(stp);
                        if (sm.get("power") instanceof Number v) st.setPower(v.floatValue());
                        if (sm.get("scale") instanceof Number v) st.setScale(v.floatValue());
                        if (sm.get("minRadius") instanceof Number v) st.setMinRadius(v.floatValue());
                        if (sm.get("fixedRadius") instanceof Number v) st.setFixedRadius(v.floatValue());
                        if (sm.get("foldLimit") instanceof Number v) st.setFoldLimit(v.floatValue());
                        if (sm.get("offsetX") instanceof Number v) st.setOffsetX(v.floatValue());
                        if (sm.get("offsetY") instanceof Number v) st.setOffsetY(v.floatValue());
                        if (sm.get("offsetZ") instanceof Number v) st.setOffsetZ(v.floatValue());
                        if (sm.get("rotX") instanceof Number v) st.setRotX(v.floatValue());
                        if (sm.get("rotY") instanceof Number v) st.setRotY(v.floatValue());
                        if (sm.get("rotZ") instanceof Number v) st.setRotZ(v.floatValue());
                        if (sm.get("radius") instanceof Number v) st.setRadius(v.floatValue());
                        hn.getSteps().add(st);
                    }
                }
                if (map.get("maxIterations") instanceof Number v) hn.setMaxIterations(v.intValue());
                if (map.get("bailout") instanceof Number v) hn.setBailout(v.floatValue());
                if (map.get("juliaCx") instanceof Number v) hn.setJuliaCx(v.floatValue());
                if (map.get("juliaCy") instanceof Number v) hn.setJuliaCy(v.floatValue());
                if (map.get("juliaCz") instanceof Number v) hn.setJuliaCz(v.floatValue());
                try { hn.setDeMode(HybridNode.DEMode.valueOf((String) map.get("deMode"))); }
                catch (Exception e) { /* keep the default */ }
                yield hn;
            }
            case "fractal" -> {
                String ftName = (String) map.get("fractalType");
                FractalType ft;
                try { ft = FractalType.valueOf(ftName); }
                catch (Exception e) { ft = FractalType.MANDELBULB; }
                FractalNode fn = new FractalNode(ft);
                // Restore per-node fractal params (reuses applyFractalParams)
                if (fn.getFractalParams() != null && map.containsKey("params")
                        && map.get("params") instanceof Map<?,?> pm) {
                    applyFractalParams(fn.getFractalParams(), (Map<String, Object>) pm);
                }
                yield fn;
            }
            case "csg" -> {
                CSGNode.Op op;
                try { op = CSGNode.Op.valueOf((String) map.get("op")); }
                catch (Exception e) { op = CSGNode.Op.UNION; }
                float blend = map.containsKey("blend") ? ((Number) map.get("blend")).floatValue() : 0f;
                GraphNode left = deserializeGraphNode((Map<String, Object>) map.get("left"));
                GraphNode right = deserializeGraphNode((Map<String, Object>) map.get("right"));
                if (left == null) left = new FractalNode(FractalType.MANDELBULB);
                if (right == null) right = new FractalNode(FractalType.MENGER_SPONGE);
                yield new CSGNode(op, left, right, blend);
            }
            case "transform" -> {
                float[] offset = extractFloatArray(map.get("offset"), 3);
                float[] rotation = extractFloatArray(map.get("rotation"), 3);
                float scale = map.containsKey("scale") ? ((Number) map.get("scale")).floatValue() : 1f;
                GraphNode child = deserializeGraphNode((Map<String, Object>) map.get("child"));
                if (child == null) child = new FractalNode(FractalType.MANDELBULB);
                TransformNode tn = new TransformNode(child, offset, rotation, scale);
                if (map.containsKey("mode")) {
                    try { tn.setMode(TransformNode.Mode.valueOf((String) map.get("mode"))); }
                    catch (Exception ignored) {}
                }
                if (map.containsKey("axis")) {
                    tn.setAxis(((Number) map.get("axis")).intValue());
                }
                if (map.containsKey("frequency")) {
                    tn.setFrequency(((Number) map.get("frequency")).floatValue());
                }
                yield tn;
            }
            case "effect" -> {
                EffectNode.EffectType effectType;
                try { effectType = EffectNode.EffectType.valueOf((String) map.get("effectType")); }
                catch (Exception e) { effectType = EffectNode.EffectType.EROSION; }
                @SuppressWarnings("unchecked")
                GraphNode child = deserializeGraphNode((Map<String, Object>) map.get("child"));
                if (child == null) child = new FractalNode(FractalType.MANDELBULB);
                EffectNode en = new EffectNode(child, effectType);
                if (map.containsKey("strength")) en.setStrength(((Number) map.get("strength")).floatValue());
                if (map.containsKey("time")) en.setTime(((Number) map.get("time")).floatValue());
                if (map.containsKey("scale")) en.setScale(((Number) map.get("scale")).floatValue());
                if (map.containsKey("erosionType")) en.setErosionType(((Number) map.get("erosionType")).intValue());
                if (map.containsKey("sharpness")) en.setSharpness(((Number) map.get("sharpness")).floatValue());
                yield en;
            }
            case "material" -> {
                @SuppressWarnings("unchecked")
                GraphNode child = deserializeGraphNode((Map<String, Object>) map.get("child"));
                if (child == null) child = new FractalNode(FractalType.MANDELBULB);
                MaterialNode mn = new MaterialNode(child);
                if (map.containsKey("materialType")) mn.setMaterialType(((Number) map.get("materialType")).intValue());
                if (map.containsKey("colorMode")) mn.setColorMode(((Number) map.get("colorMode")).intValue());
                else mn.setColorMode(MaterialNode.COLOR_TINT);  // backward compat: old saves used multiplicative
                if (map.containsKey("colorR")) mn.setColorR(((Number) map.get("colorR")).floatValue());
                if (map.containsKey("colorG")) mn.setColorG(((Number) map.get("colorG")).floatValue());
                if (map.containsKey("colorB")) mn.setColorB(((Number) map.get("colorB")).floatValue());
                if (map.containsKey("roughness")) mn.setRoughness(((Number) map.get("roughness")).floatValue());
                if (map.containsKey("metallic")) mn.setMetallic(((Number) map.get("metallic")).floatValue());
                if (map.containsKey("ior")) mn.setIor(((Number) map.get("ior")).floatValue());
                if (map.containsKey("emission")) mn.setEmission(((Number) map.get("emission")).floatValue());
                yield mn;
            }
            default -> new FractalNode(FractalType.MANDELBULB);
        };

        // Restore stable node name
        if (result != null && map.containsKey("name")) {
            result.setName((String) map.get("name"));
        }

        return result;
    }

    private static float[] extractFloatArray(Object obj, int size) {
        float[] result = new float[size];
        if (obj instanceof List<?> list) {
            for (int i = 0; i < Math.min(list.size(), size); i++) {
                if (list.get(i) instanceof Number n) result[i] = n.floatValue();
            }
        }
        return result;
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
