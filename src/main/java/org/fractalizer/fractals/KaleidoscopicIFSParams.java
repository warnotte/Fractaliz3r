package org.fractalizer.fractals;

/**
 * Parameters specific to Kaleidoscopic IFS fractal rendering.
 * Based on the classic Sierpinski tetrahedron folding algorithm.
 * Reference: Syntopia blog - Distance Estimated 3D Fractals (III): Folding Space
 */
public class KaleidoscopicIFSParams extends AbstractFractalParams {

    // ========================================================================
    // Kaleidoscopic IFS-specific parameters
    // ========================================================================

    // Number of IFS iterations
    private int maxIterations;

    // Scale factor per iteration (2.0 for classic Sierpinski)
    private float scale;

    // Optional rotation angles for variety (in degrees)
    private float foldAngleX;
    private float foldAngleY;

    // Scalar offset for translation (3.0 for classic Sierpinski)
    // Note: stored in offsetX, offsetY/Z are unused but kept for UI compatibility
    private float offsetX;
    private float offsetY;
    private float offsetZ;

    // Minimum radius (unused in this algorithm but kept for signature compatibility)
    private float minRadius;

    public KaleidoscopicIFSParams() {
        super();

        // Classic Sierpinski tetrahedron / KIFS defaults
        this.maxIterations = 15;
        this.scale = 2.0f;  // Standard scale for Sierpinski

        // No rotation by default - creates classic Sierpinski tetrahedron
        // Add small values (e.g., 5-15 degrees) for variations
        this.foldAngleX = 0.0f;
        this.foldAngleY = 0.0f;

        // Offset = 3.0 for classic Sierpinski (uses only offsetX)
        this.offsetX = 3.0f;
        this.offsetY = 0.0f;  // unused
        this.offsetZ = 0.0f;  // unused

        // Unused in this algorithm but kept for compatibility
        this.minRadius = 0.0f;

        // Standard ray marching settings
        this.epsilon = 0.0001f;
        this.maxRaySteps = 150;

        // Camera position to view the fractal
        camera.setPosition(0f, 0f, -5f);
    }

    @Override
    public FractalType getType() {
        return FractalType.KALEIDOSCOPIC_IFS;
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