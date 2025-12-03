package org.fractalizer.config;

import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Complete fractal configuration for save/load.
 * Designed for clean JSON serialization with Gson.
 */
public class FractalConfig {

    // Metadata
    public String version = "1.0";
    public String timestamp;
    public String name;
    public String description;

    // Fractal type
    public String fractalType;

    // Camera
    public CameraConfig camera = new CameraConfig();

    // Common rendering parameters
    public RenderingConfig rendering = new RenderingConfig();

    // Lighting
    public LightingConfig lighting = new LightingConfig();

    // Material
    public MaterialConfig material = new MaterialConfig();

    // Effects (DoF, Path Tracing, etc.)
    public EffectsConfig effects = new EffectsConfig();

    // Fractal-specific parameters (dynamic based on type)
    public Map<String, Object> fractalParams = new HashMap<>();

    // ========================================================================
    // Nested configuration classes
    // ========================================================================

    public static class CameraConfig {
        public float[] position = {0, 0, -3};
        public float[] quaternion = {1, 0, 0, 0};  // w, x, y, z
        public float fov = 60;  // degrees
        public float moveSpeed = 0.1f;
    }

    public static class RenderingConfig {
        public int maxRaySteps = 200;
        public float epsilon = 0.0001f;
        public float qualityMultiplier = 1.0f;
        public int renderMode = 0;

        // Shadows
        public float shadowSoftness = 16f;
        public int shadowSteps = 128;

        // AO
        public int aoSteps = 5;
        public float aoIntensity = 0.5f;

        // Glow
        public float glowIntensity = 0.15f;

        // Specular
        public float specularIntensity = 0.5f;
        public float specularPower = 32f;
    }

    public static class LightingConfig {
        public float[] direction = {2, 3, -2};
        public float[] color = {1, 0.95f, 0.9f};
        public float intensity = 1.2f;

        public float[] ambientColor = {0.1f, 0.15f, 0.25f};
        public float ambientIntensity = 0.3f;
    }

    public static class MaterialConfig {
        public float[] hue = {0, 0.33f, 0.67f};
        public int type = 0;  // 0=Lambertian, 1=Metallic, 2=Glass
        public float metalness = 0.9f;
        public float ior = 1.5f;
    }

    public static class EffectsConfig {
        // DoF
        public boolean dofEnabled = false;
        public float focalDistance = 2.5f;
        public float aperture = 0.02f;
        public int dofSamples = 16;

        // Path Tracing
        public boolean pathTracingEnabled = false;
        public int maxBounces = 4;
        public float roughness = 0.5f;
        public float skyIntensity = 1.0f;
        public float indirectMultiplier = 0.5f;

        // Motion Blur
        public float shutterAngle = 180f;
    }

    // ========================================================================
    // Factory methods
    // ========================================================================

    /**
     * Create a config from current parameters.
     */
    public static FractalConfig fromParams(AbstractFractalParams params) {
        FractalConfig config = new FractalConfig();
        config.timestamp = Instant.now().toString();
        config.fractalType = params.getType().name();

        // Camera
        Camera cam = params.getCamera();
        float[] pos = cam.getPosition();
        float[] quat = cam.getQuaternion();
        config.camera.position = new float[]{pos[0], pos[1], pos[2]};
        config.camera.quaternion = new float[]{quat[0], quat[1], quat[2], quat[3]};
        config.camera.fov = (float) Math.toDegrees(params.getFov());
        config.camera.moveSpeed = cam.getMoveSpeed();

        // Rendering
        config.rendering.maxRaySteps = params.getMaxRaySteps();
        config.rendering.epsilon = params.getEpsilon();
        config.rendering.qualityMultiplier = params.getQualityMultiplier();
        config.rendering.renderMode = params.getRenderMode();
        config.rendering.shadowSoftness = params.getShadowSoftness();
        config.rendering.shadowSteps = params.getShadowSteps();
        config.rendering.aoSteps = params.getAoSteps();
        config.rendering.aoIntensity = params.getAoIntensity();
        config.rendering.glowIntensity = params.getGlowIntensity();
        config.rendering.specularIntensity = params.getSpecularIntensity();
        config.rendering.specularPower = params.getSpecularPower();

        // Lighting
        config.lighting.direction = new float[]{params.getLightX(), params.getLightY(), params.getLightZ()};
        config.lighting.color = new float[]{params.getLightR(), params.getLightG(), params.getLightB()};
        config.lighting.intensity = params.getLightIntensity();
        config.lighting.ambientColor = new float[]{params.getAmbientR(), params.getAmbientG(), params.getAmbientB()};
        config.lighting.ambientIntensity = params.getAmbientIntensity();

        // Material
        config.material.hue = new float[]{params.getHueR(), params.getHueG(), params.getHueB()};
        config.material.type = params.getMaterialType();
        config.material.metalness = params.getMetalness();
        config.material.ior = params.getIor();

        // Effects
        config.effects.dofEnabled = params.isDofEnabled();
        config.effects.focalDistance = params.getFocalDistance();
        config.effects.aperture = params.getAperture();
        config.effects.dofSamples = params.getDofSamples();
        config.effects.pathTracingEnabled = params.isPathTracingEnabled();
        config.effects.maxBounces = params.getMaxBounces();
        config.effects.roughness = params.getRoughness();
        config.effects.skyIntensity = params.getSkyIntensity();
        config.effects.indirectMultiplier = params.getIndirectMultiplier();
        config.effects.shutterAngle = params.getShutterAngle();

        // Fractal-specific parameters
        extractFractalParams(params, config.fractalParams);

        return config;
    }

    /**
     * Apply this config to parameters.
     */
    public void applyTo(AbstractFractalParams params) {
        // Camera
        Camera cam = params.getCamera();
        cam.setPosition(camera.position[0], camera.position[1], camera.position[2]);
        cam.setQuaternion(camera.quaternion[0], camera.quaternion[1],
                          camera.quaternion[2], camera.quaternion[3]);
        params.setFovDegrees(camera.fov);
        cam.setMoveSpeed(camera.moveSpeed);

        // Rendering
        params.setMaxRaySteps(rendering.maxRaySteps);
        params.setEpsilon(rendering.epsilon);
        params.setQualityMultiplier(rendering.qualityMultiplier);
        params.setRenderMode(rendering.renderMode);
        params.setShadowSoftness(rendering.shadowSoftness);
        params.setShadowSteps(rendering.shadowSteps);
        params.setAoSteps(rendering.aoSteps);
        params.setAoIntensity(rendering.aoIntensity);
        params.setGlowIntensity(rendering.glowIntensity);
        params.setSpecularIntensity(rendering.specularIntensity);
        params.setSpecularPower(rendering.specularPower);

        // Lighting
        params.setLightDirection(lighting.direction[0], lighting.direction[1], lighting.direction[2]);
        params.setLightColor(lighting.color[0], lighting.color[1], lighting.color[2]);
        params.setLightIntensity(lighting.intensity);
        params.setAmbientColor(lighting.ambientColor[0], lighting.ambientColor[1], lighting.ambientColor[2]);
        params.setAmbientIntensity(lighting.ambientIntensity);

        // Material
        params.setMaterialHue(material.hue[0], material.hue[1], material.hue[2]);
        params.setMaterialType(material.type);
        params.setMetalness(material.metalness);
        params.setIor(material.ior);

        // Effects
        params.setDofEnabled(effects.dofEnabled);
        params.setFocalDistance(effects.focalDistance);
        params.setAperture(effects.aperture);
        params.setDofSamples(effects.dofSamples);
        params.setPathTracingEnabled(effects.pathTracingEnabled);
        params.setMaxBounces(effects.maxBounces);
        params.setRoughness(effects.roughness);
        params.setSkyIntensity(effects.skyIntensity);
        params.setIndirectMultiplier(effects.indirectMultiplier);
        params.setShutterAngle(effects.shutterAngle);

        // Fractal-specific parameters
        applyFractalParams(params, fractalParams);
    }

    /**
     * Get the fractal type enum from this config.
     */
    public FractalType getFractalTypeEnum() {
        try {
            return FractalType.valueOf(fractalType);
        } catch (IllegalArgumentException e) {
            return FractalType.MANDELBULB;  // Default fallback
        }
    }

    // ========================================================================
    // Helper methods for fractal-specific params
    // ========================================================================

    private static void extractFractalParams(AbstractFractalParams params, Map<String, Object> map) {
        if (params instanceof MandelbulbParams mb) {
            map.put("power", mb.getPower());
            map.put("maxIterations", mb.getMaxIterations());
            map.put("bailout", mb.getBailout());
        } else if (params instanceof MandelboxParams mbx) {
            map.put("scale", mbx.getScale());
            map.put("minRadius", mbx.getMinRadius());
            map.put("fixedRadius", mbx.getFixedRadius());
            map.put("foldingLimit", mbx.getFoldingLimit());
            map.put("maxIterations", mbx.getMaxIterations());
        } else if (params instanceof MengerSpongeParams ms) {
            map.put("maxIterations", ms.getMaxIterations());
            map.put("scale", ms.getScale());
            map.put("offsetX", ms.getOffsetX());
            map.put("offsetY", ms.getOffsetY());
            map.put("offsetZ", ms.getOffsetZ());
        } else if (params instanceof KaleidoscopicIFSParams k) {
            map.put("maxIterations", k.getMaxIterations());
            map.put("scale", k.getScale());
            map.put("foldAngleX", k.getFoldAngleX());
            map.put("foldAngleY", k.getFoldAngleY());
            map.put("offsetX", k.getOffsetX());
            map.put("offsetY", k.getOffsetY());
            map.put("offsetZ", k.getOffsetZ());
        } else if (params instanceof Julia3DParams j) {
            map.put("maxIterations", j.getMaxIterations());
            map.put("bailout", j.getBailout());
            map.put("juliaCx", j.getJuliaCx());
            map.put("juliaCy", j.getJuliaCy());
            map.put("juliaCz", j.getJuliaCz());
            map.put("juliaCw", j.getJuliaCw());
        } else if (params instanceof PseudoKleinianParams pk) {
            map.put("maxIterations", pk.getMaxIterations());
            map.put("size", pk.getSize());
            map.put("cSizeX", pk.getCSizeX());
            map.put("cSizeY", pk.getCSizeY());
            map.put("cSizeZ", pk.getCSizeZ());
            map.put("juliaX", pk.getJuliaX());
            map.put("juliaY", pk.getJuliaY());
            map.put("juliaZ", pk.getJuliaZ());
            map.put("deOffset", pk.getDeOffset());
            map.put("zOffset", pk.getZOffset());
        }
    }

    private static void applyFractalParams(AbstractFractalParams params, Map<String, Object> map) {
        if (params instanceof MandelbulbParams mb) {
            if (map.containsKey("power")) mb.power(getFloat(map, "power"));
            if (map.containsKey("maxIterations")) mb.iterations(getInt(map, "maxIterations"));
            if (map.containsKey("bailout")) mb.setBailout(getFloat(map, "bailout"));
        } else if (params instanceof MandelboxParams mbx) {
            if (map.containsKey("scale")) mbx.setScale(getFloat(map, "scale"));
            if (map.containsKey("minRadius")) mbx.setMinRadius(getFloat(map, "minRadius"));
            if (map.containsKey("fixedRadius")) mbx.setFixedRadius(getFloat(map, "fixedRadius"));
            if (map.containsKey("foldingLimit")) mbx.setFoldingLimit(getFloat(map, "foldingLimit"));
            if (map.containsKey("maxIterations")) mbx.setMaxIterations(getInt(map, "maxIterations"));
        } else if (params instanceof MengerSpongeParams ms) {
            if (map.containsKey("maxIterations")) ms.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("scale")) ms.setScale(getFloat(map, "scale"));
            if (map.containsKey("offsetX") || map.containsKey("offsetY") || map.containsKey("offsetZ")) {
                ms.setOffset(getFloat(map, "offsetX"), getFloat(map, "offsetY"), getFloat(map, "offsetZ"));
            }
        } else if (params instanceof KaleidoscopicIFSParams k) {
            if (map.containsKey("maxIterations")) k.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("scale")) k.setScale(getFloat(map, "scale"));
            if (map.containsKey("foldAngleX")) k.setFoldAngleX(getFloat(map, "foldAngleX"));
            if (map.containsKey("foldAngleY")) k.setFoldAngleY(getFloat(map, "foldAngleY"));
            if (map.containsKey("offsetX")) k.setOffset(getFloat(map, "offsetX"),
                                                        getFloat(map, "offsetY"),
                                                        getFloat(map, "offsetZ"));
        } else if (params instanceof Julia3DParams j) {
            if (map.containsKey("maxIterations")) j.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("bailout")) j.setBailout(getFloat(map, "bailout"));
            if (map.containsKey("juliaCx")) j.setJuliaCx(getFloat(map, "juliaCx"));
            if (map.containsKey("juliaCy")) j.setJuliaCy(getFloat(map, "juliaCy"));
            if (map.containsKey("juliaCz")) j.setJuliaCz(getFloat(map, "juliaCz"));
            if (map.containsKey("juliaCw")) j.setJuliaCw(getFloat(map, "juliaCw"));
        } else if (params instanceof PseudoKleinianParams pk) {
            if (map.containsKey("maxIterations")) pk.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("size")) pk.setSize(getFloat(map, "size"));
            if (map.containsKey("cSizeX")) pk.setCSizeX(getFloat(map, "cSizeX"));
            if (map.containsKey("cSizeY")) pk.setCSizeY(getFloat(map, "cSizeY"));
            if (map.containsKey("cSizeZ")) pk.setCSizeZ(getFloat(map, "cSizeZ"));
            if (map.containsKey("juliaX")) pk.setJuliaX(getFloat(map, "juliaX"));
            if (map.containsKey("juliaY")) pk.setJuliaY(getFloat(map, "juliaY"));
            if (map.containsKey("juliaZ")) pk.setJuliaZ(getFloat(map, "juliaZ"));
            if (map.containsKey("deOffset")) pk.setDeOffset(getFloat(map, "deOffset"));
            if (map.containsKey("zOffset")) pk.setZOffset(getFloat(map, "zOffset"));
        }
    }

    private static float getFloat(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.floatValue();
        return 0f;
    }

    private static int getInt(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return 0;
    }
}
