package org.fractalizer.fractals;

import org.fractalizer.engine.Camera;
import org.fractalizer.engine.OpenCLEngine;

/**
 * Parameters for Mandelbulb fractal rendering.
 * Includes enhanced lighting and material controls.
 */
public class MandelbulbParams implements FractalParams {

    // Camera reference
    private Camera camera;

    // Field of view in radians
    private float fov;

    // Mandelbulb power (classic is 8)
    private float power;

    // Ray marching parameters
    private int maxIterations;
    private int maxRaySteps;
    private float bailout;
    private float epsilon;

    // Light direction (will be normalized in kernel)
    private float lightX, lightY, lightZ;

    // Light color and intensity
    private float lightR, lightG, lightB;
    private float lightIntensity;

    // Ambient color and intensity
    private float ambientR, ambientG, ambientB;
    private float ambientIntensity;

    // Material hue offset for color palette
    private float hueR, hueG, hueB;

    // Rendering quality
    private float shadowSoftness;
    private int shadowSteps;
    private int aoSteps;
    private float aoIntensity;
    private float glowIntensity;

    // Specular
    private float specularIntensity;
    private float specularPower;

    // Render mode for pass visualization
    private int renderMode;

    // Depth of Field
    private boolean dofEnabled;
    private float focalDistance;
    private float aperture;
    private int dofSamples;

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

    public MandelbulbParams() {
        this.camera = new Camera();

        this.fov = (float) Math.toRadians(60);
        this.power = 8f;

        this.maxIterations = 15;
        this.maxRaySteps = 200;
        this.bailout = 2f;
        this.epsilon = 0.0001f;

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
        this.focalDistance = 2.5f;  // Distance to focal plane
        this.aperture = 0.02f;      // Aperture size (0 = pinhole, higher = more blur)
        this.dofSamples = 16;       // Samples for DoF (higher = smoother but slower)
    }

    @Override
    public int setKernelParams(OpenCLEngine engine, String kernelName, int startIndex) {
        int idx = startIndex;

        // Camera position
        float[] pos = camera.getPosition();
        engine.setKernelArgFloats(kernelName, idx++, pos[0], pos[1], pos[2], 0f);

        // Camera quaternion for orientation
        float[] quat = camera.getQuaternion();
        engine.setKernelArgFloats(kernelName, idx++, quat[0], quat[1], quat[2], quat[3]);

        // FOV
        engine.setKernelArgFloat(kernelName, idx++, fov);

        // Mandelbulb parameters
        engine.setKernelArgFloat(kernelName, idx++, power);
        engine.setKernelArgInt(kernelName, idx++, maxIterations);
        engine.setKernelArgInt(kernelName, idx++, maxRaySteps);
        engine.setKernelArgFloat(kernelName, idx++, bailout);
        engine.setKernelArgFloat(kernelName, idx++, epsilon);

        // Light direction
        engine.setKernelArgFloats(kernelName, idx++, lightX, lightY, lightZ, 0f);

        // Light color + intensity
        engine.setKernelArgFloats(kernelName, idx++, lightR, lightG, lightB, lightIntensity);

        // Ambient color + intensity
        engine.setKernelArgFloats(kernelName, idx++, ambientR, ambientG, ambientB, ambientIntensity);

        // Material hue
        engine.setKernelArgFloats(kernelName, idx++, hueR, hueG, hueB, 0f);

        // Rendering quality
        engine.setKernelArgFloat(kernelName, idx++, shadowSoftness);
        engine.setKernelArgInt(kernelName, idx++, shadowSteps);
        engine.setKernelArgInt(kernelName, idx++, aoSteps);
        engine.setKernelArgFloat(kernelName, idx++, aoIntensity);
        engine.setKernelArgFloat(kernelName, idx++, glowIntensity);

        // Specular
        engine.setKernelArgFloat(kernelName, idx++, specularIntensity);
        engine.setKernelArgFloat(kernelName, idx++, specularPower);

        // Render mode
        engine.setKernelArgInt(kernelName, idx++, renderMode);

        // DoF parameters
        engine.setKernelArgInt(kernelName, idx++, dofEnabled ? 1 : 0);
        engine.setKernelArgFloat(kernelName, idx++, focalDistance);
        engine.setKernelArgFloat(kernelName, idx++, aperture);
        engine.setKernelArgInt(kernelName, idx++, dofSamples);

        return idx - startIndex;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        MandelbulbParams reduced = new MandelbulbParams();
        reduced.camera = this.camera; // Share camera reference
        reduced.fov = this.fov;
        reduced.power = this.power;

        // Copy lighting
        reduced.lightX = this.lightX;
        reduced.lightY = this.lightY;
        reduced.lightZ = this.lightZ;
        reduced.lightR = this.lightR;
        reduced.lightG = this.lightG;
        reduced.lightB = this.lightB;
        reduced.lightIntensity = this.lightIntensity;
        reduced.ambientR = this.ambientR;
        reduced.ambientG = this.ambientG;
        reduced.ambientB = this.ambientB;
        reduced.ambientIntensity = this.ambientIntensity;
        reduced.hueR = this.hueR;
        reduced.hueG = this.hueG;
        reduced.hueB = this.hueB;

        // Copy specular
        reduced.specularIntensity = this.specularIntensity;
        reduced.specularPower = this.specularPower;

        // Reduce quality for preview
        reduced.maxIterations = Math.max(5, this.maxIterations / reductionFactor);
        reduced.maxRaySteps = Math.max(50, this.maxRaySteps / reductionFactor);
        reduced.bailout = this.bailout;
        reduced.epsilon = this.epsilon * reductionFactor * 2;

        // Simplified rendering for preview
        reduced.shadowSoftness = 8f;
        reduced.shadowSteps = Math.max(32, this.shadowSteps / reductionFactor);
        reduced.aoSteps = 2;
        reduced.aoIntensity = this.aoIntensity;
        reduced.glowIntensity = this.glowIntensity;

        // Copy render mode
        reduced.renderMode = this.renderMode;

        // DoF: copy settings but reduce samples for preview
        reduced.dofEnabled = this.dofEnabled;
        reduced.focalDistance = this.focalDistance;
        reduced.aperture = this.aperture;
        reduced.dofSamples = Math.max(4, this.dofSamples / reductionFactor);

        return reduced;
    }

    @Override
    public String getFractalType() {
        return "Mandelbulb";
    }

    // Camera access
    public Camera getCamera() {
        return camera;
    }

    public void setCamera(Camera camera) {
        this.camera = camera;
    }

    // Builder-style setters for fractal params
    public MandelbulbParams fov(float degrees) {
        this.fov = (float) Math.toRadians(degrees);
        return this;
    }

    public MandelbulbParams power(float power) {
        this.power = power;
        return this;
    }

    public MandelbulbParams iterations(int max) {
        this.maxIterations = max;
        return this;
    }

    public MandelbulbParams raySteps(int max) {
        this.maxRaySteps = max;
        return this;
    }

    public MandelbulbParams bailout(float bailout) {
        this.bailout = bailout;
        return this;
    }

    public MandelbulbParams epsilon(float epsilon) {
        this.epsilon = epsilon;
        return this;
    }

    // Light direction
    public MandelbulbParams lightDirection(float x, float y, float z) {
        this.lightX = x;
        this.lightY = y;
        this.lightZ = z;
        return this;
    }

    // Light color
    public MandelbulbParams lightColor(float r, float g, float b) {
        this.lightR = r;
        this.lightG = g;
        this.lightB = b;
        return this;
    }

    public MandelbulbParams lightIntensity(float intensity) {
        this.lightIntensity = intensity;
        return this;
    }

    // Ambient
    public MandelbulbParams ambientColor(float r, float g, float b) {
        this.ambientR = r;
        this.ambientG = g;
        this.ambientB = b;
        return this;
    }

    public MandelbulbParams ambientIntensity(float intensity) {
        this.ambientIntensity = intensity;
        return this;
    }

    // Material hue
    public MandelbulbParams materialHue(float r, float g, float b) {
        this.hueR = r;
        this.hueG = g;
        this.hueB = b;
        return this;
    }

    // Rendering quality
    public MandelbulbParams shadowSoftness(float softness) {
        this.shadowSoftness = softness;
        return this;
    }

    public MandelbulbParams aoSteps(int steps) {
        this.aoSteps = steps;
        return this;
    }

    public MandelbulbParams aoIntensity(float intensity) {
        this.aoIntensity = intensity;
        return this;
    }

    public MandelbulbParams glowIntensity(float intensity) {
        this.glowIntensity = intensity;
        return this;
    }

    // Specular
    public MandelbulbParams specularIntensity(float intensity) {
        this.specularIntensity = intensity;
        return this;
    }

    public MandelbulbParams specularPower(float power) {
        this.specularPower = power;
        return this;
    }

    // Getters
    public float getFov() { return fov; }
    public float getPower() { return power; }
    public int getMaxIterations() { return maxIterations; }
    public int getMaxRaySteps() { return maxRaySteps; }
    public float getBailout() { return bailout; }
    public float getEpsilon() { return epsilon; }

    public float getLightX() { return lightX; }
    public float getLightY() { return lightY; }
    public float getLightZ() { return lightZ; }
    public float getLightR() { return lightR; }
    public float getLightG() { return lightG; }
    public float getLightB() { return lightB; }
    public float getLightIntensity() { return lightIntensity; }

    public float getAmbientR() { return ambientR; }
    public float getAmbientG() { return ambientG; }
    public float getAmbientB() { return ambientB; }
    public float getAmbientIntensity() { return ambientIntensity; }

    public float getHueR() { return hueR; }
    public float getHueG() { return hueG; }
    public float getHueB() { return hueB; }

    public float getShadowSoftness() { return shadowSoftness; }
    public int getShadowSteps() { return shadowSteps; }
    public void setShadowSteps(int steps) { this.shadowSteps = steps; }
    public MandelbulbParams shadowSteps(int steps) {
        this.shadowSteps = steps;
        return this;
    }
    public int getAoSteps() { return aoSteps; }
    public float getAoIntensity() { return aoIntensity; }
    public float getGlowIntensity() { return glowIntensity; }

    public float getSpecularIntensity() { return specularIntensity; }
    public float getSpecularPower() { return specularPower; }

    // Render mode
    public int getRenderMode() { return renderMode; }
    public MandelbulbParams renderMode(int mode) {
        this.renderMode = mode;
        return this;
    }
    public void setRenderMode(int mode) {
        this.renderMode = mode;
    }

    // DoF getters and setters
    public boolean isDofEnabled() { return dofEnabled; }
    public void setDofEnabled(boolean enabled) { this.dofEnabled = enabled; }
    public MandelbulbParams dofEnabled(boolean enabled) {
        this.dofEnabled = enabled;
        return this;
    }

    public float getFocalDistance() { return focalDistance; }
    public void setFocalDistance(float distance) { this.focalDistance = distance; }
    public MandelbulbParams focalDistance(float distance) {
        this.focalDistance = distance;
        return this;
    }

    public float getAperture() { return aperture; }
    public void setAperture(float aperture) { this.aperture = aperture; }
    public MandelbulbParams aperture(float aperture) {
        this.aperture = aperture;
        return this;
    }

    public int getDofSamples() { return dofSamples; }
    public void setDofSamples(int samples) { this.dofSamples = samples; }
    public MandelbulbParams dofSamples(int samples) {
        this.dofSamples = samples;
        return this;
    }
}