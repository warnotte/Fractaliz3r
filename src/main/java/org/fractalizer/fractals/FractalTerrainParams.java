package org.fractalizer.fractals;

/**
 * Parameters for Fractal Terrain rendering.
 * Uses fBm noise as a heightfield terrain, raymarched with the standard pipeline.
 */
public class FractalTerrainParams extends AbstractFractalParams {

    @Animatable(display = "Terrain Height")
    private float terrainHeight;

    @Animatable(display = "Terrain Frequency")
    private float terrainFrequency;

    @Animatable(display = "Octaves")
    private int octaves;

    @Animatable(display = "Lacunarity")
    private float lacunarity;

    @Animatable(display = "Roughness")
    private float roughness;

    @Animatable(display = "Warp Strength")
    private float warpStrength;

    @Animatable(display = "Ridge Sharpness")
    private float ridgeSharpness;

    @Animatable(display = "Terrain Offset")
    private float terrainOffset;

    public FractalTerrainParams() {
        super();
        this.terrainHeight = 2.0f;
        this.terrainFrequency = 0.5f;
        this.octaves = 8;
        this.lacunarity = 2.0f;
        this.roughness = 0.5f;
        this.warpStrength = 0.0f;
        this.ridgeSharpness = 0.0f;
        this.terrainOffset = 0.0f;

        // Elevated camera looking at horizon
        camera.setPosition(0, 3, -5);
        setMaxRaySteps(300);
        setEpsilon(0.001f);
    }

    @Override
    public FractalType getType() {
        return FractalType.FRACTAL_TERRAIN;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        FractalTerrainParams reduced = new FractalTerrainParams();
        copyCommonParams(reduced);

        reduced.terrainHeight = this.terrainHeight;
        reduced.terrainFrequency = this.terrainFrequency;
        reduced.lacunarity = this.lacunarity;
        reduced.roughness = this.roughness;
        reduced.warpStrength = this.warpStrength;
        reduced.ridgeSharpness = this.ridgeSharpness;
        reduced.terrainOffset = this.terrainOffset;

        reduced.octaves = Math.max(2, this.octaves / reductionFactor);
        applyReducedQuality(reduced, reductionFactor);

        return reduced;
    }

    // Getters and setters

    public float getTerrainHeight() { return terrainHeight; }
    public void setTerrainHeight(float terrainHeight) { this.terrainHeight = terrainHeight; }

    public float getTerrainFrequency() { return terrainFrequency; }
    public void setTerrainFrequency(float terrainFrequency) { this.terrainFrequency = terrainFrequency; }

    public int getOctaves() { return octaves; }
    public void setOctaves(int octaves) { this.octaves = octaves; }

    public float getLacunarity() { return lacunarity; }
    public void setLacunarity(float lacunarity) { this.lacunarity = lacunarity; }

    public float getRoughness() { return roughness; }
    public void setRoughness(float roughness) { this.roughness = roughness; }

    public float getWarpStrength() { return warpStrength; }
    public void setWarpStrength(float warpStrength) { this.warpStrength = warpStrength; }

    public float getRidgeSharpness() { return ridgeSharpness; }
    public void setRidgeSharpness(float ridgeSharpness) { this.ridgeSharpness = ridgeSharpness; }

    public float getTerrainOffset() { return terrainOffset; }
    public void setTerrainOffset(float terrainOffset) { this.terrainOffset = terrainOffset; }

    // Presets

    public static FractalTerrainParams rollingHillsPreset() {
        FractalTerrainParams p = new FractalTerrainParams();
        p.terrainHeight = 1.5f;
        p.terrainFrequency = 0.3f;
        p.octaves = 6;
        p.lacunarity = 2.0f;
        p.roughness = 0.45f;
        p.warpStrength = 0.0f;
        p.ridgeSharpness = 0.0f;
        p.terrainOffset = 0.0f;
        return p;
    }

    public static FractalTerrainParams mountainsPreset() {
        FractalTerrainParams p = new FractalTerrainParams();
        p.terrainHeight = 4.0f;
        p.terrainFrequency = 0.4f;
        p.octaves = 10;
        p.lacunarity = 2.1f;
        p.roughness = 0.55f;
        p.warpStrength = 0.3f;
        p.ridgeSharpness = 0.8f;
        p.terrainOffset = -0.5f;
        return p;
    }

    public static FractalTerrainParams canyonsPreset() {
        FractalTerrainParams p = new FractalTerrainParams();
        p.terrainHeight = 3.0f;
        p.terrainFrequency = 0.6f;
        p.octaves = 8;
        p.lacunarity = 2.5f;
        p.roughness = 0.6f;
        p.warpStrength = 1.2f;
        p.ridgeSharpness = 0.5f;
        p.terrainOffset = -1.0f;
        return p;
    }

    public static FractalTerrainParams alienPreset() {
        FractalTerrainParams p = new FractalTerrainParams();
        p.terrainHeight = 5.0f;
        p.terrainFrequency = 0.8f;
        p.octaves = 7;
        p.lacunarity = 3.0f;
        p.roughness = 0.7f;
        p.warpStrength = 1.8f;
        p.ridgeSharpness = 0.3f;
        p.terrainOffset = 0.5f;
        return p;
    }
}
