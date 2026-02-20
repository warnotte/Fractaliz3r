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
 * Cinematic scene gallery: dramatic camera moves + layered effects.
 * Each scene is designed for storytelling — approach, reveal, transformation.
 *
 * The user will crank up render quality and path tracing themselves.
 * We focus on composition, animation, and effect layering.
 */
public class CinematicScenes {

    private static final String OUTPUT_DIR = "test_output/cinematic";
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

        System.out.println("=== Cinematic Scenes Gallery ===\n");

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

        System.out.printf("%n=== Cinematic gallery complete: %d scenes ===%n", scenes.size());
        System.exit(0);
    }

    private static List<Scene> generateScenes() {
        List<Scene> scenes = new ArrayList<>();

        // =====================================================================
        // C01: "Approach" — Spiral descent onto eroded Mandelbulb
        //
        // Camera spirals in from distance, closing from radius 6 to 2.
        // Fractal has light erosion + warm lighting.
        // Tells: "discovering an ancient artifact"
        // =====================================================================
        scenes.add(new Scene("C01_spiral_approach",
            SceneBuilder.mandelbulb()
                .name("Spiral Approach")
                .param("power", 8.0)
                .spiralCamera(
                    new float[]{0, 0, 0},   // center
                    6.0f, 2.0f,             // radius: far -> close
                    2.0f, 0.3f,             // height: above -> low
                    0, 270,                  // 270° sweep
                    8.0                      // 8 seconds
                )
                .erosion(0.06f, 3.0f, 1.0f)
                .palette(5)  // Sunset — warm tones
                .lightDir(1.5f, 4, -1)
                .lightColor(1, 0.9f, 0.8f)
                .ambientColor(0.15f, 0.1f, 0.2f)
                .colorStrength(1.2f)
                .duration(8).fps(30)
                .build(),
            new int[]{0, 30, 60, 120, 180, 240}
        ));

        // =====================================================================
        // C02: "Reveal" — Crane shot rising over Menger + crystals
        //
        // Camera starts low, rises dramatically revealing crystallized Menger.
        // Low angle → God's eye view progression.
        // Tells: "unveiling a crystalline temple"
        // =====================================================================
        scenes.add(new Scene("C02_crane_reveal",
            SceneBuilder.menger()
                .name("Crystal Temple Reveal")
                .param("maxIterations", 7)
                .craneCamera(
                    new float[]{0, 0, 0},   // center
                    3.5f,                   // radius
                    30,                     // angle (degrees from front)
                    -2.0f, 3.0f,            // height: below -> above
                    6.0                      // 6 seconds
                )
                .crystal(0.25f, 2.0f, 1.2f)
                .palette(1)  // Ice — cold crystalline look
                .lightDir(2, 5, -1)
                .lightColor(0.9f, 0.95f, 1.0f)
                .ambientColor(0.1f, 0.12f, 0.2f)
                .colorStrength(1.3f)
                .duration(6).fps(30)
                .build(),
            new int[]{0, 30, 60, 90, 120, 150, 180}
        ));

        // =====================================================================
        // C03: "Erosion Timelapse" — Static camera, growing erosion
        //
        // Camera holds still. Erosion grows from 0 to dramatic.
        // Like watching millennia of weathering in seconds.
        // Tells: "the passage of time"
        // =====================================================================
        scenes.add(new Scene("C03_erosion_timelapse",
            SceneBuilder.mandelbulb()
                .name("Erosion Timelapse")
                .camera(0, 0.3f, -2.8f).lookAt(0, 0, 0)
                .param("power", 8.0)
                .erosion(0.05f, 0.0f, 1.0f)  // starts at time=0
                .palette(0)  // Magma — rocky, earthy
                .lightDir(2, 3, -1.5f)
                .colorStrength(1.1f)
                .duration(8).fps(30)
                .track("erosionTime").key(0, 0.0).key(8, 4.0).done()
                .track("erosionStrength").lin(0, 0.02).lin(3, 0.05).lin(8, 0.09).done()
                .build(),
            new int[]{0, 30, 60, 120, 180, 240}
        ));

        // =====================================================================
        // C04: "Metamorphosis" — Dolly in + morph Mandelbulb → Menger
        //
        // Camera pushes in while the fractal morphs between two types.
        // Smooth approach combined with transformation.
        // Tells: "a being changing form"
        // =====================================================================
        scenes.add(new Scene("C04_metamorphosis",
            SceneBuilder.mandelbulb()
                .name("Metamorphosis")
                .param("power", 8.0)
                .dollyCamera(
                    new float[]{0, 0.5f, -5},   // start far
                    new float[]{0, 0.2f, -2.5f}, // end close
                    new float[]{0, 0, 0},         // always look at center
                    8.0
                )
                .morph("menger")
                .boolScale(1.1f)
                .palette(4)  // Spectral
                .lightDir(1.5f, 3, -2)
                .colorStrength(1.2f)
                .duration(8).fps(30)
                .track("boolBlend")
                    .key(0, 0.0)
                    .key(2, 0.0)      // hold Mandelbulb 2s
                    .key(5, 1.0)      // morph over 3s
                    .key(8, 1.0)      // hold Menger
                    .done()
                .build(),
            new int[]{0, 30, 60, 90, 150, 210, 240}
        ));

        // =====================================================================
        // C05: "Twisted Cathedral" — Orbit around twisted Menger with fog
        //
        // Slow orbit around a gently twisted Menger in atmospheric fog.
        // Dramatic side lighting creates deep shadows.
        // Tells: "exploring an impossible architecture"
        // =====================================================================
        scenes.add(new Scene("C05_twisted_cathedral",
            SceneBuilder.menger()
                .name("Twisted Cathedral")
                .param("maxIterations", 7)
                .orbitCamera(
                    new float[]{0, 0, 0}, 4.0f, 0.8f, -45, 135, 10.0
                )
                .twist(0.2f, 0.5f)
                .fog(0.08f)
                .fogColor(0.35f, 0.4f, 0.55f)
                .palette(2)  // Forest — organic despite being geometric
                .lightDir(-3, 2, 1)
                .lightColor(1, 0.9f, 0.75f)
                .lightIntensity(1.4f)
                .ambientIntensity(0.35f)
                .colorStrength(1.1f)
                .duration(10).fps(30)
                .build(),
            new int[]{0, 45, 90, 150, 225, 300}
        ));

        // =====================================================================
        // C06: "Growth" — Static camera, moss growing on Apollonian
        //
        // Apollonian Gasket starts clean, then moss slowly covers it.
        // Camera slightly elevated for good composition.
        // Tells: "nature reclaiming geometry"
        // =====================================================================
        scenes.add(new Scene("C06_moss_growth",
            SceneBuilder.mandelbulb()
                .name("Moss Growth")
                .camera(0, 0.5f, -3).lookAt(0, 0, 0)
                .param("power", 8.0)
                .moss(0.0f, 0.0f, 1.2f)
                .mossColor(0.12f, 0.4f, 0.06f)
                .erosion(0.04f, 2.0f, 1.0f)  // slight weathering for moss to grip
                .palette(2)  // Forest
                .lightDir(2, 4, -1)
                .colorStrength(1.0f)
                .duration(6).fps(30)
                .track("mossStrength").key(0, 0.0).key(6, 0.45).done()
                .track("mossTime").key(0, 0.0).key(6, 4.0).done()
                .build(),
            new int[]{0, 30, 60, 90, 120, 150, 180}
        ));

        // =====================================================================
        // C07: "Julia Dreams" — Spiral + Julia C morphing
        //
        // Camera spirals around while Julia C values morph the shape.
        // Double motion: camera movement + shape change.
        // Tells: "a shape shifting in a dream"
        // =====================================================================
        scenes.add(new Scene("C07_julia_dreams",
            SceneBuilder.quaternionJulia()
                .name("Julia Dreams")
                .spiralCamera(
                    new float[]{0, 0, 0},
                    4.0f, 3.0f,           // spiral in slightly
                    0.5f, 0.3f,
                    0, 360,               // full 360
                    10.0
                )
                .palette(3)  // Neon — dreamy, vibrant
                .lightDir(1, 3, -2)
                .lightColor(0.9f, 0.85f, 1.0f)
                .colorStrength(1.3f)
                .duration(10).fps(30)
                .track("quaternionjulia4d.juliaCx")
                    .key(0, -0.2).key(2.5, 0.3).key(5, -0.4).key(7.5, 0.2).key(10, -0.2).done()
                .track("quaternionjulia4d.juliaCy")
                    .key(0, 0.8).key(2.5, 0.5).key(5, 0.9).key(7.5, 0.6).key(10, 0.8).done()
                .build(),
            new int[]{0, 37, 75, 150, 225, 300}
        ));

        // =====================================================================
        // C08: "Power Surge" — Dolly in + power sweep on Mandelbulb
        //
        // Classic Mandelbulb power animation (3→8→3) with dramatic push-in.
        // Power controls the fractal's "spikiness" — from blob to star.
        // Tells: "energy building and releasing"
        // =====================================================================
        scenes.add(new Scene("C08_power_surge",
            SceneBuilder.mandelbulb()
                .name("Power Surge")
                .dollyCamera(
                    new float[]{0, 0, -4.5f},
                    new float[]{0, 0.2f, -2.2f},
                    new float[]{0, 0, 0},
                    8.0
                )
                .palette(3)  // Neon
                .lightDir(2, 3, -1)
                .lightColor(0.85f, 0.9f, 1.0f)
                .glowIntensity(0.25f)
                .colorStrength(1.4f)
                .duration(8).fps(30)
                .track("mandelbulb.power")
                    .key(0, 3.0)
                    .key(4, 8.0)
                    .key(8, 3.0)
                    .done()
                .build(),
            new int[]{0, 30, 60, 120, 180, 240}
        ));

        // =====================================================================
        // C09: "Frozen Ruins" — Orbit + erosion + crystals + fog
        //
        // Combined effects: eroded structure encrusted with ice crystals, in fog.
        // This was the most visually striking combination from Gallery 2.
        // Crane rising from inside the fog.
        // Tells: "discovering frozen alien ruins"
        // =====================================================================
        scenes.add(new Scene("C09_frozen_ruins",
            SceneBuilder.menger()
                .name("Frozen Ruins")
                .param("maxIterations", 6)
                .craneCamera(
                    new float[]{0, 0, 0},
                    3.5f,
                    -20,                   // slightly off-center angle
                    -1.0f, 2.5f,           // rise from below
                    8.0
                )
                .erosion(0.07f, 3.0f, 1.0f)
                .crystal(0.3f, 2.0f, 1.0f)
                .fog(0.15f)
                .fogColor(0.4f, 0.5f, 0.65f)
                .palette(1)  // Ice
                .lightDir(1, 4, -2)
                .lightColor(0.85f, 0.92f, 1.0f)
                .ambientColor(0.08f, 0.1f, 0.18f)
                .colorStrength(1.2f)
                .duration(8).fps(30)
                .build(),
            new int[]{0, 30, 60, 120, 180, 240}
        ));

        // =====================================================================
        // C10: "Mandelbox Abyss" — Dolly into Mandelbox negative scale
        //
        // Approach shot into the fractal while scale sweeps.
        // Mandelbox with negative scale creates infinite detail corridors.
        // Tells: "descending into an infinite abyss"
        // =====================================================================
        scenes.add(new Scene("C10_mandelbox_abyss",
            SceneBuilder.mandelbox()
                .name("Mandelbox Abyss")
                .dollyCamera(
                    new float[]{0, 0.5f, -7},
                    new float[]{0, 0.2f, -3.5f},
                    new float[]{0, 0, 0},
                    10.0
                )
                .palette(0)  // Magma — warm depth
                .lightDir(1, 3, -2)
                .lightColor(1, 0.85f, 0.7f)
                .colorStrength(1.3f)
                .duration(10).fps(30)
                .track("mandelbox.scale")
                    .key(0, -1.5)
                    .key(5, -2.2)
                    .key(10, -1.8)
                    .done()
                .build(),
            new int[]{0, 45, 90, 150, 225, 300}
        ));

        // =====================================================================
        // C11: "Boolean Dance" — Orbit + animated subtract
        //
        // Mandelbulb with Sierpinski subtracted, rotating around it.
        // The boolean blend animates from solid to carved.
        // Tells: "a sculpture being chiseled by invisible hands"
        // =====================================================================
        scenes.add(new Scene("C11_boolean_dance",
            SceneBuilder.mandelbulb()
                .name("Boolean Dance")
                .param("power", 8.0)
                .orbitCamera(
                    new float[]{0, 0, 0}, 3.0f, 0.5f, 0, 180, 8.0
                )
                .booleanOp("sierpinski", 3, 0.0f)
                .boolScale(1.0f)
                .palette(4)  // Spectral
                .lightDir(2, 3, -1.5f)
                .colorStrength(1.2f)
                .duration(8).fps(30)
                .track("boolBlend")
                    .key(0, 0.0)
                    .key(2, 0.0)
                    .key(4, 0.2)   // smooth blend increases
                    .key(6, 0.1)
                    .key(8, 0.0)
                    .done()
                .build(),
            new int[]{0, 30, 60, 120, 180, 240}
        ));

        // =====================================================================
        // C12: "Bristorbrot Nightmare" — Spiral + Julia morph
        //
        // Bristorbrot is wild and organic. Spiral in while Julia C morphs.
        // Deep shadows, dark ambient, neon palette for alien feel.
        // Tells: "encountering something from another dimension"
        // =====================================================================
        scenes.add(new Scene("C12_bristorbrot_nightmare",
            SceneBuilder.bristorbrot()
                .name("Bristorbrot Nightmare")
                .param("juliaCx", 0.3).param("juliaCy", 0.5).param("juliaCz", 0.1)
                .spiralCamera(
                    new float[]{0, 0, 0},
                    5.0f, 2.5f,
                    1.0f, 0.3f,
                    -30, 210,
                    8.0
                )
                .palette(3)  // Neon
                .lightDir(-1, 4, -2)
                .lightColor(0.8f, 0.85f, 1.0f)
                .ambientColor(0.05f, 0.03f, 0.1f)
                .ambientIntensity(0.15f)
                .glowIntensity(0.3f)
                .colorStrength(1.5f)
                .duration(8).fps(30)
                .track("bristorbrot.juliaCx")
                    .key(0, 0.3).key(4, -0.2).key(8, 0.3).done()
                .track("bristorbrot.juliaCy")
                    .key(0, 0.5).key(4, 0.8).key(8, 0.5).done()
                .build(),
            new int[]{0, 30, 60, 120, 180, 240}
        ));

        // =====================================================================
        // C13: "The Awakening" — Crane + power + erosion + crystal buildup
        //
        // The ultimate combined scene. Mandelbulb starts smooth (low power),
        // power increases as erosion grows and crystals appear.
        // Camera rises to reveal the transformation.
        // Tells: "a dormant entity awakening"
        // =====================================================================
        scenes.add(new Scene("C13_the_awakening",
            SceneBuilder.mandelbulb()
                .name("The Awakening")
                .craneCamera(
                    new float[]{0, 0, 0},
                    3.0f,
                    15,
                    -1.5f, 2.0f,
                    10.0
                )
                .erosion(0.0f, 0.0f, 1.0f)
                .crystal(0.0f, 0.0f, 1.2f)
                .palette(5)  // Sunset — warm dramatic
                .lightDir(1, 3, -2)
                .lightColor(1, 0.85f, 0.7f)
                .ambientColor(0.12f, 0.08f, 0.15f)
                .glowIntensity(0.2f)
                .colorStrength(1.3f)
                .duration(10).fps(30)
                // Power increases: blob to star
                .track("mandelbulb.power")
                    .key(0, 4.0).key(5, 7.0).key(10, 8.5).done()
                // Erosion grows
                .track("erosionTime")
                    .key(0, 0.0).key(10, 4.0).done()
                .track("erosionStrength")
                    .key(0, 0.0).key(3, 0.04).key(10, 0.08).done()
                // Crystals emerge later
                .track("crystalTime")
                    .key(0, 0.0).key(5, 0.0).key(10, 2.0).done()
                .track("crystalStrength")
                    .key(0, 0.0).key(5, 0.0).key(8, 0.15).key(10, 0.3).done()
                .build(),
            new int[]{0, 45, 90, 150, 225, 300}
        ));

        // =====================================================================
        // C14: "Sierpinski Light Play" — Orbit + animated light direction
        //
        // The geometric purity of Sierpinski with moving shadows.
        // Strong directional light orbiting the fractal.
        // Tells: "time of day passing over a monument"
        // =====================================================================
        scenes.add(new Scene("C14_light_play",
            SceneBuilder.sierpinski()
                .name("Sierpinski Light Play")
                .param("maxIterations", 16)
                .orbitCamera(
                    new float[]{0, 0.3f, 0}, 4.0f, 0.8f, -20, 100, 8.0
                )
                .palette(4)  // Spectral
                .lightIntensity(1.5f)
                .ambientIntensity(0.15f)
                .colorStrength(1.2f)
                .duration(8).fps(30)
                .track("lightDir", "float[]", Arrays.asList(3.0, 4.0, -1.0))
                    .spline(true)
                    .key(0.0, Arrays.asList(3.0, 4.0, -1.0))
                    .key(2.0, Arrays.asList(-1.0, 5.0, 2.0))
                    .key(4.0, Arrays.asList(-3.0, 2.0, 1.0))
                    .key(6.0, Arrays.asList(1.0, 5.0, -3.0))
                    .key(8.0, Arrays.asList(3.0, 4.0, -1.0))
                    .done()
                .build(),
            new int[]{0, 30, 60, 120, 180, 240}
        ));

        // =====================================================================
        // C15: "Infinite Corridor" — Dolly through repeated Mandelbulb
        //
        // Repetition 3D creates infinite copies. Camera dollies through.
        // Low frequency = big cells, camera path avoids collision.
        // Tells: "walking through an infinite gallery"
        // =====================================================================
        scenes.add(new Scene("C15_infinite_corridor",
            SceneBuilder.mandelbulb()
                .name("Infinite Corridor")
                .param("power", 6.0)
                .dollyCamera(
                    new float[]{0, 1.5f, -7},
                    new float[]{0, 1.5f, 3},
                    new float[]{0, 0, 0},
                    8.0
                )
                .repetition3D(0.2f)  // period=5, large cells
                .palette(0)  // Magma
                .fog(0.1f)
                .fogColor(0.3f, 0.3f, 0.4f)
                .lightDir(1, 3, -1)
                .colorStrength(1.1f)
                .maxRaySteps(300)
                .duration(8).fps(30)
                .build(),
            new int[]{0, 30, 60, 120, 180, 240}
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
