package org.fractalizer.test;

import org.fractalizer.animation.AnimationTrack;
import org.fractalizer.animation.Easing;
import org.fractalizer.animation.Timeline;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.*;
import org.fractalizer.graph.NodeGraphAnimationHelper;
import org.fractalizer.graph.NodeGraphAnimationHelper.NodeAnimInfo;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

/**
 * Headless renderer that loads a .frac file and renders still images or animation frames.
 *
 * Usage:
 *   # Render a single still image (frame 0):
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.HeadlessRenderer" \
 *       -Dexec.args="scene.frac output/ still 960x540 8"
 *
 *   # Render specific animation frames:
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.HeadlessRenderer" \
 *       -Dexec.args="scene.frac output/ frames 960x540 4 0,30,60,90"
 *
 *   # Render all animation frames:
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.HeadlessRenderer" \
 *       -Dexec.args="scene.frac output/ animation 960x540 4"
 */
public class HeadlessRenderer {

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.out.println("Usage: HeadlessRenderer <file.frac> <outputDir> <mode> <WxH> <samples> [frameList]");
            System.out.println("  mode: still | frames | animation");
            System.out.println("  frameList: comma-separated frame numbers (for 'frames' mode)");
            System.exit(1);
        }

        String fracFile = args[0];
        String outputDir = args[1];
        String mode = args[2];
        String[] resolution = args[3].split("x");
        int width = Integer.parseInt(resolution[0]);
        int height = Integer.parseInt(resolution[1]);
        int samples = Integer.parseInt(args[4]);

        int[] frameList = null;
        if (mode.equals("frames") && args.length >= 6) {
            String[] parts = args[5].split(",");
            frameList = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                frameList[i] = Integer.parseInt(parts[i].trim());
            }
        }

        // Initialize JavaFX toolkit
        CountDownLatch fxLatch = new CountDownLatch(1);
        Platform.startup(fxLatch::countDown);
        fxLatch.await();

        // Create output directory
        File outDir = new File(outputDir);
        if (!outDir.exists()) outDir.mkdirs();

        System.out.println("=== Headless Renderer ===");
        System.out.printf("File: %s%n", fracFile);
        System.out.printf("Output: %s%n", outDir.getAbsolutePath());
        System.out.printf("Mode: %s, Resolution: %dx%d, Samples: %d%n", mode, width, height, samples);

        // Load .frac config
        FractalConfig config = FractalConfigManager.load(new File(fracFile));
        FractalType fractalType = config.getFractalTypeEnum();
        System.out.printf("Fractal: %s%n", fractalType.getDisplayName());

        // Initialize controller (creates hidden GL window + compiles shaders)
        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((msg, progress) ->
            System.out.printf("  [%.0f%%] %s%n", progress * 100, msg)
        );

        // Set fractal type and apply config
        controller.setFractalType(fractalType);
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        config.applyTo(params);

        // Upload custom gradient texture
        if (params.getCustomGradient() != null) {
            controller.updatePaletteTexture(params.getCustomGradient());
        }

        controller.setExportSize(width, height);

        switch (mode) {
            case "still" -> renderStill(controller, params, outDir, width, height, samples);
            case "frames" -> renderFrames(controller, config, params, outDir, width, height, samples, frameList);
            case "animation" -> renderAnimation(controller, config, params, outDir, width, height, samples);
            default -> System.err.println("Unknown mode: " + mode);
        }

        System.out.println("\n=== Render complete ===");
        System.exit(0);
    }

    private static void renderStill(GLSLFractalizerController controller, AbstractFractalParams params,
                                     File outDir, int width, int height, int samples) throws Exception {
        File file = new File(outDir, "render.png");
        System.out.printf("%nRendering still image (%dx%d, %d samples)...%n", width, height, samples);
        long start = System.currentTimeMillis();

        CompletableFuture<Void> future = controller.exportToPNG(
            file, samples, progress -> {}, () -> false
        );
        future.get();

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("Done in %dms → %s (%dKB)%n", elapsed, file.getName(), file.length() / 1024);
    }

    private static void renderFrames(GLSLFractalizerController controller, FractalConfig config,
                                      AbstractFractalParams params, File outDir,
                                      int width, int height, int samples, int[] frameList) throws Exception {
        if (config.animation == null) {
            System.err.println("No animation data in .frac file!");
            return;
        }
        if (frameList == null || frameList.length == 0) {
            System.err.println("No frame numbers specified!");
            return;
        }

        Timeline timeline = buildTimeline(config.animation);
        double fps = timeline.getFrameRate();

        for (int frameNum : frameList) {
            double time = frameNum / fps;
            System.out.printf("%nFrame %d (t=%.3fs)...%n", frameNum, time);

            timeline.setCurrentTime(time);
            applyTimelineToParams(timeline, params);

            File file = new File(outDir, String.format("frame_%05d.png", frameNum));
            long start = System.currentTimeMillis();

            controller.setExportSize(width, height);
            CompletableFuture<Void> future = controller.exportToPNG(
                file, samples, progress -> {}, () -> false
            );
            future.get();

            long elapsed = System.currentTimeMillis() - start;
            System.out.printf("  Done in %dms → %s (%dKB)%n", elapsed, file.getName(), file.length() / 1024);
        }
    }

    private static void renderAnimation(GLSLFractalizerController controller, FractalConfig config,
                                         AbstractFractalParams params, File outDir,
                                         int width, int height, int samples) throws Exception {
        if (config.animation == null) {
            System.err.println("No animation data in .frac file!");
            return;
        }

        Timeline timeline = buildTimeline(config.animation);
        double fps = timeline.getFrameRate();
        double duration = timeline.getDuration();
        int totalFrames = (int) Math.ceil(duration * fps);

        System.out.printf("Animation: %.1fs @ %.0ffps = %d frames%n", duration, fps, totalFrames);

        long totalStart = System.currentTimeMillis();
        for (int frame = 0; frame < totalFrames; frame++) {
            double time = frame / fps;

            timeline.setCurrentTime(time);
            applyTimelineToParams(timeline, params);

            File file = new File(outDir, String.format("frame_%05d.png", frame));
            long start = System.currentTimeMillis();

            controller.setExportSize(width, height);
            CompletableFuture<Void> future = controller.exportToPNG(
                file, samples, progress -> {}, () -> false
            );
            future.get();

            long elapsed = System.currentTimeMillis() - start;
            double pct = (frame + 1) * 100.0 / totalFrames;
            System.out.printf("  [%.0f%%] Frame %d/%d in %dms%n", pct, frame + 1, totalFrames, elapsed);
        }

        long totalElapsed = System.currentTimeMillis() - totalStart;
        System.out.printf("Total: %d frames in %.1fs (avg %.0fms/frame)%n",
            totalFrames, totalElapsed / 1000.0, (double) totalElapsed / totalFrames);
    }

    // ========================================================================
    // Timeline building and application (headless, no UI deps)
    // ========================================================================

    static Timeline buildTimeline(FractalConfig.AnimationConfig animConfig) {
        Timeline timeline = new Timeline(animConfig.duration, animConfig.frameRate);
        timeline.setLooping(animConfig.looping);

        for (FractalConfig.TrackConfig trackConfig : animConfig.tracks) {
            Class<?> valueType = getTypeFromName(trackConfig.valueType);
            if (valueType == null) continue;

            Object defaultVal = convertValue(trackConfig.defaultValue, valueType);
            @SuppressWarnings("unchecked")
            AnimationTrack<Object> track = (AnimationTrack<Object>)
                timeline.createTrack(trackConfig.name, (Class<Object>) valueType, defaultVal);

            for (FractalConfig.KeyframeConfig kf : trackConfig.keyframes) {
                Easing easing = Easing.LINEAR;
                try { easing = Easing.valueOf(kf.easing); } catch (IllegalArgumentException ignored) {}

                Object value = convertValue(kf.value, valueType);
                track.setKeyframe(kf.time, value, easing);
            }

            track.setSplineInterpolation(trackConfig.splineInterpolation);
        }

        return timeline;
    }

    /**
     * Apply all timeline track values to params. Mirrors AnimationManager.applyTimelineToParams().
     */
    static void applyTimelineToParams(Timeline timeline, AbstractFractalParams params) {
        Camera camera = params.getCamera();

        // Camera position
        applyFloat3Track(timeline, "camPos", (v) -> camera.setPosition(v[0], v[1], v[2]));

        // Camera rotation
        applyFloat3Track(timeline, "camQuat", (v) -> {
            if (v.length >= 4) camera.setQuaternion(v[0], v[1], v[2], v[3]);
        });

        // FOV
        applyFloatTrack(timeline, "fov", params::setFovDegrees);

        // DoF
        applyFloatTrack(timeline, "focalDistance", params::setFocalDistance);
        applyFloatTrack(timeline, "aperture", params::setAperture);

        // Lighting
        applyFloat3Track(timeline, "lightDir", (v) -> params.setLightDirection(v[0], v[1], v[2]));
        applyFloatTrack(timeline, "extraLightIntensity", params::setExtraLightIntensity);
        applyFloatTrack(timeline, "extraLightAreaRadius", params::setExtraLightAreaRadius);

        // Base hue
        applyFloat3Track(timeline, "baseHue", (v) -> params.setMaterialHue(v[0], v[1], v[2]));

        // Erosion
        applyFloatTrack(timeline, "erosionTime", params::setErosionTime);
        applyFloatTrack(timeline, "erosionStrength", params::setErosionStrength);
        applyFloatTrack(timeline, "erosionScale", params::setErosionScale);

        // Crystallization
        applyFloatTrack(timeline, "crystalTime", params::setCrystalTime);
        applyFloatTrack(timeline, "crystalStrength", params::setCrystalStrength);
        applyFloatTrack(timeline, "crystalScale", params::setCrystalScale);

        // Moss
        applyFloatTrack(timeline, "mossTime", params::setMossTime);
        applyFloatTrack(timeline, "mossStrength", params::setMossStrength);
        applyFloatTrack(timeline, "mossScale", params::setMossScale);

        // Ocean
        applyFloatTrack(timeline, "oceanTime", params::setOceanTime);

        // Boolean/Morph blend
        applyFloatTrack(timeline, "boolBlend", params::setBoolBlend);

        // Fractal-specific parameters (via @Animatable reflection)
        String kernelName = params.getKernelName();
        List<AnimatableParameter> descriptors = params.getAnimatableParameters();
        for (AnimatableParameter desc : descriptors) {
            String trackName = desc.trackName(kernelName);
            AnimationTrack<?> track = timeline.getTrack(trackName);
            if (track != null && track.hasKeyframes()) {
                Object value = timeline.getValue(trackName);
                if (value instanceof Number num) {
                    if (desc.valueType() == int.class || desc.valueType() == Integer.class) {
                        desc.setter().accept(num.intValue());
                    } else {
                        desc.setter().accept(num.floatValue());
                    }
                }
            }
        }

        // Node graph parameters (CSG blend, transform strength, per-node fractal params)
        if (params instanceof NodeGraphParams ngp && ngp.getGraphRoot() != null) {
            List<NodeAnimInfo> nodeInfos = NodeGraphAnimationHelper.discoverAnimatableParameters(ngp.getGraphRoot());
            for (NodeAnimInfo info : nodeInfos) {
                for (AnimatableParameter param : info.parameters()) {
                    String trackName = info.nodeName() + "." + param.name();
                    AnimationTrack<?> track = timeline.getTrack(trackName);
                    if (track != null && track.hasKeyframes()) {
                        Object value = timeline.getValue(trackName);
                        if (value instanceof Number num) {
                            param.setter().accept(num.floatValue());
                        }
                    }
                }
            }
            ngp.updateUniforms();
        }
    }

    private static void applyFloatTrack(Timeline timeline, String name, java.util.function.Consumer<Float> setter) {
        AnimationTrack<?> track = timeline.getTrack(name);
        if (track != null && track.hasKeyframes()) {
            Object val = timeline.getValue(name);
            if (val instanceof Number n) setter.accept(n.floatValue());
        }
    }

    private static void applyFloat3Track(Timeline timeline, String name, java.util.function.Consumer<float[]> setter) {
        AnimationTrack<?> track = timeline.getTrack(name);
        if (track != null && track.hasKeyframes()) {
            Object val = timeline.getValue(name);
            if (val instanceof float[] arr) setter.accept(arr);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object convertValue(Object value, Class<?> targetType) {
        if (targetType == Float.class || targetType == float.class) {
            if (value instanceof Number n) return n.floatValue();
            return 0.0f;
        }
        if (targetType == Integer.class || targetType == int.class) {
            if (value instanceof Number n) return n.intValue();
            return 0;
        }
        if (targetType == Double.class || targetType == double.class) {
            if (value instanceof Number n) return n.doubleValue();
            return 0.0;
        }
        if (targetType == float[].class) {
            if (value instanceof List<?> list) {
                float[] arr = new float[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    arr[i] = ((Number) list.get(i)).floatValue();
                }
                return arr;
            }
            if (value instanceof float[] arr) return arr;
            return new float[]{0, 0, 0};
        }
        return value;
    }

    private static Class<?> getTypeFromName(String name) {
        return switch (name) {
            case "Float" -> Float.class;
            case "Integer" -> Integer.class;
            case "Double" -> Double.class;
            case "float[]" -> float[].class;
            default -> null;
        };
    }
}
