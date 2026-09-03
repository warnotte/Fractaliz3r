package org.fractalizer.test;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.*;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

/**
 * Generates a gallery of animated scenes and renders thumbnail previews.
 * Uses SceneBuilder for quick scene creation and HeadlessRenderer for rendering.
 *
 * Usage:
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.SceneGallery"
 */
public class SceneGallery {

    private static final String OUTPUT_DIR = "out/test_output/gallery";
    private static final int THUMB_W = 480;
    private static final int THUMB_H = 270;
    private static final int THUMB_SAMPLES = 2;
    private static final int PREVIEW_SAMPLES = 4;

    private static GLSLFractalizerController controller;

    public static void main(String[] args) throws Exception {
        // Init JavaFX
        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        File outDir = new File(OUTPUT_DIR);
        if (!outDir.exists()) outDir.mkdirs();

        System.out.println("=== Scene Gallery Generator ===\n");

        // Init controller
        controller = new GLSLFractalizerController();
        controller.loadAllShaders((msg, progress) ->
            System.out.printf("  [%.0f%%] %s%n", progress * 100, msg));
        System.out.println();

        // Generate all scenes
        List<Scene> scenes = generateScenes();

        for (Scene scene : scenes) {
            System.out.printf("--- %s ---%n", scene.name);

            // Write .frac
            File fracFile = new File(outDir, scene.name + ".frac");
            FractalConfigManager.save(scene.config, fracFile);
            System.out.printf("  Written: %s%n", fracFile.getName());

            // Render key frames
            renderKeyFrames(scene, outDir);
        }

        System.out.printf("%n=== Gallery complete: %d scenes in %s ===%n", scenes.size(), outDir.getAbsolutePath());
        System.exit(0);
    }

    // ========================================================================
    // Scene definitions — this is where the magic happens
    // ========================================================================

    private static List<Scene> generateScenes() {
        List<Scene> scenes = new ArrayList<>();

        // 1. Mandelbulb Power Sweep (proven working)
        scenes.add(new Scene("01_power_sweep",
            SceneBuilder.mandelbulb()
                .name("Power Sweep")
                .camera(0, 0, -3).lookAt(0, 0, 0)
                .duration(6).fps(30)
                .track("mandelbulb.power").key(0, 3.0).key(3, 8.0).key(6, 3.0).done()
                .build(),
            new int[]{0, 45, 90, 135, 180}
        ));

        // 2. Mandelbulb → Menger Morph
        scenes.add(new Scene("02_morph_mandelbulb_menger",
            SceneBuilder.mandelbulb()
                .name("Morph: Mandelbulb → Menger")
                .camera(0, 0, -3).lookAt(0, 0, 0)
                .morph("menger")
                .duration(6).fps(30)
                .track("boolBlend").key(0, 0.0).key(3, 1.0).key(6, 0.0).done()
                .build(),
            new int[]{0, 45, 90, 135, 180}
        ));

        // 3. Mandelbulb with camera orbit (testing lookAt)
        scenes.add(new Scene("03_orbit",
            SceneBuilder.mandelbulb()
                .name("Camera Orbit")
                .orbitCamera(new float[]{0, 0, 0}, 3.0f, 0.3f, 0, 180, 6.0)
                .duration(6).fps(30)
                .build(),
            new int[]{0, 45, 90, 135, 180}
        ));

        // 4. Menger Sponge with gentle twist
        scenes.add(new Scene("04_menger_twist",
            SceneBuilder.menger()
                .name("Menger Twist")
                .camera(0, 0, -4).lookAt(0, 0, 0)
                .twist(0, 0.5f)
                .duration(6).fps(30)
                .track("distortionStrength").key(0, 0.0).key(3, 0.2).key(6, 0.0).done()
                .track("distortionOffset").lin(0, 0.0).lin(6, 3.0).done()
                .build(),
            new int[]{0, 45, 90, 135, 180}
        ));

        // 5. Quaternion Julia 4D slice walk
        scenes.add(new Scene("05_julia4d_slice",
            SceneBuilder.quaternionJulia()
                .name("Julia 4D Slice Walk")
                .camera(0, 0, -3).lookAt(0, 0, 0)
                .duration(8).fps(30)
                .track("quaternionjulia4d.sliceW").lin(0, -0.5).lin(4, 0.5).lin(8, -0.5).done()
                .build(),
            new int[]{0, 60, 120, 180, 240}
        ));

        // 6. Mandelbulb with erosion (gentle)
        scenes.add(new Scene("06_erosion",
            SceneBuilder.mandelbulb()
                .name("Erosion Growth")
                .camera(0, 0, -3).lookAt(0, 0, 0)
                .erosion(0, 0, 1)
                .duration(8).fps(30)
                .track("erosionStrength").lin(0, 0.0).lin(8, 0.12).done()
                .track("erosionTime").lin(0, 0.0).lin(8, 4.0).done()
                .build(),
            new int[]{0, 60, 120, 180, 240}
        ));

        // 7. Sierpinski with crystal growth
        scenes.add(new Scene("07_crystal_growth",
            SceneBuilder.sierpinski()
                .name("Sierpinski Crystal")
                .camera(0, 0.5f, -4).lookAt(0, 0, 0)
                .crystal(0, 0, 1)
                .duration(6).fps(30)
                .track("crystalStrength").lin(0, 0.0).lin(6, 0.3).done()
                .track("crystalTime").lin(0, 0.0).lin(6, 3.0).done()
                .build(),
            new int[]{0, 45, 90, 135, 180}
        ));

        // 8. Mandelbulb orbit + power change + twist — the showcase
        scenes.add(new Scene("08_showcase",
            SceneBuilder.mandelbulb()
                .name("Showcase: Orbit + Power + Twist")
                .orbitCamera(new float[]{0, 0, 0}, 3.0f, 0.2f, 0, 120, 8.0)
                .twist(0, 0.6f)
                .duration(8).fps(30)
                .track("mandelbulb.power").key(0, 4.0).key(4, 8.0).key(8, 5.0).done()
                .track("distortionStrength").key(0, 0.0).key(4, 0.15).key(8, 0.0).done()
                .build(),
            new int[]{0, 60, 120, 180, 240}
        ));

        // 9. Infinite Mandelbulbs (Repetition 3D)
        scenes.add(new Scene("09_infinite_grid",
            SceneBuilder.mandelbulb()
                .name("Infinite Mandelbulb Grid")
                .camera(0, 1.5f, -5f).lookAt(0, 0, 0)
                .repetition3D(0.25f)
                .param("power", 8.0)
                .param("maxIterations", 10)
                .duration(6).fps(30)
                .build(),
            new int[]{0}
        ));

        // 10. Mandelbox with bend distortion
        scenes.add(new Scene("10_mandelbox_bend",
            SceneBuilder.mandelbox()
                .name("Mandelbox Bend")
                .camera(0, 0, -6).lookAt(0, 0, 0)
                .bend(0, 0.3f)
                .duration(6).fps(30)
                .track("distortionStrength").key(0, 0.0).key(3, 0.15).key(6, 0.0).done()
                .build(),
            new int[]{0, 45, 90, 135, 180}
        ));

        return scenes;
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    private static void renderKeyFrames(Scene scene, File outDir) throws Exception {
        FractalConfig config = scene.config;
        FractalType type = config.getFractalTypeEnum();

        controller.setFractalType(type);
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        config.applyTo(params);

        if (params.getCustomGradient() != null) {
            controller.updatePaletteTexture(params.getCustomGradient());
        }

        // Build timeline if animation present
        org.fractalizer.animation.Timeline timeline = null;
        if (config.animation != null && config.animation.tracks != null) {
            timeline = HeadlessRenderer.buildTimeline(config.animation);
        }

        controller.setExportSize(THUMB_W, THUMB_H);

        for (int frame : scene.keyFrames) {
            if (timeline != null) {
                double time = frame / config.animation.frameRate;
                timeline.setCurrentTime(time);
                HeadlessRenderer.applyTimelineToParams(timeline, params);
            }

            String filename = String.format("%s_f%03d.png", scene.name, frame);
            File file = new File(outDir, filename);
            long start = System.currentTimeMillis();

            CompletableFuture<Void> future = controller.exportToPNG(
                file, THUMB_SAMPLES, p -> {}, () -> false);
            future.get();

            long ms = System.currentTimeMillis() - start;
            System.out.printf("  [%3dms] %s%n", ms, filename);
        }
    }

    // ========================================================================
    // Scene record
    // ========================================================================

    record Scene(String name, FractalConfig config, int[] keyFrames) {}
}
