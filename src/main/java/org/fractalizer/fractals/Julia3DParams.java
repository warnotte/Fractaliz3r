package org.fractalizer.fractals;

/**
 * Parameters specific to Quaternion Julia 3D fractal rendering.
 *
 * The Julia set uses the iteration: q' = q² + c
 * where q and c are quaternions.
 *
 * Famous Julia constants produce beautiful shapes:
 * - c = (-0.2, 0.8, 0, 0) - Classic Julia
 * - c = (-0.291, -0.399, 0.339, 0.437) - Detailed organic
 * - c = (-0.125, -0.256, 0.847, 0.0895) - Spiky
 */
public class Julia3DParams extends AbstractFractalParams {

    // ========================================================================
    // Julia 3D-specific parameters
    // ========================================================================

    @Animatable(display = "Iterations")
    private int maxIterations;

    @Animatable(display = "Bailout")
    private float bailout;

    // Julia constant quaternion (cx, cy, cz, cw)
    @Animatable(display = "Julia Cx")
    private float juliaCx;

    @Animatable(display = "Julia Cy")
    private float juliaCy;

    @Animatable(display = "Julia Cz")
    private float juliaCz;

    @Animatable(display = "Julia Cw")
    private float juliaCw;

    public Julia3DParams() {
        super();

        // Default iteration settings
        this.maxIterations = 12;
        this.bailout = 4.0f;

        // Classic Julia constant - produces nice organic shapes
        this.juliaCx = -0.2f;
        this.juliaCy = 0.8f;
        this.juliaCz = 0.0f;
        this.juliaCw = 0.0f;

        // Ray marching settings
        this.epsilon = 0.0001f;
        this.maxRaySteps = 200;

        // Camera position
        camera.setPosition(0f, 0f, -3f);
    }

    @Override
    public FractalType getType() {
        return FractalType.JULIA_3D;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        Julia3DParams reduced = new Julia3DParams();

        // Copy common params
        copyCommonParams(reduced);

        // Copy Julia-specific params
        reduced.bailout = this.bailout;
        reduced.juliaCx = this.juliaCx;
        reduced.juliaCy = this.juliaCy;
        reduced.juliaCz = this.juliaCz;
        reduced.juliaCw = this.juliaCw;

        // Reduce quality
        reduced.maxIterations = Math.max(6, this.maxIterations / reductionFactor);
        applyReducedQuality(reduced, reductionFactor);

        return reduced;
    }

    // ========================================================================
    // Julia 3D-specific getters and setters
    // ========================================================================

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int iterations) { this.maxIterations = iterations; }

    public float getBailout() { return bailout; }
    public void setBailout(float bailout) { this.bailout = bailout; }

    public float getJuliaCx() { return juliaCx; }
    public float getJuliaCy() { return juliaCy; }
    public float getJuliaCz() { return juliaCz; }
    public float getJuliaCw() { return juliaCw; }

    public void setJuliaC(float cx, float cy, float cz, float cw) {
        this.juliaCx = cx;
        this.juliaCy = cy;
        this.juliaCz = cz;
        this.juliaCw = cw;
    }

    public void setJuliaCx(float cx) { this.juliaCx = cx; }
    public void setJuliaCy(float cy) { this.juliaCy = cy; }
    public void setJuliaCz(float cz) { this.juliaCz = cz; }
    public void setJuliaCw(float cw) { this.juliaCw = cw; }

    // Builder-style setters
    public Julia3DParams iterations(int max) {
        this.maxIterations = max;
        return this;
    }

    public Julia3DParams bailout(float b) {
        this.bailout = b;
        return this;
    }

    public Julia3DParams juliaC(float cx, float cy, float cz, float cw) {
        setJuliaC(cx, cy, cz, cw);
        return this;
    }

    // ========================================================================
    // Presets
    // ========================================================================

    public static Julia3DParams classicPreset() {
        Julia3DParams p = new Julia3DParams();
        p.setJuliaC(-0.2f, 0.8f, 0.0f, 0.0f);
        return p;
    }

    public static Julia3DParams organicPreset() {
        Julia3DParams p = new Julia3DParams();
        p.setJuliaC(-0.291f, -0.399f, 0.339f, 0.437f);
        p.setMaxIterations(15);
        return p;
    }

    public static Julia3DParams spikyPreset() {
        Julia3DParams p = new Julia3DParams();
        p.setJuliaC(-0.125f, -0.256f, 0.847f, 0.0895f);
        p.setMaxIterations(14);
        return p;
    }

    public static Julia3DParams spiralPreset() {
        Julia3DParams p = new Julia3DParams();
        p.setJuliaC(-0.4f, 0.6f, 0.2f, -0.1f);
        p.setMaxIterations(12);
        return p;
    }
}
