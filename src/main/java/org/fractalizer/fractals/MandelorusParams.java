package org.fractalizer.fractals;

public class MandelorusParams extends AbstractFractalParams {

    @Animatable(display = "Iterations") private int maxIterations;
    @Animatable(display = "Bailout") private float bailout;
    @Animatable(display = "Ring Radius") private float ringRadius;
    @Animatable(display = "Twist") private float torusTwist;
    @Animatable(display = "Power") private float power;
    @Animatable(display = "Ring Phase") private float ringPhase;
    @Animatable(display = "Cross Phase") private float crossPhase;
    @Animatable(display = "Vert Scale") private float vertScale;

    public MandelorusParams() {
        super();
        this.maxIterations = 10;
        this.bailout = 4.0f;
        this.ringRadius = 1.0f;
        this.torusTwist = 0.0f;
        this.power = 8.0f;
        this.ringPhase = 0.0f;
        this.crossPhase = 0.0f;
        this.vertScale = 0.0f;
        camera.setPosition(0f, 0f, -4f);
    }

    @Override public FractalType getType() { return FractalType.MANDELORUS; }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        MandelorusParams r = new MandelorusParams();
        copyCommonParams(r);
        r.bailout = this.bailout; r.ringRadius = this.ringRadius;
        r.torusTwist = this.torusTwist; r.power = this.power;
        r.ringPhase = this.ringPhase; r.crossPhase = this.crossPhase;
        r.vertScale = this.vertScale;
        r.maxIterations = Math.max(5, this.maxIterations / reductionFactor);
        applyReducedQuality(r, reductionFactor);
        return r;
    }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int v) { this.maxIterations = v; }
    public float getBailout() { return bailout; }
    public void setBailout(float v) { this.bailout = v; }
    public float getRingRadius() { return ringRadius; }
    public void setRingRadius(float v) { this.ringRadius = v; }
    public float getTorusTwist() { return torusTwist; }
    public void setTorusTwist(float v) { this.torusTwist = v; }
    public float getPower() { return power; }
    public void setPower(float v) { this.power = v; }
    public float getRingPhase() { return ringPhase; }
    public void setRingPhase(float v) { this.ringPhase = v; }
    public float getCrossPhase() { return crossPhase; }
    public void setCrossPhase(float v) { this.crossPhase = v; }
    public float getVertScale() { return vertScale; }
    public void setVertScale(float v) { this.vertScale = v; }
}
