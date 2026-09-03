package org.fractalizer.test;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.*;
import org.fractalizer.graph.*;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.fractalizer.test.SceneBuilder.*;

/**
 * Gallery 3: Node Graph showcase.
 * Demonstrates CSG operations, coordinate transforms, per-node params,
 * and animation using the node graph system.
 */
public class SceneGallery3 {

    private static final String OUTPUT_DIR = "out/test_output/gallery3";
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

        System.out.println("=== Scene Gallery 3: Node Graph Showcase ===\n");

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

        System.out.printf("%n=== Gallery 3 complete: %d scenes ===%n", scenes.size());
        System.exit(0);
    }

    private static List<Scene> generateScenes() {
        List<Scene> scenes = new ArrayList<>();

        // =====================================================================
        // 31 — CSG Smooth Union: Mandelbulb + Menger
        // =====================================================================

        {
            FractalNode mb = fractal(FractalType.MANDELBULB);
            ((MandelbulbParams) mb.getFractalParams()).setPower(8);
            FractalNode mg = fractal(FractalType.MENGER_SPONGE);

            GraphNode root = union(
                translate(mb, -0.5f, 0, 0),
                translate(mg, 0.5f, 0, 0),
                0.1f
            );

            scenes.add(new Scene("31_csg_union",
                nodeGraph(root)
                    .name("CSG Smooth Union")
                    .camera(0, 0, -4).lookAt(0, 0, 0)
                    .palette(0)
                    .build(),
                new int[]{0}
            ));
        }

        // =====================================================================
        // 32 — CSG Subtract: Menger holes in Mandelbulb
        // =====================================================================

        {
            FractalNode mb = fractal(FractalType.MANDELBULB);
            ((MandelbulbParams) mb.getFractalParams()).setPower(8);
            FractalNode mg = fractal(FractalType.MENGER_SPONGE);

            GraphNode root = subtract(mb, scale(mg, 0.9f), 0.05f);

            scenes.add(new Scene("32_csg_subtract",
                nodeGraph(root)
                    .name("CSG Subtract")
                    .camera(0, 0, -3.5f).lookAt(0, 0, 0)
                    .palette(4)
                    .build(),
                new int[]{0}
            ));
        }

        // =====================================================================
        // 33 — Twist Transform on Menger
        // =====================================================================

        {
            FractalNode mg = fractal(FractalType.MENGER_SPONGE);
            GraphNode root = twist(mg, 0.3f, 1);  // twist around Y

            scenes.add(new Scene("33_twist_menger",
                nodeGraph(root)
                    .name("Twisted Menger")
                    .camera(0, 0.5f, -4).lookAt(0, 0, 0)
                    .palette(2)
                    .build(),
                new int[]{0}
            ));
        }

        // =====================================================================
        // 34 — Repeated Mandelbulb (3D Repetition)
        // =====================================================================

        {
            FractalNode mb = fractal(FractalType.MANDELBULB);
            ((MandelbulbParams) mb.getFractalParams()).setPower(8);
            GraphNode root = repeat(scale(mb, 0.4f), 3, 3, 3);

            scenes.add(new Scene("34_repeat_mandelbulb",
                nodeGraph(root)
                    .name("Repeated Mandelbulb")
                    .camera(2, 3, -8).lookAt(0, 0, 0)
                    .fov(70)
                    .palette(3)
                    .build(),
                new int[]{0}
            ));
        }

        // =====================================================================
        // 35 — Mirror + CSG Union: symmetric Mandelbulb + Menger center
        // =====================================================================

        {
            FractalNode mb = fractal(FractalType.MANDELBULB);
            ((MandelbulbParams) mb.getFractalParams()).setPower(8);
            FractalNode mg = fractal(FractalType.MENGER_SPONGE);

            GraphNode root = union(
                mirror(translate(mb, 1.0f, 0, 0), 0),  // mirror on X
                mg,
                0.08f
            );

            scenes.add(new Scene("35_mirror_union",
                nodeGraph(root)
                    .name("Mirror + Union")
                    .camera(0, 0, -4).lookAt(0, 0, 0)
                    .palette(6)
                    .build(),
                new int[]{0}
            ));
        }

        // =====================================================================
        // 36 — Nested Transforms: Twist(Translate(Menger))
        // =====================================================================

        {
            FractalNode mg = fractal(FractalType.MENGER_SPONGE);
            GraphNode root = twist(translate(mg, 0.5f, 0.2f, 0), 0.25f, 1);

            scenes.add(new Scene("36_nested_transforms",
                nodeGraph(root)
                    .name("Nested Transforms")
                    .camera(0, 0.3f, -4).lookAt(0, 0, 0)
                    .palette(5)
                    .build(),
                new int[]{0}
            ));
        }

        // =====================================================================
        // 37 — Morph Animation: Mandelbulb → Sierpinski
        // =====================================================================

        {
            FractalNode mb = fractal(FractalType.MANDELBULB);
            mb.setName("Mandelbulb");
            FractalNode si = fractal(FractalType.SIERPINSKI);
            si.setName("Sierpinski");

            CSGNode morph = morphGraph(mb, si, 0.0f);
            morph.setName("CSG");

            scenes.add(new Scene("37_morph_anim",
                nodeGraph(morph)
                    .name("Morph Animation")
                    .camera(0, 0, -3.5f).lookAt(0, 0, 0)
                    .palette(7)
                    .duration(6).fps(30)
                    .track("CSG.blend").lin(0, 0.0).lin(3, 1.0).lin(6, 0.0).done()
                    .build(),
                new int[]{0, 45, 90, 135, 180}
            ));
        }

        // =====================================================================
        // 38 — Triple Union: Mandelbulb + Menger + Apollonian
        // =====================================================================

        {
            FractalNode mb = fractal(FractalType.MANDELBULB);
            ((MandelbulbParams) mb.getFractalParams()).setPower(8);
            FractalNode mg = fractal(FractalType.MENGER_SPONGE);
            FractalNode ap = fractal(FractalType.APOLLONIAN);

            GraphNode root = union(
                union(
                    translate(mb, -1.2f, 0, 0),
                    translate(mg, 0, 0, 0),
                    0.1f
                ),
                translate(ap, 1.2f, 0, 0),
                0.1f
            );

            scenes.add(new Scene("38_triple_union",
                nodeGraph(root)
                    .name("Triple Union")
                    .camera(0, 0.5f, -5).lookAt(0, 0, 0)
                    .palette(0)
                    .build(),
                new int[]{0}
            ));
        }

        // =====================================================================
        // 39 — Twist + Erosion: global erosion on twisted Mandelbulb
        // =====================================================================

        {
            FractalNode mb = fractal(FractalType.MANDELBULB);
            ((MandelbulbParams) mb.getFractalParams()).setPower(8);
            GraphNode root = twist(mb, 0.2f, 1);

            scenes.add(new Scene("39_twist_erosion",
                nodeGraph(root)
                    .name("Twisted Erosion")
                    .camera(0, 0.3f, -3.5f).lookAt(0, 0, 0)
                    .palette(2)
                    .erosion(0.1f, 3.0f, 1.0f)
                    .build(),
                new int[]{0}
            ));
        }

        // =====================================================================
        // 40 — Repetition 1D: infinite Menger column
        // =====================================================================

        {
            FractalNode mg = fractal(FractalType.MENGER_SPONGE);
            GraphNode root = repeat1D(mg, 1, 4);  // Y axis, period 4

            scenes.add(new Scene("40_repeat1d_column",
                nodeGraph(root)
                    .name("Menger Column")
                    .camera(1.5f, 2, -4).lookAt(0, 1, 0)
                    .palette(1)
                    .build(),
                new int[]{0}
            ));
        }

        return scenes;
    }

    // ========================================================================
    // Rendering (same pattern as SceneGallery / SceneGallery2)
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
