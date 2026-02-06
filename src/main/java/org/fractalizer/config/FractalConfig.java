package org.fractalizer.config;

import javafx.scene.paint.Color;
import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    // Animation (optional - only present if animation has keyframes)
    public AnimationConfig animation;

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
        public int paletteIndex = 0;
        public float colorStrength = 1.0f;
        public float paletteOffset = 0.0f;
        public int type = 0;  // 0=Lambertian, 1=Metallic, 2=Glass
        public float metalness = 0.9f;
        public float ior = 1.5f;
        public List<GradientStopConfig> gradientStops;
    }

    public static class GradientStopConfig {
        public double position;
        public double r, g, b;

        public GradientStopConfig() {}

        public GradientStopConfig(double position, double r, double g, double b) {
            this.position = position;
            this.r = r;
            this.g = g;
            this.b = b;
        }
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
        
        // Procedural Sky
        public int skyType = 0;
        public float cloudDensity = 0.5f;
        public float skySpeed = 1.0f;
        public float skyTime = 0.0f;
        public float skyParallax = 0.25f;
        public int projectionMode = 0;

        // Lens Effects
        public boolean lensEffectsEnabled = false;
        public float lensDirtIntensity = 0.0f;
        public float starburstIntensity = 0.0f;

        // Volumetric Fog
        public boolean volumetricFogEnabled = false;
        public float fogDensity = 0.15f;
        public float[] fogColor = {0.5f, 0.6f, 0.7f};
        public float fogScattering = 0.5f;
        public int fogSteps = 32;

        // Motion Blur
        public float shutterAngle = 180f;
    }

    public static class AnimationConfig {
        public double duration = 10.0;
        public double frameRate = 30.0;
        public boolean looping = false;
        public List<TrackConfig> tracks = new ArrayList<>();
    }

    public static class TrackConfig {
        public String name;
        public String valueType;  // "Float", "Double", "Integer", "float[]"
        public Object defaultValue;
        public List<KeyframeConfig> keyframes = new ArrayList<>();
    }

    public static class KeyframeConfig {
        public double time;
        public Object value;
        public String easing = "LINEAR";
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
        config.material.paletteIndex = params.getPaletteIndex();
        config.material.colorStrength = params.getColorStrength();
        config.material.paletteOffset = params.getPaletteOffset();
        config.material.type = params.getMaterialType();
        config.material.metalness = params.getMetalness();
        config.material.ior = params.getIor();

        // Serialize custom gradient stops
        GradientPalette gradient = params.getCustomGradient();
        if (gradient != null) {
            config.material.gradientStops = new ArrayList<>();
            for (GradientPalette.ColorStop stop : gradient.getStops()) {
                config.material.gradientStops.add(new GradientStopConfig(
                    stop.position(), stop.color().getRed(), stop.color().getGreen(), stop.color().getBlue()
                ));
            }
        }

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
        config.effects.skyType = params.getSkyType();
        config.effects.cloudDensity = params.getCloudDensity();
        config.effects.skySpeed = params.getSkySpeed();
        config.effects.skyTime = params.getSkyTime();
        config.effects.skyParallax = params.getSkyParallax();
        config.effects.projectionMode = params.getProjectionMode();
        config.effects.lensEffectsEnabled = params.isLensEffectsEnabled();
        config.effects.lensDirtIntensity = params.getLensDirtIntensity();
        config.effects.starburstIntensity = params.getStarburstIntensity();
        config.effects.volumetricFogEnabled = params.isVolumetricFogEnabled();
        config.effects.fogDensity = params.getFogDensity();
        config.effects.fogColor = params.getFogColor().clone();
        config.effects.fogScattering = params.getFogScattering();
        config.effects.fogSteps = params.getFogSteps();
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
        params.setPaletteIndex(material.paletteIndex);
        params.setColorStrength(material.colorStrength);
        params.setPaletteOffset(material.paletteOffset);
        params.setMaterialType(material.type);
        params.setMetalness(material.metalness);
        params.setIor(material.ior);

        // Deserialize custom gradient stops
        if (material.gradientStops != null && material.gradientStops.size() >= 2) {
            List<GradientPalette.ColorStop> stops = new ArrayList<>();
            for (GradientStopConfig sc : material.gradientStops) {
                stops.add(new GradientPalette.ColorStop(sc.position, Color.color(
                    Math.max(0, Math.min(1, sc.r)),
                    Math.max(0, Math.min(1, sc.g)),
                    Math.max(0, Math.min(1, sc.b))
                )));
            }
            params.setCustomGradient(new GradientPalette(stops));
        }

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
        params.setSkyType(effects.skyType);
        params.setCloudDensity(effects.cloudDensity);
        params.setSkySpeed(effects.skySpeed);
        params.setSkyTime(effects.skyTime);
        params.setSkyParallax(effects.skyParallax);
        params.setProjectionMode(effects.projectionMode);
        params.setLensEffectsEnabled(effects.lensEffectsEnabled);
        params.setLensDirtIntensity(effects.lensDirtIntensity);
        params.setStarburstIntensity(effects.starburstIntensity);
        params.setVolumetricFogEnabled(effects.volumetricFogEnabled);
        params.setFogDensity(effects.fogDensity);
        if (effects.fogColor != null && effects.fogColor.length == 3) {
            params.setFogColor(effects.fogColor[0], effects.fogColor[1], effects.fogColor[2]);
        }
        params.setFogScattering(effects.fogScattering);
        params.setFogSteps(effects.fogSteps);
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
        } else if (params instanceof PolyhedralIFSParams p) {
            map.put("polyType", p.getPolyType().name());
            map.put("maxIterations", p.getMaxIterations());
            map.put("scale", p.getScale());
            map.put("offsetX", p.getOffsetX());
            map.put("offsetY", p.getOffsetY());
            map.put("offsetZ", p.getOffsetZ());
            map.put("shiftX", p.getShiftX());
            map.put("shiftY", p.getShiftY());
            map.put("shiftZ", p.getShiftZ());
            map.put("rot1X", p.getRot1X());
            map.put("rot1Y", p.getRot1Y());
            map.put("rot1Z", p.getRot1Z());
            map.put("rot2X", p.getRot2X());
            map.put("rot2Y", p.getRot2Y());
            map.put("rot2Z", p.getRot2Z());
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
        } else if (params instanceof PolyhedralIFSParams p) {
            if (map.containsKey("polyType")) p.setPolyType(PolyhedralIFSParams.PolyType.valueOf((String)map.get("polyType")));
            if (map.containsKey("maxIterations")) p.setMaxIterations(getInt(map, "maxIterations"));
            if (map.containsKey("scale")) p.setScale(getFloat(map, "scale"));
            if (map.containsKey("offsetX")) p.setOffsetX(getFloat(map, "offsetX"));
            if (map.containsKey("offsetY")) p.setOffsetY(getFloat(map, "offsetY"));
            if (map.containsKey("offsetZ")) p.setOffsetZ(getFloat(map, "offsetZ"));
            if (map.containsKey("shiftX")) p.setShiftX(getFloat(map, "shiftX"));
            if (map.containsKey("shiftY")) p.setShiftY(getFloat(map, "shiftY"));
            if (map.containsKey("shiftZ")) p.setShiftZ(getFloat(map, "shiftZ"));
            if (map.containsKey("rot1X")) p.setRot1X(getFloat(map, "rot1X"));
            if (map.containsKey("rot1Y")) p.setRot1Y(getFloat(map, "rot1Y"));
            if (map.containsKey("rot1Z")) p.setRot1Z(getFloat(map, "rot1Z"));
            if (map.containsKey("rot2X")) p.setRot2X(getFloat(map, "rot2X"));
            if (map.containsKey("rot2Y")) p.setRot2Y(getFloat(map, "rot2Y"));
            if (map.containsKey("rot2Z")) p.setRot2Z(getFloat(map, "rot2Z"));
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