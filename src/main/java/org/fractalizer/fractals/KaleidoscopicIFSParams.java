package org.fractalizer.fractals;

import org.fractalizer.engine.OpenCLEngine;

/**
 * Parameters specific to Kaleidoscopic IFS fractal rendering.
 * Creates symmetrical fractal patterns using reflection and scaling operations.
 * Can produce variations like Kali, Pseudo-Kleinian, and custom IFS patterns.
 */
public class KaleidoscopicIFSParams extends AbstractFractalParams {

    // ========================================================================
    // Kaleidoscopic IFS-specific parameters
    // ========================================================================

    // Number of IFS iterations
    private int maxIterations;

    // Scale factor per iteration (controls fractal density)
    private float scale;

    // Fold angles for reflection planes (in degrees)
    private float foldAngleX;  // Angle for YZ plane fold
    private float foldAngleY;  // Angle for XZ plane fold

    // Translation offset applied after each iteration
    private float offsetX;
    private float offsetY;
    private float offsetZ;

    // Minimum radius for sphere fold (like Mandelbox)
    private float minRadius;

    public KaleidoscopicIFSParams() {
        super();

        // Kaleidoscopic IFS defaults - creates interesting patterns
        this.maxIterations = 12;
        this.scale = 2.0f;

        // Fold angles (degrees) - affects symmetry
        this.foldAngleX = 77.0f;  // Classic value for interesting patterns
        this.foldAngleY = 77.0f;

        // Offset - translation after each iteration
        this.offsetX = 1.0f;
        this.offsetY = 1.0f;
        this.offsetZ = 0.0f;

        // Min radius for sphere fold (0 = disabled)
        this.minRadius = 0.5f;

        // Kaleidoscopic needs finer epsilon
        this.epsilon = 0.0003f;

        // Good camera position
        camera.setPosition(0f, 0f, -5f);
    }

    @Override
    public FractalType getType() {
        return FractalType.KALEIDOSCOPIC_IFS;
    }

    @Override
    public int setKernelParams(OpenCLEngine engine, String kernelName, int startIndex) {
        int idx = startIndex;

        // Set common camera params
        idx += setCommonKernelParams(engine, kernelName, idx);

        // Kaleidoscopic IFS-specific parameters
        engine.setKernelArgInt(kernelName, idx++, maxIterations);
        engine.setKernelArgFloat(kernelName, idx++, scale);

        // Fold angles (convert to radians in kernel)
        engine.setKernelArgFloat(kernelName, idx++, (float) Math.toRadians(foldAngleX));
        engine.setKernelArgFloat(kernelName, idx++, (float) Math.toRadians(foldAngleY));

        // Offset
        engine.setKernelArgFloats(kernelName, idx++, offsetX, offsetY, offsetZ, 0f);

        // Min radius
        engine.setKernelArgFloat(kernelName, idx++, minRadius);

        engine.setKernelArgInt(kernelName, idx++, maxRaySteps);
        engine.setKernelArgFloat(kernelName, idx++, epsilon);

        // Set lighting and effects params
        idx += setLightingKernelParams(engine, kernelName, idx);

        return idx - startIndex;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        KaleidoscopicIFSParams reduced = new KaleidoscopicIFSParams();

        // Copy common params
        copyCommonParams(reduced);

        // Copy Kaleidoscopic-specific params
        reduced.scale = this.scale;
        reduced.foldAngleX = this.foldAngleX;
        reduced.foldAngleY = this.foldAngleY;
        reduced.offsetX = this.offsetX;
        reduced.offsetY = this.offsetY;
        reduced.offsetZ = this.offsetZ;
        reduced.minRadius = this.minRadius;

        // Reduce quality
        reduced.maxIterations = Math.max(6, this.maxIterations / reductionFactor);
        applyReducedQuality(reduced, reductionFactor);

        return reduced;
    }

    // ========================================================================
    // Kaleidoscopic IFS-specific getters and setters
    // ========================================================================

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int iterations) { this.maxIterations = iterations; }

    public float getScale() { return scale; }
    public void setScale(float scale) { this.scale = scale; }

    public float getFoldAngleX() { return foldAngleX; }
    public void setFoldAngleX(float degrees) { this.foldAngleX = degrees; }

    public float getFoldAngleY() { return foldAngleY; }
    public void setFoldAngleY(float degrees) { this.foldAngleY = degrees; }

    public float getOffsetX() { return offsetX; }
    public float getOffsetY() { return offsetY; }
    public float getOffsetZ() { return offsetZ; }
    public void setOffset(float x, float y, float z) {
        this.offsetX = x;
        this.offsetY = y;
        this.offsetZ = z;
    }

    public float getMinRadius() { return minRadius; }
    public void setMinRadius(float radius) { this.minRadius = radius; }

    // Builder-style setters
    public KaleidoscopicIFSParams iterations(int max) {
        this.maxIterations = max;
        return this;
    }

    public KaleidoscopicIFSParams scale(float scale) {
        this.scale = scale;
        return this;
    }

    public KaleidoscopicIFSParams foldAngles(float angleX, float angleY) {
        this.foldAngleX = angleX;
        this.foldAngleY = angleY;
        return this;
    }

    public KaleidoscopicIFSParams offset(float x, float y, float z) {
        setOffset(x, y, z);
        return this;
    }

    public KaleidoscopicIFSParams minRadius(float radius) {
        this.minRadius = radius;
        return this;
    }
}