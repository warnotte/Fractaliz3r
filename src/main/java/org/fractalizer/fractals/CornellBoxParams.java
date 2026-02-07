package org.fractalizer.fractals;

/**
 * Parameters for the Cornell Box scene.
 * A classic test scene with colored walls, area light, glass sphere, and metal sphere.
 * Designed to showcase path tracing caustics via per-object materials.
 */
public class CornellBoxParams extends AbstractFractalParams {

    @Animatable(display = "Scene Scale")
    private float sceneScale;

    // Glass sphere position and size
    @Animatable(display = "Glass Sphere X")
    private float glassSphereX;
    @Animatable(display = "Glass Sphere Y")
    private float glassSphereY;
    @Animatable(display = "Glass Sphere Z")
    private float glassSphereZ;
    @Animatable(display = "Glass Sphere Radius")
    private float glassSphereRadius;

    // Metal sphere position and size
    @Animatable(display = "Metal Sphere X")
    private float metalSphereX;
    @Animatable(display = "Metal Sphere Y")
    private float metalSphereY;
    @Animatable(display = "Metal Sphere Z")
    private float metalSphereZ;
    @Animatable(display = "Metal Sphere Radius")
    private float metalSphereRadius;

    // Light panel position and size
    @Animatable(display = "Light Panel X")
    private float lightPanelX;
    @Animatable(display = "Light Panel Y")
    private float lightPanelY;
    @Animatable(display = "Light Panel Z")
    private float lightPanelZ;
    @Animatable(display = "Light Panel Width")
    private float lightPanelW;
    @Animatable(display = "Light Panel Depth")
    private float lightPanelD;

    public CornellBoxParams() {
        super();
        this.sceneScale = 1.0f;

        // Glass sphere defaults
        this.glassSphereX = -0.35f;
        this.glassSphereY = 0.4f;
        this.glassSphereZ = 1.2f;
        this.glassSphereRadius = 0.4f;

        // Metal sphere defaults
        this.metalSphereX = 0.35f;
        this.metalSphereY = 0.3f;
        this.metalSphereZ = 0.7f;
        this.metalSphereRadius = 0.3f;

        // Light panel defaults
        this.lightPanelX = 0.0f;
        this.lightPanelY = 1.99f;
        this.lightPanelZ = 1.0f;
        this.lightPanelW = 0.3f;
        this.lightPanelD = 0.3f;

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
        reduced.glassSphereX = this.glassSphereX;
        reduced.glassSphereY = this.glassSphereY;
        reduced.glassSphereZ = this.glassSphereZ;
        reduced.glassSphereRadius = this.glassSphereRadius;
        reduced.metalSphereX = this.metalSphereX;
        reduced.metalSphereY = this.metalSphereY;
        reduced.metalSphereZ = this.metalSphereZ;
        reduced.metalSphereRadius = this.metalSphereRadius;
        reduced.lightPanelX = this.lightPanelX;
        reduced.lightPanelY = this.lightPanelY;
        reduced.lightPanelZ = this.lightPanelZ;
        reduced.lightPanelW = this.lightPanelW;
        reduced.lightPanelD = this.lightPanelD;
        applyReducedQuality(reduced, reductionFactor);
        return reduced;
    }

    // Getters / Setters
    public float getSceneScale() { return sceneScale; }
    public void setSceneScale(float sceneScale) { this.sceneScale = sceneScale; }

    public float getGlassSphereX() { return glassSphereX; }
    public void setGlassSphereX(float glassSphereX) { this.glassSphereX = glassSphereX; }

    public float getGlassSphereY() { return glassSphereY; }
    public void setGlassSphereY(float glassSphereY) { this.glassSphereY = glassSphereY; }

    public float getGlassSphereZ() { return glassSphereZ; }
    public void setGlassSphereZ(float glassSphereZ) { this.glassSphereZ = glassSphereZ; }

    public float getGlassSphereRadius() { return glassSphereRadius; }
    public void setGlassSphereRadius(float glassSphereRadius) { this.glassSphereRadius = glassSphereRadius; }

    public float getMetalSphereX() { return metalSphereX; }
    public void setMetalSphereX(float metalSphereX) { this.metalSphereX = metalSphereX; }

    public float getMetalSphereY() { return metalSphereY; }
    public void setMetalSphereY(float metalSphereY) { this.metalSphereY = metalSphereY; }

    public float getMetalSphereZ() { return metalSphereZ; }
    public void setMetalSphereZ(float metalSphereZ) { this.metalSphereZ = metalSphereZ; }

    public float getMetalSphereRadius() { return metalSphereRadius; }
    public void setMetalSphereRadius(float metalSphereRadius) { this.metalSphereRadius = metalSphereRadius; }

    public float getLightPanelX() { return lightPanelX; }
    public void setLightPanelX(float lightPanelX) { this.lightPanelX = lightPanelX; }

    public float getLightPanelY() { return lightPanelY; }
    public void setLightPanelY(float lightPanelY) { this.lightPanelY = lightPanelY; }

    public float getLightPanelZ() { return lightPanelZ; }
    public void setLightPanelZ(float lightPanelZ) { this.lightPanelZ = lightPanelZ; }

    public float getLightPanelW() { return lightPanelW; }
    public void setLightPanelW(float lightPanelW) { this.lightPanelW = lightPanelW; }

    public float getLightPanelD() { return lightPanelD; }
    public void setLightPanelD(float lightPanelD) { this.lightPanelD = lightPanelD; }
}
