package org.fractalizer.fractals;

/**
 * Parameters for the Cornell Box scene.
 * A classic test scene with colored walls, area light, glass sphere, and metal sphere.
 * Designed to showcase path tracing caustics via per-object materials.
 */
public class CornellBoxParams extends AbstractFractalParams {

    @Animatable(display = "Scene Scale")
    private float sceneScale;

    public CornellBoxParams() {
        super();
        this.sceneScale = 1.0f;

        // Camera inside the box, looking inward
        camera.setPosition(0f, 1f, -1f);
    }

    @Override
    public FractalType getType() {
        return FractalType.CORNELL_BOX;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        CornellBoxParams reduced = new CornellBoxParams();
        copyCommonParams(reduced);
        reduced.sceneScale = this.sceneScale;
        applyReducedQuality(reduced, reductionFactor);
        return reduced;
    }

    // Getters / Setters
    public float getSceneScale() { return sceneScale; }
    public void setSceneScale(float sceneScale) { this.sceneScale = sceneScale; }
}
