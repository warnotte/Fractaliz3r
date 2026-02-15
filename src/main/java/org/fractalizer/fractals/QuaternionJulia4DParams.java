package org.fractalizer.fractals;

/**
 * Parameters for Quaternion Julia 4D fractal rendering.
 *
 * Extends the Julia 3D concept with a 4th-dimension slice parameter
 * and 4D rotation planes (XW, YW, ZW) to explore the full 4D
 * quaternion Julia set — seeing cross-sections impossible with Julia 3D.
 */
public class QuaternionJulia4DParams extends AbstractFractalParams {

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

    @Animatable(display = "Julia Cw")
    private float juliaCw;

    @Animatable(display = "Slice W")
    private float sliceW;

    @Animatable(display = "Rot XW")
    private float rotXW;

    @Animatable(display = "Rot YW")
    private float rotYW;

    @Animatable(display = "Rot ZW")
    private float rotZW;

    public QuaternionJulia4DParams() {
        super();
        this.maxIterations = 12;
        this.bailout = 4.0f;
        this.juliaCx = -0.2f;
        this.juliaCy = 0.8f;
        this.juliaCz = 0.0f;
        this.juliaCw = 0.0f;
        this.sliceW = 0.0f;
        this.rotXW = 0.0f;
        this.rotYW = 0.0f;
        this.rotZW = 0.0f;
        this.epsilon = 0.0001f;
        this.maxRaySteps = 200;
        camera.setPosition(0f, 0f, -3f);
    }

    @Override
    public FractalType getType() {
        return FractalType.QUATERNION_JULIA_4D;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        QuaternionJulia4DParams reduced = new QuaternionJulia4DParams();
        copyCommonParams(reduced);
        reduced.bailout = this.bailout;
        reduced.juliaCx = this.juliaCx;
        reduced.juliaCy = this.juliaCy;
        reduced.juliaCz = this.juliaCz;
        reduced.juliaCw = this.juliaCw;
        reduced.sliceW = this.sliceW;
        reduced.rotXW = this.rotXW;
        reduced.rotYW = this.rotYW;
        reduced.rotZW = this.rotZW;
        reduced.maxIterations = Math.max(6, this.maxIterations / reductionFactor);
        applyReducedQuality(reduced, reductionFactor);
        return reduced;
    }

    // Getters and setters

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int v) { this.maxIterations = v; }

    public float getBailout() { return bailout; }
    public void setBailout(float v) { this.bailout = v; }

    public float getJuliaCx() { return juliaCx; }
    public void setJuliaCx(float v) { this.juliaCx = v; }

    public float getJuliaCy() { return juliaCy; }
    public void setJuliaCy(float v) { this.juliaCy = v; }

    public float getJuliaCz() { return juliaCz; }
    public void setJuliaCz(float v) { this.juliaCz = v; }

    public float getJuliaCw() { return juliaCw; }
    public void setJuliaCw(float v) { this.juliaCw = v; }

    public float getSliceW() { return sliceW; }
    public void setSliceW(float v) { this.sliceW = v; }

    public float getRotXW() { return rotXW; }
    public void setRotXW(float v) { this.rotXW = v; }

    public float getRotYW() { return rotYW; }
    public void setRotYW(float v) { this.rotYW = v; }

    public float getRotZW() { return rotZW; }
    public void setRotZW(float v) { this.rotZW = v; }

    // Presets

    public static QuaternionJulia4DParams classicPreset() {
        return new QuaternionJulia4DParams(); // defaults
    }

    public static QuaternionJulia4DParams flowerPreset() {
        QuaternionJulia4DParams p = new QuaternionJulia4DParams();
        p.juliaCx = -0.08f;
        p.juliaCy = -0.8f;
        p.juliaCz = -0.22f;
        p.juliaCw = 0.03f;
        p.sliceW = 0.15f;
        p.rotXW = 25.0f;
        p.rotYW = 10.0f;
        p.maxIterations = 14;
        return p;
    }

    public static QuaternionJulia4DParams wormholePreset() {
        QuaternionJulia4DParams p = new QuaternionJulia4DParams();
        p.juliaCx = -0.125f;
        p.juliaCy = -0.256f;
        p.juliaCz = 0.847f;
        p.juliaCw = 0.0895f;
        p.sliceW = 0.3f;
        p.rotXW = 45.0f;
        p.rotYW = -30.0f;
        p.rotZW = 15.0f;
        p.maxIterations = 14;
        return p;
    }

    public static QuaternionJulia4DParams crystalPreset() {
        QuaternionJulia4DParams p = new QuaternionJulia4DParams();
        p.juliaCx = -0.291f;
        p.juliaCy = -0.399f;
        p.juliaCz = 0.339f;
        p.juliaCw = 0.437f;
        p.sliceW = -0.2f;
        p.rotXW = 60.0f;
        p.rotZW = 20.0f;
        p.maxIterations = 15;
        return p;
    }

    public static QuaternionJulia4DParams hyperspherePreset() {
        QuaternionJulia4DParams p = new QuaternionJulia4DParams();
        p.juliaCx = -0.4f;
        p.juliaCy = 0.6f;
        p.juliaCz = 0.2f;
        p.juliaCw = -0.1f;
        p.sliceW = 0.5f;
        p.rotXW = 90.0f;
        p.rotYW = 45.0f;
        p.rotZW = -45.0f;
        p.maxIterations = 12;
        return p;
    }
}
