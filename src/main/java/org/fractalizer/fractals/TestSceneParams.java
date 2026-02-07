package org.fractalizer.fractals;

/**
 * Parameters for Test Scene (SDF Primitives Showcase).
 * A non-fractal scene with various SDF primitives for testing rendering effects.
 */
public class TestSceneParams extends AbstractFractalParams {

    @Animatable(display = "Scene Scale")
    private float sceneScale;

    public TestSceneParams() {
        super();
        this.sceneScale = 1.0f;

        // Override camera to a good viewing position for the scene
        camera.setPosition(0f, 1.5f, -5f);
    }

    @Override
    public FractalType getType() {
        return FractalType.TEST_SCENE;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        TestSceneParams reduced = new TestSceneParams();
        copyCommonParams(reduced);
        reduced.sceneScale = this.sceneScale;
        applyReducedQuality(reduced, reductionFactor);
        return reduced;
    }

    // Getters / Setters
    public float getSceneScale() { return sceneScale; }
    public void setSceneScale(float sceneScale) { this.sceneScale = sceneScale; }
}
