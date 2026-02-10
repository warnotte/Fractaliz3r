package org.fractalizer.fractals;

/**
 * Parameters specific to Bristorbrot fractal rendering.
 * Component-wise 3D Mandelbrot producing elongated, asymmetric bulb shapes.
 * Supports Julia mode via juliaC constant.
 */
public class BristorbrotParams extends AbstractFractalParams {

    @Animatable(display = "Iterations")
    private int maxIterations;

    @Animatable(display = "Bailout")
    private float bailout;

    @Animatable(display = "Julia Cx")
    private float juliaCx;

    @Animatable(display = "Julia Cy")
    private float juliaCy;

    @Animatable(display = "Julia Cz")
    private float juliaCz;

    public BristorbrotParams() {
        super();
        this.maxIterations = 15;
        this.bailout = 4.0f;
        this.juliaCx = 0.0f;
        this.juliaCy = 0.0f;
        this.juliaCz = 0.0f;

        camera.setPosition(0f, 0f, -3f);
    }

    @Override
    public FractalType getType() {
        return FractalType.BRISTORBROT;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        BristorbrotParams reduced = new BristorbrotParams();
        copyCommonParams(reduced);
        reduced.bailout = this.bailout;
        reduced.juliaCx = this.juliaCx;
        reduced.juliaCy = this.juliaCy;
        reduced.juliaCz = this.juliaCz;
        reduced.maxIterations = Math.max(5, this.maxIterations / reductionFactor);
        applyReducedQuality(reduced, reductionFactor);
        return reduced;
    }

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
}
