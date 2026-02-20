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
 * Training round 2: more ambitious scenes testing untested features.
 * Goal: expand coverage, learn new parameter ranges, push boundaries.
 */
public class SceneGallery2 {

    private static final String OUTPUT_DIR = "test_output/gallery2";
    private static final int W = 480;
    private static final int H = 270;
    private static final int SAMPLES = 2;

    private static GLSLFractalizerController controller;

    public static void main(String[] args) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        File outDir = new File(OUTPUT_DIR);
        if (!outDir.exists()) outDir.mkdirs();

        System.out.println("=== Scene Gallery 2: Training Round ===\n");

        controller = new GLSLFractalizerController();
        controller.loadAllShaders((msg, progress) ->
            System.out.printf("  [%.0f%%] %s%n", progress * 100, msg));
        System.out.println();

        List<Scene> scenes = generateScenes();

        for (Scene scene : scenes) {
            System.out.printf("--- %s ---%n", scene.name);

            File fracFile = new File(outDir, scene.name + ".frac");
            FractalConfigManager.save(scene.config, fracFile);

            renderKeyFrames(scene, outDir);
        }

        System.out.printf("%n=== Gallery 2 complete: %d scenes ===%n", scenes.size());
        System.exit(0);
    }

    private static List<Scene> generateScenes() {
        List<Scene> scenes = new ArrayList<>();

        // =====================================================================
        // PALETTE VARIETY — same Mandelbulb, different palettes
        // =====================================================================

        // 11. Ice palette
        scenes.add(new Scene("11_palette_ice",
            SceneBuilder.mandelbulb()
                .name("Ice Mandelbulb")
                .camera(0, 0, -3).lookAt(0, 0, 0)
                .palette(1)  // Ice
                .param("power", 8.0)
                .build(),
            new int[]{0}
        ));

        // 12. Neon palette + path tracing (test GI at low samples)
        scenes.add(new Scene("12_palette_neon_pt",
            SceneBuilder.mandelbulb()
                .name("Neon Mandelbulb (Path Traced)")
                .camera(0, 0, -3).lookAt(0, 0, 0)
                .palette(3)  // Neon
                .pathTracing(true)
                .param("power", 8.0)
                .build(),
            new int[]{0}
        ));

        // =====================================================================
        // BOOLEAN OPERATIONS — Subtract and Intersect (not just morph)
        // =====================================================================

        // 13. Mandelbulb SUBTRACT Menger — carved fractal
        scenes.add(new Scene("13_subtract",
            SceneBuilder.mandelbulb()
                .name("Mandelbulb - Menger (Subtract)")
                .camera(0, 0, -3).lookAt(0, 0, 0)
                .booleanOp("menger", 3, 0.05f)
                .boolScale(1.2f)
                .palette(4)  // Spectral
                .param("power", 8.0)
                .build(),
            new int[]{0}
        ));

        // 14. Mandelbulb INTERSECT Menger — only the overlap
        scenes.add(new Scene("14_intersect",
            SceneBuilder.mandelbulb()
                .name("Mandelbulb ∩ Menger (Intersect)")
                .camera(0, 0, -3).lookAt(0, 0, 0)
                .booleanOp("menger", 2, 0.05f)
                .boolScale(0.9f)
                .palette(5)  // Sunset
                .param("power", 8.0)
                .build(),
            new int[]{0}
        ));

        // =====================================================================
        // COMBINED EFFECTS — erosion + crystal + distortion together
        // =====================================================================

        // 15. Eroded twisted Menger with crystals
        scenes.add(new Scene("15_combined_effects",
            SceneBuilder.menger()
                .name("Eroded Twisted Crystal Menger")
                .camera(0, 0, -4).lookAt(0, 0, 0)
                .erosion(0.08f, 2.0f, 1.0f)
                .crystal(0.2f, 1.5f, 1.0f)
                .twist(0.15f, 0.6f)
                .palette(2)  // Forest
                .build(),
            new int[]{0}
        ));

        // =====================================================================
        // VOLUMETRIC FOG — atmospheric depth
        // =====================================================================

        // 16. Mandelbulb in fog
        scenes.add(new Scene("16_fog",
            SceneBuilder.mandelbulb()
                .name("Mandelbulb in Fog")
                .camera(0, 0.3f, -3.5f).lookAt(0, 0, 0)
                .fog(0.3f)
                .fogColor(0.4f, 0.5f, 0.7f)
                .palette(0)  // Magma
                .param("power", 8.0)
                .build(),
            new int[]{0}
        ));

        // =====================================================================
        // LIGHT DIRECTION ANIMATION
        // =====================================================================

        // 17. Rotating light around Sierpinski
        scenes.add(new Scene("17_light_anim",
            SceneBuilder.sierpinski()
                .name("Sierpinski Light Dance")
                .camera(0, 0.5f, -4).lookAt(0, 0, 0)
                .palette(4)  // Spectral
                .duration(6).fps(30)
                .track("lightDir", "float[]", Arrays.asList(2.0, 3.0, -2.0))
                    .spline(true)
                    .key(0.0, Arrays.asList(2.0, 3.0, -2.0))
                    .key(1.5, Arrays.asList(-2.0, 3.0, 2.0))
                    .key(3.0, Arrays.asList(2.0, 1.0, 2.0))
                    .key(4.5, Arrays.asList(-2.0, 3.0, -2.0))
                    .key(6.0, Arrays.asList(2.0, 3.0, -2.0))
                    .done()
                .build(),
            new int[]{0, 45, 90, 135, 180}
        ));

        // =====================================================================
        // DOF ANIMATION — rack focus
        // =====================================================================

        // 18. Depth of Field rack focus
        scenes.add(new Scene("18_dof_rack",
            SceneBuilder.mandelbulb()
                .name("DoF Rack Focus")
                .camera(0, 0, -3).lookAt(0, 0, 0)
                .dof(3.0f, 0.05f)
                .palette(1)  // Ice
                .param("power", 8.0)
                .duration(4).fps(30)
                .track("focalDistance").lin(0, 2.0).lin(2, 4.0).lin(4, 2.0).done()
                .track("aperture").lin(0, 0.03).lin(2, 0.06).lin(4, 0.03).done()
                .build(),
            new int[]{0, 30, 60, 90, 120}
        ));

        // =====================================================================
        // AGGRESSIVE JULIA C ANIMATION — dramatic shape morphing
        // =====================================================================

        // 20. Julia 4D with animated C values (not just sliceW)
        scenes.add(new Scene("20_julia_morph",
            SceneBuilder.quaternionJulia()
                .name("Julia C Morphing")
                .camera(0, 0, -3).lookAt(0, 0, 0)
                .palette(3)  // Neon
                .duration(8).fps(30)
                .track("quaternionjulia4d.juliaCx").lin(0, -0.4).lin(4, 0.4).lin(8, -0.4).done()
                .track("quaternionjulia4d.juliaCy").lin(0, 0.6).lin(4, 0.9).lin(8, 0.6).done()
                .build(),
            new int[]{0, 60, 120, 180, 240}
        ));

        // =====================================================================
        // NESTING with animation
        // =====================================================================

        // 21. Animated nesting — Mandelbulb covered in growing Sierpinski
        scenes.add(new Scene("21_nesting_grow",
            SceneBuilder.mandelbulb()
                .name("Nesting Growth")
                .camera(0, 0, -3).lookAt(0, 0, 0)
                .nesting("sierpinski", 0.08f, 8.0f, 0.0f)
                .boolScale(0.5f)
                .palette(5)  // Sunset
                .param("power", 8.0)
                .duration(6).fps(30)
                .track("boolBlend").lin(0, 0.0).lin(6, 1.0).done()  // nestMix not animatable, but boolBlend might work
                .build(),
            new int[]{0, 45, 90, 135, 180}
        ));

        // =====================================================================
        // DRAMATIC ORBIT — close flyby
        // =====================================================================

        // 22. Close orbit around Apollonian
        scenes.add(new Scene("22_apollonian_orbit",
            SceneBuilder.apollonian()
                .name("Apollonian Flyby")
                .orbitCamera(new float[]{0, 0, 0}, 2.5f, 0.5f, -30, 150, 8.0)
                .palette(0)  // Magma
                .duration(8).fps(30)
                .build(),
            new int[]{0, 60, 120, 180, 240}
        ));

        // =====================================================================
        // BRISTORBROT with Julia mode
        // =====================================================================

        // 23. Bristorbrot Julia animation
        scenes.add(new Scene("23_bristorbrot_julia",
            SceneBuilder.bristorbrot()
                .name("Bristorbrot Julia Mode")
                .camera(0, 0, -3).lookAt(0, 0, 0)
                .palette(3)  // Neon
                .param("juliaCx", 0.5).param("juliaCy", 0.3).param("juliaCz", 0.1)
                .duration(6).fps(30)
                .track("bristorbrot.juliaCx").lin(0, 0.5).lin(3, -0.3).lin(6, 0.5).done()
                .track("bristorbrot.juliaCy").lin(0, 0.3).lin(3, 0.7).lin(6, 0.3).done()
                .build(),
            new int[]{0, 45, 90, 135, 180}
        ));

        // =====================================================================
        // MANDELBOX NEGATIVE SCALE — the classic folding beauty
        // =====================================================================

        // 24. Mandelbox scale sweep (the famous -1.5 to -2.0 range)
        scenes.add(new Scene("24_mandelbox_scale",
            SceneBuilder.mandelbox()
                .name("Mandelbox Scale Sweep")
                .camera(0, 0, -6).lookAt(0, 0, 0)
                .palette(1)  // Ice
                .duration(8).fps(30)
                .track("mandelbox.scale").lin(0, -1.5).lin(4, -2.5).lin(8, -1.5).done()
                .build(),
            new int[]{0, 60, 120, 180, 240}
        ));

        return scenes;
    }

    // ========================================================================
    // Rendering (same as SceneGallery)
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

        org.fractalizer.animation.Timeline timeline = null;
        if (config.animation != null && config.animation.tracks != null) {
            timeline = HeadlessRenderer.buildTimeline(config.animation);
        }

        controller.setExportSize(W, H);

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
                file, SAMPLES, p -> {}, () -> false);
            future.get();

            long ms = System.currentTimeMillis() - start;
            System.out.printf("  [%3dms] %s%n", ms, filename);
        }
    }

    record Scene(String name, FractalConfig config, int[] keyFrames) {}
}
