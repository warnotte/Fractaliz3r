package org.fractalizer.fractals;

/**
 * Parameters specific to Mandelbulb fractal rendering.
 * Inherits common rendering parameters from AbstractFractalParams.
 */
public class MandelbulbParams extends AbstractFractalParams {

    // ========================================================================
    // Mandelbulb-specific parameters
    // ========================================================================

    // Mandelbulb power (classic is 8)
    @Animatable(display = "Power")
    private float power;

    // Fractal iteration parameters
    @Animatable(display = "Iterations")
    private int maxIterations;

    @Animatable(display = "Bailout")
    private float bailout;

    @Animatable(display = "Radiolaria")
    private float radiolaria;

    @Animatable(display = "Radiolaria Limit")
    private float radiolariaFactor;

    // Julia constant. Left at (0,0,0) the formula stays a Mandelbrot: the set has
    // large analytic bulbs whose surface is locally smooth, so a deep dive lands on
    // featureless drapes. A non-zero constant makes every point a boundary point and
    // the structure survives arbitrarily deep zooms.
    @Animatable(display = "Julia Cx")
    private float juliaCx;

    @Animatable(display = "Julia Cy")
    private float juliaCy;

    @Animatable(display = "Julia Cz")
    private float juliaCz;

    public MandelbulbParams() {
        super();

        // Mandelbulb-specific defaults
        this.power = 8f;
        this.maxIterations = 15;
        this.bailout = 2f;
        this.radiolaria = 0f;
        this.radiolariaFactor = 0.5f;
        this.juliaCx = 0f;
        this.juliaCy = 0f;
        this.juliaCz = 0f;
    }

    @Override
    public FractalType getType() {
        return FractalType.MANDELBULB;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        MandelbulbParams reduced = new MandelbulbParams();

        // Copy common params
        copyCommonParams(reduced);

        // Copy Mandelbulb-specific params
        reduced.power = this.power;
        reduced.bailout = this.bailout;
        reduced.radiolaria = this.radiolaria;
        reduced.radiolariaFactor = this.radiolariaFactor;
        reduced.juliaCx = this.juliaCx;
        reduced.juliaCy = this.juliaCy;
        reduced.juliaCz = this.juliaCz;

        // Reduce quality
        reduced.maxIterations = Math.max(5, this.maxIterations / reductionFactor);
        applyReducedQuality(reduced, reductionFactor);

        return reduced;
    }

    // ========================================================================
    // Mandelbulb-specific getters and setters
    // ========================================================================

    public float getPower() { return power; }
    public void setPower(float power) { this.power = power; }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int iterations) { this.maxIterations = iterations; }

    public float getBailout() { return bailout; }
    public void setBailout(float bailout) { this.bailout = bailout; }

    public float getJuliaCx() { return juliaCx; }
    public void setJuliaCx(float v) { this.juliaCx = v; }

    public float getJuliaCy() { return juliaCy; }
    public void setJuliaCy(float v) { this.juliaCy = v; }

    public float getJuliaCz() { return juliaCz; }
    public void setJuliaCz(float v) { this.juliaCz = v; }

    public float getRadiolaria() { return radiolaria; }
    public void setRadiolaria(float radiolaria) { this.radiolaria = radiolaria; }

    public float getRadiolariaFactor() { return radiolariaFactor; }
    public void setRadiolariaFactor(float radiolariaFactor) { this.radiolariaFactor = radiolariaFactor; }

    // Builder-style setters
    public MandelbulbParams power(float power) {
        this.power = power;
        return this;
    }

    public MandelbulbParams iterations(int max) {
        this.maxIterations = max;
        return this;
    }

    public MandelbulbParams bailout(float bailout) {
        this.bailout = bailout;
        return this;
    }
}
