package org.fractalizer.fractals;

/**
 * Parameters for Pseudo Kleinian fractal rendering.
 *
 * The Pseudo Kleinian is a fascinating fractal that combines:
 * - Box folding (clamping space)
 * - Sphere folding (inversion)
 * - Julia-style translation
 *
 * Based on the Fragmentarium implementation by Knighty.
 * Creates beautiful organic, coral-like and crystalline structures.
 */
public class PseudoKleinianParams extends AbstractFractalParams {

    // ========================================================================
    // Pseudo Kleinian-specific parameters
    // ========================================================================

    // Fractal iteration count
    private int maxIterations;

    // Sphere fold size (controls overall shape)
    private float size;

    // Box fold size (vec3 - controls folding bounds)
    private float cSizeX;
    private float cSizeY;
    private float cSizeZ;

    // Julia constant (vec3 - controls fractal variations)
    private float juliaX;
    private float juliaY;
    private float juliaZ;

    // Distance estimator offset (fine-tunes surface detection)
    private float deOffset;

    // Z-offset for the final DE calculation
    private float zOffset;

    public PseudoKleinianParams() {
        super();

        // Pseudo Kleinian-specific defaults (based on Fragmentarium presets)
        this.maxIterations = 8;
        this.size = 1.0f;

        // CSize - typical values create interesting folding patterns
        this.cSizeX = 0.90453f;
        this.cSizeY = 0.92f;
        this.cSizeZ = 0.90453f;

        // Julia constant - these create the variations
        this.juliaX = 0.0f;
        this.juliaY = 0.0f;
        this.juliaZ = 0.0f;

        // DE parameters
        this.deOffset = 0.0f;
        this.zOffset = 1.0f;

        // Adjust some common defaults for Pseudo Kleinian
        this.epsilon = 0.0005f;

        // Good starting camera position
        getCamera().setPosition(0f, 0f, -3f);
    }

    @Override
    public FractalType getType() {
        return FractalType.PSEUDO_KLEINIAN;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        PseudoKleinianParams reduced = new PseudoKleinianParams();

        // Copy common params
        copyCommonParams(reduced);

        // Copy Pseudo Kleinian-specific params
        reduced.size = this.size;
        reduced.cSizeX = this.cSizeX;
        reduced.cSizeY = this.cSizeY;
        reduced.cSizeZ = this.cSizeZ;
        reduced.juliaX = this.juliaX;
        reduced.juliaY = this.juliaY;
        reduced.juliaZ = this.juliaZ;
        reduced.deOffset = this.deOffset;
        reduced.zOffset = this.zOffset;

        // Reduce quality
        reduced.maxIterations = Math.max(4, this.maxIterations / reductionFactor);
        applyReducedQuality(reduced, reductionFactor);

        return reduced;
    }

    // ========================================================================
    // Pseudo Kleinian-specific getters and setters
    // ========================================================================

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int iterations) { this.maxIterations = iterations; }

    public float getSize() { return size; }
    public void setSize(float size) { this.size = size; }

    public float getCSizeX() { return cSizeX; }
    public void setCSizeX(float x) { this.cSizeX = x; }

    public float getCSizeY() { return cSizeY; }
    public void setCSizeY(float y) { this.cSizeY = y; }

    public float getCSizeZ() { return cSizeZ; }
    public void setCSizeZ(float z) { this.cSizeZ = z; }

    public void setCSize(float x, float y, float z) {
        this.cSizeX = x;
        this.cSizeY = y;
        this.cSizeZ = z;
    }

    public float getJuliaX() { return juliaX; }
    public void setJuliaX(float x) { this.juliaX = x; }

    public float getJuliaY() { return juliaY; }
    public void setJuliaY(float y) { this.juliaY = y; }

    public float getJuliaZ() { return juliaZ; }
    public void setJuliaZ(float z) { this.juliaZ = z; }

    public void setJulia(float x, float y, float z) {
        this.juliaX = x;
        this.juliaY = y;
        this.juliaZ = z;
    }

    public float getDeOffset() { return deOffset; }
    public void setDeOffset(float offset) { this.deOffset = offset; }

    public float getZOffset() { return zOffset; }
    public void setZOffset(float offset) { this.zOffset = offset; }

    // Builder-style setters for fluent API
    public PseudoKleinianParams iterations(int max) {
        this.maxIterations = max;
        return this;
    }

    public PseudoKleinianParams size(float size) {
        this.size = size;
        return this;
    }

    public PseudoKleinianParams cSize(float x, float y, float z) {
        setCSize(x, y, z);
        return this;
    }

    public PseudoKleinianParams julia(float x, float y, float z) {
        setJulia(x, y, z);
        return this;
    }

    public PseudoKleinianParams deOffset(float offset) {
        this.deOffset = offset;
        return this;
    }

    public PseudoKleinianParams zOffset(float offset) {
        this.zOffset = offset;
        return this;
    }
}
