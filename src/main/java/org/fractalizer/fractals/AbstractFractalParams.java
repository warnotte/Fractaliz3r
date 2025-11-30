package org.fractalizer.fractals;

import org.fractalizer.engine.Camera;

/**
 * Abstract base class for fractal parameters.
 * Contains all common rendering parameters shared across fractal types.
 */
public abstract class AbstractFractalParams implements FractalParams {

    // Render mode constants (must match kernel defines)
    public static final int RENDER_FINAL = 0;
    public static final int RENDER_NORMALS = 1;
    public static final int RENDER_DEPTH = 2;
    public static final int RENDER_AO = 3;
    public static final int RENDER_SHADOWS = 4;
    public static final int RENDER_DIFFUSE = 5;
    public static final int RENDER_SPECULAR = 6;
    public static final int RENDER_ORBIT_TRAP = 7;
    public static final int RENDER_ITERATIONS = 8;

    // ========================================================================
    // Common parameters for all fractals
    // ========================================================================

    // Camera reference
    protected Camera camera;

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

    // Material hue offset for color palette
    protected float hueR, hueG, hueB;

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

    // Path Tracing
    protected boolean pathTracingEnabled;
    protected int maxBounces;
    protected float roughness;
    protected float skyIntensity;

    /**
     * Initialize common parameters with sensible defaults.
     */
    protected AbstractFractalParams() {
        this.camera = new Camera();
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

        // Default material hue (blue-purple)
        this.hueR = 0.0f;
        this.hueG = 0.33f;
        this.hueB = 0.67f;

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

        // Path tracing defaults (disabled by default)
        this.pathTracingEnabled = false;
        this.maxBounces = 4;
        this.roughness = 0.5f;
        this.skyIntensity = 1.0f;
    }

    /**
     * Copy common parameters to another instance.
     */
    protected void copyCommonParams(AbstractFractalParams target) {
        target.camera = this.camera; // Share camera reference
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
        target.hueR = this.hueR;
        target.hueG = this.hueG;
        target.hueB = this.hueB;

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

        // Copy path tracing
        target.pathTracingEnabled = this.pathTracingEnabled;
        target.maxBounces = this.maxBounces;
        target.roughness = this.roughness;
        target.skyIntensity = this.skyIntensity;
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

    // Material hue
    public float getHueR() { return hueR; }
    public float getHueG() { return hueG; }
    public float getHueB() { return hueB; }
    public void setMaterialHue(float r, float g, float b) {
        this.hueR = r;
        this.hueG = g;
        this.hueB = b;
    }

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

    // Path Tracing
    public boolean isPathTracingEnabled() { return pathTracingEnabled; }
    public void setPathTracingEnabled(boolean enabled) { this.pathTracingEnabled = enabled; }
    public int getMaxBounces() { return maxBounces; }
    public void setMaxBounces(int bounces) { this.maxBounces = bounces; }
    public float getRoughness() { return roughness; }
    public void setRoughness(float roughness) { this.roughness = roughness; }
    public float getSkyIntensity() { return skyIntensity; }
    public void setSkyIntensity(float intensity) { this.skyIntensity = intensity; }

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
