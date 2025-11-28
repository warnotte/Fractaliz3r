package org.fractalizer.fractals;

import org.fractalizer.engine.Camera;
import org.fractalizer.engine.OpenCLEngine;

/**
 * Parameters for Mandelbulb fractal rendering.
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

    // Lighting
    private float lightX, lightY, lightZ;

    // Rendering quality
    private float shadowSoftness;
    private int aoSteps;
    private float aoIntensity;
    private float glowIntensity;

    public MandelbulbParams() {
        this.camera = new Camera();

        this.fov = (float) Math.toRadians(60);
        this.power = 8f;

        this.maxIterations = 15;
        this.maxRaySteps = 200;
        this.bailout = 2f;
        this.epsilon = 0.0001f;

        this.lightX = 2f;
        this.lightY = 3f;
        this.lightZ = -2f;

        // Enhanced rendering defaults
        this.shadowSoftness = 16f;
        this.aoSteps = 5;
        this.aoIntensity = 0.2f;
        this.glowIntensity = 0.1f;
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

        // Lighting
        engine.setKernelArgFloats(kernelName, idx++, lightX, lightY, lightZ, 0f);

        // Enhanced rendering
        engine.setKernelArgFloat(kernelName, idx++, shadowSoftness);
        engine.setKernelArgInt(kernelName, idx++, aoSteps);
        engine.setKernelArgFloat(kernelName, idx++, aoIntensity);
        engine.setKernelArgFloat(kernelName, idx++, glowIntensity);

        return idx - startIndex;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        MandelbulbParams reduced = new MandelbulbParams();
        reduced.camera = this.camera; // Share camera reference
        reduced.fov = this.fov;
        reduced.power = this.power;
        reduced.lightX = this.lightX;
        reduced.lightY = this.lightY;
        reduced.lightZ = this.lightZ;

        // Reduce quality for preview
        reduced.maxIterations = Math.max(5, this.maxIterations / reductionFactor);
        reduced.maxRaySteps = Math.max(50, this.maxRaySteps / reductionFactor);
        reduced.bailout = this.bailout;
        reduced.epsilon = this.epsilon * reductionFactor * 2;

        // Simplified rendering for preview
        reduced.shadowSoftness = 8f;
        reduced.aoSteps = 2;
        reduced.aoIntensity = this.aoIntensity;
        reduced.glowIntensity = this.glowIntensity;

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

    // Builder-style setters
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

    public MandelbulbParams light(float x, float y, float z) {
        this.lightX = x; this.lightY = y; this.lightZ = z;
        return this;
    }

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
    public float getShadowSoftness() { return shadowSoftness; }
    public int getAoSteps() { return aoSteps; }
    public float getAoIntensity() { return aoIntensity; }
    public float getGlowIntensity() { return glowIntensity; }
}