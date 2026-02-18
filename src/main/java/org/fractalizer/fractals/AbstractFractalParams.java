package org.fractalizer.fractals;

import org.fractalizer.engine.Camera;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abstract base class for fractal parameters.
 * Contains all common rendering parameters shared across fractal types.
 */
public abstract class AbstractFractalParams implements FractalParams {

    // Cache of animatable field metadata per concrete class (fields don't change at runtime)
    private static final Map<Class<?>, List<AnimatableFieldInfo>> FIELD_INFO_CACHE = new ConcurrentHashMap<>();

    /** Internal record to cache field reflection info without binding to a specific instance. */
    private record AnimatableFieldInfo(Field field, String name, String displayName, Class<?> valueType) {}

    // Render mode constants (must match shaders/common.glsl RENDER_MODE_* defines)
    public static final int RENDER_FINAL = 0;
    public static final int RENDER_NORMALS = 1;
    public static final int RENDER_DEPTH = 2;
    public static final int RENDER_AO = 3;
    public static final int RENDER_SHADOWS = 4;
    public static final int RENDER_DIFFUSE = 5;
    public static final int RENDER_SPECULAR = 6;
    public static final int RENDER_ORBIT_TRAP = 7;
    public static final int RENDER_ITERATIONS = 8;

    // Projection modes
    public static final int PROJECTION_PERSPECTIVE = 0;
    public static final int PROJECTION_360_EQUIRECTANGULAR = 1;

    // ========================================================================
    // Common parameters for all fractals
    // ========================================================================

    // Camera reference
    protected Camera camera;

    // Projection mode
    protected int projectionMode;

    // Field of view in radians
    protected float fov;

    // Ray marching parameters
    protected int maxRaySteps;
    protected float epsilon;

    // Quality multiplier for adaptive precision (1.0 = normal, higher = more detail)
    protected float qualityMultiplier;

    // Light direction (will be normalized in kernel)
    protected float lightX, lightY, lightZ;

    // Light color and intensity
    protected float lightR, lightG, lightB;
    protected float lightIntensity;

    // Ambient color and intensity
    protected float ambientR, ambientG, ambientB;
    protected float ambientIntensity;

    // Additional light source
    // Type: 0 = Off, 1 = Directional, 2 = Point (omni), 3 = Spot
    protected int extraLightType;
    protected boolean extraLightAttachToCamera;
    // Camera-local transform for additional light (offset + local direction).
    protected float extraLightX, extraLightY, extraLightZ;
    protected float extraLightDirX, extraLightDirY, extraLightDirZ;
    protected float extraLightR, extraLightG, extraLightB;
    protected float extraLightIntensity;
    protected float extraLightRange;
    protected float extraLightAreaRadius;
    protected float extraLightConeAngle;
    protected float extraLightConeSoftness;

    // Material hue offset for color palette
    protected float hueR, hueG, hueB;
    protected int paletteIndex;
    protected float colorStrength;
    protected float paletteOffset;
    protected int coloringMode;  // 0=Standard, 1=Bands, 2=Distance, 3=Angle, 4=Blend, 5=Contour

    // Rendering quality
    protected float shadowSoftness;
    protected int shadowSteps;
    protected int aoSteps;
    protected float aoIntensity;
    protected float glowIntensity;

    // Specular
    protected float specularIntensity;
    protected float specularPower;

    // Render mode for pass visualization
    protected int renderMode;

    // Depth of Field
    protected boolean dofEnabled;
    protected float focalDistance;
    protected float aperture;
    protected int dofSamples;
    protected float anamorphicRatio = 1.0f;
    protected int bokehBlades = 0;
    protected float bokehRotation = 0.0f;
    protected float opticalVignettingStrength = 0.0f;
    protected boolean tiltShiftEnabled = false;
    protected float tiltAngleX = 0.0f;
    protected float tiltAngleY = 0.0f;
    protected float dofChromaticStrength = 0.0f;

    // Path Tracing
    protected boolean pathTracingEnabled;
    protected int maxBounces;
    protected float roughness;
    protected float skyIntensity;
    protected float indirectMultiplier;  // Controls indirect light contribution (0 = no GI, 1 = full GI)
    
    // Sky
    protected int skyType; // 0=Clouds, 1=Space, 2=Ocean, 3=Studio
    protected float cloudDensity;
    protected float skySpeed; // Renamed to "Variation" in UI
    protected float skyTime;  // Manual time control
    protected float skyParallax;

    // Lens Effects
    protected boolean lensEffectsEnabled;
    protected float lensDirtIntensity;
    protected float starburstIntensity;

    // Volumetric Fog
    protected boolean volumetricFogEnabled;
    protected float fogDensity;
    protected float[] fogColorVec;
    protected float fogScattering; // Anisotropy (-1 to 1)
    protected int fogSteps;

    // Material System
    // Type: 0 = Lambertian (diffuse), 1 = Metallic, 2 = Glass (dielectric)
    public static final int MATERIAL_LAMBERTIAN = 0;
    public static final int MATERIAL_METALLIC = 1;
    public static final int MATERIAL_GLASS = 2;

    // Additional Light Types
    public static final int EXTRA_LIGHT_OFF = 0;
    public static final int EXTRA_LIGHT_DIRECTIONAL = 1;
    public static final int EXTRA_LIGHT_POINT = 2;
    public static final int EXTRA_LIGHT_SPOT = 3;

    protected int materialType;
    protected float metalness;       // For metallic: blend between dielectric and metal (0-1)
    protected float ior;             // Index of refraction for glass (typically 1.5)

    // Advanced Effects
    protected float reflectionIntensity;  // 0-1, ray-marched reflections (classic mode)
    protected float emissiveIntensity;    // 0-3, self-illumination based on orbit traps
    protected float sssIntensity;         // 0-2, subsurface scattering strength
    protected float sssRadius;            // 0.01-0.5, SSS sampling depth
    protected float[] sssColorVec;        // RGB color for SSS (warm = wax/skin, cool = jade/marble)

    // Motion Blur (for animation export)
    // shutterAngle: 0 = no blur, 180 = half frame, 360 = full frame blur (cinematic default)
    protected float shutterAngle;

    // NEE + MIS (environment importance sampling)
    protected boolean neeEnabled = true;

    // Adaptive Sampling
    protected boolean adaptiveSampling = false;
    protected float varianceThreshold = 0.0005f;
    protected int minAdaptiveSamples = 16;

    // Erosion
    protected boolean erosionEnabled = false;
    protected float erosionStrength = 0.5f;
    protected float erosionTime = 0.0f;
    protected float erosionScale = 1.0f;
    protected int erosionType = 0; // 0=All, 1=Hydraulic, 2=Thermal, 3=Cracks

    // Crystallization
    protected boolean crystalEnabled = false;
    protected float crystalStrength = 0.5f;
    protected float crystalTime = 0.0f;
    protected float crystalScale = 1.0f;
    protected float crystalSharpness = 2.0f;

    // Moss/Lichen
    protected boolean mossEnabled = false;
    protected float mossStrength = 0.5f;
    protected float mossTime = 0.0f;
    protected float mossScale = 1.0f;
    protected float mossColorR = 0.15f;
    protected float mossColorG = 0.35f;
    protected float mossColorB = 0.08f;
    protected float mossNormalThreshold = 0.3f;

    // Custom gradient palette (used when paletteIndex == 6)
    protected GradientPalette customGradient;

    /**
     * Initialize common parameters with sensible defaults.
     */
    protected AbstractFractalParams() {
        this.camera = new Camera();
        this.projectionMode = PROJECTION_PERSPECTIVE;
        this.fov = (float) Math.toRadians(60);

        // Ray marching defaults
        this.maxRaySteps = 200;
        this.epsilon = 0.0001f;
        this.qualityMultiplier = 1.0f;

        // Default light direction (top-right-front)
        this.lightX = 2f;
        this.lightY = 3f;
        this.lightZ = -2f;

        // Default light color (warm white)
        this.lightR = 1.0f;
        this.lightG = 0.95f;
        this.lightB = 0.9f;
        this.lightIntensity = 1.2f;

        // Default ambient (cool blue)
        this.ambientR = 0.1f;
        this.ambientG = 0.15f;
        this.ambientB = 0.25f;
        this.ambientIntensity = 0.3f;

        // Additional light defaults (disabled)
        this.extraLightType = EXTRA_LIGHT_OFF;
        this.extraLightAttachToCamera = true;
        this.extraLightX = 0.0f;
        this.extraLightY = 0.0f;
        this.extraLightZ = 0.0f;
        this.extraLightDirX = 0.0f;
        this.extraLightDirY = 0.0f;
        this.extraLightDirZ = 1.0f;
        this.extraLightR = 1.0f;
        this.extraLightG = 0.95f;
        this.extraLightB = 0.9f;
        this.extraLightIntensity = 1.5f;
        this.extraLightRange = 2.0f;
        this.extraLightAreaRadius = 0.03f;
        this.extraLightConeAngle = 35.0f;
        this.extraLightConeSoftness = 0.3f;

        // Default material hue (blue-purple)
        this.hueR = 0.0f;
        this.hueG = 0.33f;
        this.hueB = 0.67f;
        this.paletteIndex = 0;
        this.colorStrength = 1.0f;
        this.paletteOffset = 0.0f;
        this.coloringMode = 0;

        // Enhanced rendering defaults
        this.shadowSoftness = 16f;
        this.shadowSteps = 128;
        this.aoSteps = 5;
        this.aoIntensity = 0.5f;
        this.glowIntensity = 0.15f;

        // Specular defaults
        this.specularIntensity = 0.5f;
        this.specularPower = 32f;

        // DoF defaults (disabled by default)
        this.dofEnabled = false;
        this.focalDistance = 2.5f;
        this.aperture = 0.02f;
        this.dofSamples = 16;

        // Path tracing defaults
        this.pathTracingEnabled = true;
        this.maxBounces = 4;
        this.roughness = 0.5f;
        this.skyIntensity = 1.0f;
        this.indirectMultiplier = 0.5f;
        this.skyType = 1; // Default to Deep Space
        this.cloudDensity = 0.5f;
        this.skySpeed = 1.0f; // Variation scale
        this.skyTime = 0.0f;
        this.skyParallax = 0.25f;

        // Material defaults (Lambertian diffuse)
        this.materialType = MATERIAL_LAMBERTIAN;
        this.metalness = 0.9f;
        this.ior = 1.5f;  // Glass IOR

        // Advanced Effects (all disabled by default)
        this.reflectionIntensity = 0.0f;
        this.emissiveIntensity = 0.0f;
        this.sssIntensity = 0.0f;
        this.sssRadius = 0.1f;
        this.sssColorVec = new float[]{1.0f, 0.4f, 0.2f};

        // Motion blur defaults (180° = cinematic film standard)
        this.shutterAngle = 180.0f;

        // Custom gradient (default: Spectral rainbow)
        this.customGradient = GradientPalette.createSpectral();

        // Lens defaults
        this.lensEffectsEnabled = false;
        this.lensDirtIntensity = 0.0f;
        this.starburstIntensity = 0.0f;

        // Volumetric Fog defaults
        this.volumetricFogEnabled = false;
        this.fogDensity = 0.15f;
        this.fogColorVec = new float[]{0.5f, 0.6f, 0.7f};
        this.fogScattering = 0.5f; // Forward scattering
        this.fogSteps = 32;
    }

    /**
     * Copy common parameters to another instance.
     */
    public void copyCommonParams(AbstractFractalParams target) {
        target.camera = this.camera; // Share camera reference
        target.projectionMode = this.projectionMode;
        target.fov = this.fov;
        target.maxRaySteps = this.maxRaySteps;
        target.epsilon = this.epsilon;
        target.qualityMultiplier = this.qualityMultiplier;

        // Copy lighting
        target.lightX = this.lightX;
        target.lightY = this.lightY;
        target.lightZ = this.lightZ;
        target.lightR = this.lightR;
        target.lightG = this.lightG;
        target.lightB = this.lightB;
        target.lightIntensity = this.lightIntensity;
        target.ambientR = this.ambientR;
        target.ambientG = this.ambientG;
        target.ambientB = this.ambientB;
        target.ambientIntensity = this.ambientIntensity;
        target.extraLightType = this.extraLightType;
        target.extraLightAttachToCamera = this.extraLightAttachToCamera;
        target.extraLightX = this.extraLightX;
        target.extraLightY = this.extraLightY;
        target.extraLightZ = this.extraLightZ;
        target.extraLightDirX = this.extraLightDirX;
        target.extraLightDirY = this.extraLightDirY;
        target.extraLightDirZ = this.extraLightDirZ;
        target.extraLightR = this.extraLightR;
        target.extraLightG = this.extraLightG;
        target.extraLightB = this.extraLightB;
        target.extraLightIntensity = this.extraLightIntensity;
        target.extraLightRange = this.extraLightRange;
        target.extraLightAreaRadius = this.extraLightAreaRadius;
        target.extraLightConeAngle = this.extraLightConeAngle;
        target.extraLightConeSoftness = this.extraLightConeSoftness;
        target.hueR = this.hueR;
        target.hueG = this.hueG;
        target.hueB = this.hueB;
        target.paletteIndex = this.paletteIndex;
        target.colorStrength = this.colorStrength;
        target.paletteOffset = this.paletteOffset;
        target.coloringMode = this.coloringMode;

        // Copy specular
        target.specularIntensity = this.specularIntensity;
        target.specularPower = this.specularPower;

        // Copy shadows and AO
        target.shadowSoftness = this.shadowSoftness;
        target.shadowSteps = this.shadowSteps;
        target.aoSteps = this.aoSteps;
        target.aoIntensity = this.aoIntensity;
        target.glowIntensity = this.glowIntensity;

        // Copy render mode
        target.renderMode = this.renderMode;

        // Copy DoF
        target.dofEnabled = this.dofEnabled;
        target.focalDistance = this.focalDistance;
        target.aperture = this.aperture;
        target.dofSamples = this.dofSamples;
        target.anamorphicRatio = this.anamorphicRatio;
        target.bokehBlades = this.bokehBlades;
        target.bokehRotation = this.bokehRotation;
        target.opticalVignettingStrength = this.opticalVignettingStrength;
        target.tiltShiftEnabled = this.tiltShiftEnabled;
        target.tiltAngleX = this.tiltAngleX;
        target.tiltAngleY = this.tiltAngleY;
        target.dofChromaticStrength = this.dofChromaticStrength;

        // Copy path tracing
        target.pathTracingEnabled = this.pathTracingEnabled;
        target.maxBounces = this.maxBounces;
        target.roughness = this.roughness;
        target.skyIntensity = this.skyIntensity;
        target.indirectMultiplier = this.indirectMultiplier;
        target.skyType = this.skyType;
        target.cloudDensity = this.cloudDensity;
        target.skySpeed = this.skySpeed;
        target.skyTime = this.skyTime;
        target.skyParallax = this.skyParallax;

        // Copy Lens Effects
        target.lensEffectsEnabled = this.lensEffectsEnabled;
        target.lensDirtIntensity = this.lensDirtIntensity;
        target.starburstIntensity = this.starburstIntensity;

        // Copy Volumetric Fog
        target.volumetricFogEnabled = this.volumetricFogEnabled;
        target.fogDensity = this.fogDensity;
        target.fogColorVec = this.fogColorVec.clone();
        target.fogScattering = this.fogScattering;
        target.fogSteps = this.fogSteps;

        // Copy material
        target.materialType = this.materialType;
        target.metalness = this.metalness;
        target.ior = this.ior;

        // Copy Advanced Effects
        target.reflectionIntensity = this.reflectionIntensity;
        target.emissiveIntensity = this.emissiveIntensity;
        target.sssIntensity = this.sssIntensity;
        target.sssRadius = this.sssRadius;
        target.sssColorVec = this.sssColorVec.clone();

        // Copy NEE + MIS
        target.neeEnabled = this.neeEnabled;

        // Copy Adaptive Sampling
        target.adaptiveSampling = this.adaptiveSampling;
        target.varianceThreshold = this.varianceThreshold;
        target.minAdaptiveSamples = this.minAdaptiveSamples;

        // Copy Erosion
        target.erosionEnabled = this.erosionEnabled;
        target.erosionStrength = this.erosionStrength;
        target.erosionTime = this.erosionTime;
        target.erosionScale = this.erosionScale;
        target.erosionType = this.erosionType;

        // Copy Crystallization
        target.crystalEnabled = this.crystalEnabled;
        target.crystalStrength = this.crystalStrength;
        target.crystalTime = this.crystalTime;
        target.crystalScale = this.crystalScale;
        target.crystalSharpness = this.crystalSharpness;

        // Copy Moss
        target.mossEnabled = this.mossEnabled;
        target.mossStrength = this.mossStrength;
        target.mossTime = this.mossTime;
        target.mossScale = this.mossScale;
        target.mossColorR = this.mossColorR;
        target.mossColorG = this.mossColorG;
        target.mossColorB = this.mossColorB;
        target.mossNormalThreshold = this.mossNormalThreshold;

        // Copy custom gradient
        target.customGradient = this.customGradient.copy();
    }

    /**
     * Apply reduced quality settings for preview rendering.
     */
    protected void applyReducedQuality(AbstractFractalParams target, int reductionFactor) {
        target.maxRaySteps = Math.max(50, this.maxRaySteps / reductionFactor);
        target.epsilon = this.epsilon * reductionFactor * 2;
        target.shadowSoftness = 8f;
        target.shadowSteps = Math.max(32, this.shadowSteps / reductionFactor);
        target.aoSteps = 2;
        target.dofSamples = Math.max(4, this.dofSamples / reductionFactor);
    }

    // ========================================================================
    // Animatable parameter discovery via reflection
    // ========================================================================

    /**
     * Get the kernel name for this fractal type (e.g. "mandelbulb").
     * Used to prefix animation track names.
     */
    public String getKernelName() {
        return getType().getKernelName();
    }

    /**
     * Discover all @Animatable fields in this concrete class via reflection.
     * Field metadata is cached per class; getter/setter lambdas are bound to this instance.
     */
    public List<AnimatableParameter> getAnimatableParameters() {
        List<AnimatableFieldInfo> fieldInfos = FIELD_INFO_CACHE.computeIfAbsent(this.getClass(), clazz -> {
            List<AnimatableFieldInfo> infos = new ArrayList<>();
            for (Field field : clazz.getDeclaredFields()) {
                Animatable ann = field.getAnnotation(Animatable.class);
                if (ann == null) continue;

                field.setAccessible(true);

                // Map primitive types to boxed types
                Class<?> valueType;
                if (field.getType() == float.class) {
                    valueType = Float.class;
                } else if (field.getType() == int.class) {
                    valueType = Integer.class;
                } else if (field.getType() == double.class) {
                    valueType = Double.class;
                } else {
                    valueType = field.getType();
                }

                infos.add(new AnimatableFieldInfo(field, field.getName(), ann.display(), valueType));
            }
            return infos;
        });

        // Build AnimatableParameter list with getter/setter bound to THIS instance
        List<AnimatableParameter> params = new ArrayList<>(fieldInfos.size());
        for (AnimatableFieldInfo info : fieldInfos) {
            final Field f = info.field();
            params.add(new AnimatableParameter(
                info.name(), info.displayName(), info.valueType(),
                () -> {
                    try {
                        return f.get(this);
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException("Cannot read animatable field: " + f.getName(), e);
                    }
                },
                value -> {
                    try {
                        if (f.getType() == int.class && value instanceof Number n) {
                            f.setInt(this, n.intValue());
                        } else if (f.getType() == float.class && value instanceof Number n) {
                            f.setFloat(this, n.floatValue());
                        } else if (f.getType() == double.class && value instanceof Number n) {
                            f.setDouble(this, n.doubleValue());
                        } else {
                            f.set(this, value);
                        }
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException("Cannot write animatable field: " + f.getName(), e);
                    }
                }
            ));
        }
        return params;
    }

    // ========================================================================
    // Abstract methods to be implemented by specific fractals
    // ========================================================================

    /**
     * Get the fractal type enum.
     */
    public abstract FractalType getType();

    @Override
    public String getFractalType() {
        return getType().getDisplayName();
    }

    // ========================================================================
    // Common getters and setters
    // ========================================================================

    // Camera
    public Camera getCamera() { return camera; }
    public void setCamera(Camera camera) { this.camera = camera; }
    
    public int getProjectionMode() { return projectionMode; }
    public void setProjectionMode(int mode) { this.projectionMode = mode; }

    // FOV
    public float getFov() { return fov; }
    public void setFov(float radians) { this.fov = radians; }
    public void setFovDegrees(float degrees) { this.fov = (float) Math.toRadians(degrees); }

    // Ray marching
    public int getMaxRaySteps() { return maxRaySteps; }
    public void setMaxRaySteps(int steps) { this.maxRaySteps = steps; }
    public float getEpsilon() { return epsilon; }
    public void setEpsilon(float epsilon) { this.epsilon = epsilon; }
    public float getQualityMultiplier() { return qualityMultiplier; }
    public void setQualityMultiplier(float multiplier) { this.qualityMultiplier = Math.max(0.5f, multiplier); }

    // Light direction
    public float getLightX() { return lightX; }
    public float getLightY() { return lightY; }
    public float getLightZ() { return lightZ; }
    public void setLightDirection(float x, float y, float z) {
        this.lightX = x;
        this.lightY = y;
        this.lightZ = z;
    }

    // Light color
    public float getLightR() { return lightR; }
    public float getLightG() { return lightG; }
    public float getLightB() { return lightB; }
    public void setLightColor(float r, float g, float b) {
        this.lightR = r;
        this.lightG = g;
        this.lightB = b;
    }
    public float getLightIntensity() { return lightIntensity; }
    public void setLightIntensity(float intensity) { this.lightIntensity = intensity; }

    // Ambient
    public float getAmbientR() { return ambientR; }
    public float getAmbientG() { return ambientG; }
    public float getAmbientB() { return ambientB; }
    public void setAmbientColor(float r, float g, float b) {
        this.ambientR = r;
        this.ambientG = g;
        this.ambientB = b;
    }
    public float getAmbientIntensity() { return ambientIntensity; }
    public void setAmbientIntensity(float intensity) { this.ambientIntensity = intensity; }

    // Additional light
    public int getExtraLightType() { return extraLightType; }
    public void setExtraLightType(int type) {
        int clamped = Math.max(EXTRA_LIGHT_OFF, Math.min(EXTRA_LIGHT_SPOT, type));
        // Directional mode is intentionally disabled for this additional light workflow.
        this.extraLightType = (clamped == EXTRA_LIGHT_DIRECTIONAL) ? EXTRA_LIGHT_OFF : clamped;
    }
    public boolean isExtraLightAttachToCamera() { return true; }
    public void setExtraLightAttachToCamera(boolean attachToCamera) { this.extraLightAttachToCamera = true; }
    public float getExtraLightX() { return extraLightX; }
    public float getExtraLightY() { return extraLightY; }
    public float getExtraLightZ() { return extraLightZ; }
    public void setExtraLightPosition(float x, float y, float z) {
        this.extraLightX = x;
        this.extraLightY = y;
        this.extraLightZ = z;
    }
    public float getExtraLightDirX() { return extraLightDirX; }
    public float getExtraLightDirY() { return extraLightDirY; }
    public float getExtraLightDirZ() { return extraLightDirZ; }
    public void setExtraLightDirection(float x, float y, float z) {
        this.extraLightDirX = x;
        this.extraLightDirY = y;
        this.extraLightDirZ = z;
    }
    public float getExtraLightR() { return extraLightR; }
    public float getExtraLightG() { return extraLightG; }
    public float getExtraLightB() { return extraLightB; }
    public void setExtraLightColor(float r, float g, float b) {
        this.extraLightR = Math.max(0.0f, r);
        this.extraLightG = Math.max(0.0f, g);
        this.extraLightB = Math.max(0.0f, b);
    }
    public float getExtraLightIntensity() { return extraLightIntensity; }
    public void setExtraLightIntensity(float intensity) { this.extraLightIntensity = Math.max(0.0f, intensity); }
    public float getExtraLightRange() { return extraLightRange; }
    public void setExtraLightRange(float range) { this.extraLightRange = Math.max(0.01f, range); }
    public float getExtraLightAreaRadius() { return extraLightAreaRadius; }
    public void setExtraLightAreaRadius(float radius) { this.extraLightAreaRadius = Math.max(0.0f, Math.min(0.1f, radius)); }
    public float getExtraLightConeAngle() { return extraLightConeAngle; }
    public void setExtraLightConeAngle(float angle) { this.extraLightConeAngle = Math.max(1.0f, Math.min(89.0f, angle)); }
    public float getExtraLightConeSoftness() { return extraLightConeSoftness; }
    public void setExtraLightConeSoftness(float softness) { this.extraLightConeSoftness = Math.max(0.0f, Math.min(1.0f, softness)); }

    // Material hue
    public float getHueR() { return hueR; }
    public float getHueG() { return hueG; }
    public float getHueB() { return hueB; }
    public void setMaterialHue(float r, float g, float b) {
        this.hueR = r;
        this.hueG = g;
        this.hueB = b;
    }
    public int getPaletteIndex() { return paletteIndex; }
    public void setPaletteIndex(int index) { this.paletteIndex = index; }
    public float getColorStrength() { return colorStrength; }
    public void setColorStrength(float strength) { this.colorStrength = strength; }
    public float getPaletteOffset() { return paletteOffset; }
    public void setPaletteOffset(float offset) { this.paletteOffset = offset; }
    public int getColoringMode() { return coloringMode; }
    public void setColoringMode(int mode) { this.coloringMode = mode; }

    // Shadows
    public float getShadowSoftness() { return shadowSoftness; }
    public void setShadowSoftness(float softness) { this.shadowSoftness = softness; }
    public int getShadowSteps() { return shadowSteps; }
    public void setShadowSteps(int steps) { this.shadowSteps = steps; }

    // AO
    public int getAoSteps() { return aoSteps; }
    public void setAoSteps(int steps) { this.aoSteps = steps; }
    public float getAoIntensity() { return aoIntensity; }
    public void setAoIntensity(float intensity) { this.aoIntensity = intensity; }

    // Glow
    public float getGlowIntensity() { return glowIntensity; }
    public void setGlowIntensity(float intensity) { this.glowIntensity = intensity; }

    // Specular
    public float getSpecularIntensity() { return specularIntensity; }
    public void setSpecularIntensity(float intensity) { this.specularIntensity = intensity; }
    public float getSpecularPower() { return specularPower; }
    public void setSpecularPower(float power) { this.specularPower = power; }

    // Render mode
    public int getRenderMode() { return renderMode; }
    public void setRenderMode(int mode) { this.renderMode = mode; }

    // DoF
    public boolean isDofEnabled() { return dofEnabled; }
    public void setDofEnabled(boolean enabled) { this.dofEnabled = enabled; }
    public float getFocalDistance() { return focalDistance; }
    public void setFocalDistance(float distance) { this.focalDistance = distance; }
    public float getAperture() { return aperture; }
    public void setAperture(float aperture) { this.aperture = aperture; }
    public int getDofSamples() { return dofSamples; }
    public void setDofSamples(int samples) { this.dofSamples = samples; }

    // Enhanced DoF
    public float getAnamorphicRatio() { return anamorphicRatio; }
    public void setAnamorphicRatio(float ratio) { this.anamorphicRatio = Math.max(0.3f, Math.min(1.0f, ratio)); }
    public int getBokehBlades() { return bokehBlades; }
    public void setBokehBlades(int blades) { this.bokehBlades = blades; }
    public float getBokehRotation() { return bokehRotation; }
    public void setBokehRotation(float radians) { this.bokehRotation = radians; }
    public float getOpticalVignettingStrength() { return opticalVignettingStrength; }
    public void setOpticalVignettingStrength(float strength) { this.opticalVignettingStrength = Math.max(0.0f, Math.min(1.0f, strength)); }
    public boolean isTiltShiftEnabled() { return tiltShiftEnabled; }
    public void setTiltShiftEnabled(boolean enabled) { this.tiltShiftEnabled = enabled; }
    public float getTiltAngleX() { return tiltAngleX; }
    public void setTiltAngleX(float radians) { this.tiltAngleX = radians; }
    public float getTiltAngleY() { return tiltAngleY; }
    public void setTiltAngleY(float radians) { this.tiltAngleY = radians; }
    public float getDofChromaticStrength() { return dofChromaticStrength; }
    public void setDofChromaticStrength(float strength) { this.dofChromaticStrength = Math.max(0.0f, Math.min(0.1f, strength)); }

    // Path Tracing
    public boolean isPathTracingEnabled() { return pathTracingEnabled; }
    public void setPathTracingEnabled(boolean enabled) { this.pathTracingEnabled = enabled; }
    public int getMaxBounces() { return maxBounces; }
    public void setMaxBounces(int bounces) { this.maxBounces = bounces; }
    public float getRoughness() { return roughness; }
    public void setRoughness(float roughness) { this.roughness = roughness; }
    public float getSkyIntensity() { return skyIntensity; }
    public void setSkyIntensity(float intensity) { this.skyIntensity = intensity; }
    public float getIndirectMultiplier() { return indirectMultiplier; }
    public void setIndirectMultiplier(float multiplier) { this.indirectMultiplier = Math.max(0, Math.min(1, multiplier)); }

    // Sky
    public int getSkyType() { return skyType; }
    public void setSkyType(int type) { this.skyType = type; }
    public float getCloudDensity() { return cloudDensity; }
    public void setCloudDensity(float density) { this.cloudDensity = density; }
    public float getSkySpeed() { return skySpeed; }
    public void setSkySpeed(float speed) { this.skySpeed = speed; }
    public float getSkyTime() { return skyTime; }
    public void setSkyTime(float time) { this.skyTime = time; }
    public float getSkyParallax() { return skyParallax; }
    public void setSkyParallax(float parallax) { this.skyParallax = parallax; }

    // Lens Effects
    public boolean isLensEffectsEnabled() { return lensEffectsEnabled; }
    public void setLensEffectsEnabled(boolean enabled) { this.lensEffectsEnabled = enabled; }
    public float getLensDirtIntensity() { return lensDirtIntensity; }
    public void setLensDirtIntensity(float intensity) { this.lensDirtIntensity = intensity; }
    public float getStarburstIntensity() { return starburstIntensity; }
    public void setStarburstIntensity(float intensity) { this.starburstIntensity = intensity; }

    // Volumetric Fog
    public boolean isVolumetricFogEnabled() { return volumetricFogEnabled; }
    public void setVolumetricFogEnabled(boolean enabled) { this.volumetricFogEnabled = enabled; }
    public float getFogDensity() { return fogDensity; }
    public void setFogDensity(float density) { this.fogDensity = density; }
    public float[] getFogColor() { return fogColorVec; }
    public void setFogColor(float r, float g, float b) {
        this.fogColorVec[0] = r;
        this.fogColorVec[1] = g;
        this.fogColorVec[2] = b;
    }
    public float getFogScattering() { return fogScattering; }
    public void setFogScattering(float scattering) { this.fogScattering = scattering; }
    public int getFogSteps() { return fogSteps; }
    public void setFogSteps(int steps) { this.fogSteps = steps; }

    // Material
    public int getMaterialType() { return materialType; }
    public void setMaterialType(int type) { this.materialType = Math.max(0, Math.min(2, type)); }
    public float getMetalness() { return metalness; }
    public void setMetalness(float metalness) { this.metalness = Math.max(0, Math.min(1, metalness)); }
    public float getIor() { return ior; }
    public void setIor(float ior) { this.ior = Math.max(1.0f, Math.min(3.0f, ior)); }

    // Advanced Effects
    public float getReflectionIntensity() { return reflectionIntensity; }
    public void setReflectionIntensity(float intensity) { this.reflectionIntensity = Math.max(0, Math.min(1, intensity)); }
    public float getEmissiveIntensity() { return emissiveIntensity; }
    public void setEmissiveIntensity(float intensity) { this.emissiveIntensity = Math.max(0, Math.min(3, intensity)); }
    public float getSssIntensity() { return sssIntensity; }
    public void setSssIntensity(float intensity) { this.sssIntensity = Math.max(0, Math.min(2, intensity)); }
    public float getSssRadius() { return sssRadius; }
    public void setSssRadius(float radius) { this.sssRadius = Math.max(0.01f, Math.min(0.5f, radius)); }
    public float[] getSssColor() { return sssColorVec; }
    public void setSssColor(float r, float g, float b) {
        this.sssColorVec[0] = r;
        this.sssColorVec[1] = g;
        this.sssColorVec[2] = b;
    }

    // Motion Blur
    public float getShutterAngle() { return shutterAngle; }
    public void setShutterAngle(float angle) { this.shutterAngle = Math.max(0, Math.min(360, angle)); }

    // NEE + MIS
    public boolean isNeeEnabled() { return neeEnabled; }
    public void setNeeEnabled(boolean enabled) { this.neeEnabled = enabled; }

    // Adaptive Sampling
    public boolean isAdaptiveSampling() { return adaptiveSampling; }
    public void setAdaptiveSampling(boolean enabled) { this.adaptiveSampling = enabled; }
    public float getVarianceThreshold() { return varianceThreshold; }
    public void setVarianceThreshold(float threshold) { this.varianceThreshold = threshold; }
    public int getMinAdaptiveSamples() { return minAdaptiveSamples; }
    public void setMinAdaptiveSamples(int min) { this.minAdaptiveSamples = min; }

    // Erosion
    public boolean isErosionEnabled() { return erosionEnabled; }
    public void setErosionEnabled(boolean enabled) { this.erosionEnabled = enabled; }
    public float getErosionStrength() { return erosionStrength; }
    public void setErosionStrength(float strength) { this.erosionStrength = Math.max(0, Math.min(1, strength)); }
    public float getErosionTime() { return erosionTime; }
    public void setErosionTime(float time) { this.erosionTime = Math.max(0, Math.min(20, time)); }
    public float getErosionScale() { return erosionScale; }
    public void setErosionScale(float scale) { this.erosionScale = Math.max(0.1f, Math.min(5, scale)); }
    public int getErosionType() { return erosionType; }
    public void setErosionType(int type) { this.erosionType = Math.max(0, Math.min(3, type)); }

    // Crystallization
    public boolean isCrystalEnabled() { return crystalEnabled; }
    public void setCrystalEnabled(boolean enabled) { this.crystalEnabled = enabled; }
    public float getCrystalStrength() { return crystalStrength; }
    public void setCrystalStrength(float strength) { this.crystalStrength = Math.max(0, Math.min(1, strength)); }
    public float getCrystalTime() { return crystalTime; }
    public void setCrystalTime(float time) { this.crystalTime = Math.max(0, Math.min(10, time)); }
    public float getCrystalScale() { return crystalScale; }
    public void setCrystalScale(float scale) { this.crystalScale = Math.max(0.1f, Math.min(5, scale)); }
    public float getCrystalSharpness() { return crystalSharpness; }
    public void setCrystalSharpness(float sharpness) { this.crystalSharpness = Math.max(0.5f, Math.min(5, sharpness)); }

    // Moss/Lichen
    public boolean isMossEnabled() { return mossEnabled; }
    public void setMossEnabled(boolean enabled) { this.mossEnabled = enabled; }
    public float getMossStrength() { return mossStrength; }
    public void setMossStrength(float strength) { this.mossStrength = Math.max(0, Math.min(1, strength)); }
    public float getMossTime() { return mossTime; }
    public void setMossTime(float time) { this.mossTime = Math.max(0, Math.min(10, time)); }
    public float getMossScale() { return mossScale; }
    public void setMossScale(float scale) { this.mossScale = Math.max(0.1f, Math.min(5, scale)); }
    public float getMossColorR() { return mossColorR; }
    public float getMossColorG() { return mossColorG; }
    public float getMossColorB() { return mossColorB; }
    public void setMossColor(float r, float g, float b) {
        this.mossColorR = r;
        this.mossColorG = g;
        this.mossColorB = b;
    }
    public float getMossNormalThreshold() { return mossNormalThreshold; }
    public void setMossNormalThreshold(float threshold) { this.mossNormalThreshold = Math.max(0, Math.min(1, threshold)); }

    // Custom Gradient
    public GradientPalette getCustomGradient() { return customGradient; }
    public void setCustomGradient(GradientPalette gradient) { this.customGradient = gradient; }

    // ========================================================================
    // Builder-style setters (return this for chaining)
    // These return AbstractFractalParams but subclasses can override
    // ========================================================================

    @SuppressWarnings("unchecked")
    protected <T extends AbstractFractalParams> T self() {
        return (T) this;
    }

    public <T extends AbstractFractalParams> T fov(float degrees) {
        this.fov = (float) Math.toRadians(degrees);
        return self();
    }

    public <T extends AbstractFractalParams> T raySteps(int max) {
        this.maxRaySteps = max;
        return self();
    }

    public <T extends AbstractFractalParams> T epsilon(float epsilon) {
        this.epsilon = epsilon;
        return self();
    }

    public <T extends AbstractFractalParams> T lightDirection(float x, float y, float z) {
        setLightDirection(x, y, z);
        return self();
    }

    public <T extends AbstractFractalParams> T lightColor(float r, float g, float b) {
        setLightColor(r, g, b);
        return self();
    }

    public <T extends AbstractFractalParams> T lightIntensity(float intensity) {
        this.lightIntensity = intensity;
        return self();
    }

    public <T extends AbstractFractalParams> T ambientColor(float r, float g, float b) {
        setAmbientColor(r, g, b);
        return self();
    }

    public <T extends AbstractFractalParams> T ambientIntensity(float intensity) {
        this.ambientIntensity = intensity;
        return self();
    }

    public <T extends AbstractFractalParams> T materialHue(float r, float g, float b) {
        setMaterialHue(r, g, b);
        return self();
    }

    public <T extends AbstractFractalParams> T shadowSoftness(float softness) {
        this.shadowSoftness = softness;
        return self();
    }

    public <T extends AbstractFractalParams> T shadowSteps(int steps) {
        this.shadowSteps = steps;
        return self();
    }

    public <T extends AbstractFractalParams> T aoSteps(int steps) {
        this.aoSteps = steps;
        return self();
    }

    public <T extends AbstractFractalParams> T aoIntensity(float intensity) {
        this.aoIntensity = intensity;
        return self();
    }

    public <T extends AbstractFractalParams> T glowIntensity(float intensity) {
        this.glowIntensity = intensity;
        return self();
    }

    public <T extends AbstractFractalParams> T specularIntensity(float intensity) {
        this.specularIntensity = intensity;
        return self();
    }

    public <T extends AbstractFractalParams> T specularPower(float power) {
        this.specularPower = power;
        return self();
    }

    public <T extends AbstractFractalParams> T renderMode(int mode) {
        this.renderMode = mode;
        return self();
    }

    public <T extends AbstractFractalParams> T dofEnabled(boolean enabled) {
        this.dofEnabled = enabled;
        return self();
    }

    public <T extends AbstractFractalParams> T focalDistance(float distance) {
        this.focalDistance = distance;
        return self();
    }

    public <T extends AbstractFractalParams> T aperture(float aperture) {
        this.aperture = aperture;
        return self();
    }

    public <T extends AbstractFractalParams> T dofSamples(int samples) {
        this.dofSamples = samples;
        return self();
    }
}
