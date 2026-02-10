package org.fractalizer.fractals;

/**
 * Parameters specific to Pseudo-Kleinian fractal rendering.
 * Based on Knighty's Fragmentarium implementation.
 * The foldC parameter (Julia-like constant) drives the fractal structure.
 */
public class PseudoKleinianParams extends AbstractFractalParams {

    @Animatable(display = "Iterations")
    private int maxIterations;

    @Animatable(display = "CSize X")
    private float cSizeX;

    @Animatable(display = "CSize Y")
    private float cSizeY;

    @Animatable(display = "CSize Z")
    private float cSizeZ;

    @Animatable(display = "Size")
    private float size;

    @Animatable(display = "DE Offset")
    private float deOffset;

    @Animatable(display = "Fold Cx")
    private float foldCx;

    @Animatable(display = "Fold Cy")
    private float foldCy;

    @Animatable(display = "Fold Cz")
    private float foldCz;

    public PseudoKleinianParams() {
        super();
        this.maxIterations = 7;
        this.cSizeX = 1.0f;
        this.cSizeY = 1.0f;
        this.cSizeZ = 1.3f;
        this.size = 1.0f;
        this.deOffset = 0.0f;
        this.foldCx = -0.62f;
        this.foldCy = -0.015f;
        this.foldCz = -0.025f;

        this.epsilon = 0.001f;
        camera.setPosition(0f, 0f, 0.5f);
    }

    @Override
    public FractalType getType() {
        return FractalType.PSEUDO_KLEINIAN;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        PseudoKleinianParams reduced = new PseudoKleinianParams();
        copyCommonParams(reduced);
        reduced.cSizeX = this.cSizeX;
        reduced.cSizeY = this.cSizeY;
        reduced.cSizeZ = this.cSizeZ;
        reduced.size = this.size;
        reduced.deOffset = this.deOffset;
        reduced.foldCx = this.foldCx;
        reduced.foldCy = this.foldCy;
        reduced.foldCz = this.foldCz;
        reduced.maxIterations = Math.max(5, this.maxIterations / reductionFactor);
        applyReducedQuality(reduced, reductionFactor);
        return reduced;
    }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int iterations) { this.maxIterations = iterations; }

    public float getCSizeX() { return cSizeX; }
    public void setCSizeX(float v) { this.cSizeX = v; }

    public float getCSizeY() { return cSizeY; }
    public void setCSizeY(float v) { this.cSizeY = v; }

    public float getCSizeZ() { return cSizeZ; }
    public void setCSizeZ(float v) { this.cSizeZ = v; }

    public float getSize() { return size; }
    public void setSize(float size) { this.size = size; }

    public float getDEOffset() { return deOffset; }
    public void setDEOffset(float offset) { this.deOffset = offset; }

    public float getFoldCx() { return foldCx; }
    public void setFoldCx(float v) { this.foldCx = v; }

    public float getFoldCy() { return foldCy; }
    public void setFoldCy(float v) { this.foldCy = v; }

    public float getFoldCz() { return foldCz; }
    public void setFoldCz(float v) { this.foldCz = v; }
}
