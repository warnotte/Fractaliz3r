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
 * Cinematic scenes batch 2: pushing further.
 * - More complex camera + effect combos
 * - New fractals (Polyhedral, Kaleidoscopic, Pseudo-Kleinian)
 * - Multi-track animations with staggered timing
 * - Color & lighting storytelling
 */
public class CinematicScenes2 {

    private static final String OUTPUT_DIR = "test_output/cinematic2";
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

        System.out.println("=== Cinematic Scenes 2 ===\n");

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

        System.out.printf("%n=== Cinematic 2 complete: %d scenes ===%n", scenes.size());
        System.exit(0);
    }

    private static List<Scene> generateScenes() {
        List<Scene> scenes = new ArrayList<>();

        // =====================================================================
        // D01: "Kaleidoscope" — Orbit around KIFS with animated distortion
        //
        // Kaleidoscopic IFS has beautiful geometric symmetry.
        // Add gentle twist that animates the offset for sliding motion.
        // =====================================================================
        scenes.add(new Scene("D01_kaleidoscope",
            SceneBuilder.kaleidoscopic()
                .name("Kaleidoscope")
                .param("maxIterations", 22).param("scale", 2.1)
                .param("offsetX", 1.0).param("offsetY", 1.0).param("offsetZ", 1.0)
                .orbitCamera(
                    new float[]{0, 0, 0}, 5.0f, 0.8f, -30, 150, 8.0
                )
                .twist(0.12f, 0.7f)
                .palette(4)  // Spectral
                .lightDir(2, 4, -1)
                .colorStrength(1.3f)
                .duration(8).fps(30)
                .track("distortionOffset")
                    .key(0, 0.0).key(4, 2.0).key(8, 0.0).done()
                .build(),
            new int[]{0, 30, 60, 120, 180, 240}
        ));

        // =====================================================================
        // D02: "Tidal Erosion" — Mandelbulb with animated twist offset
        //
        // Twist offset slides the twist center up and down,
        // creating a "tidal" washing effect on the fractal.
        // Static camera, focus on the effect.
        // =====================================================================
        scenes.add(new Scene("D02_tidal_erosion",
            SceneBuilder.mandelbulb()
                .name("Tidal Erosion")
                .camera(0, 0.3f, -2.8f).lookAt(0, 0, 0)
                .param("power", 7.0)
                .twist(0.25f, 0.6f)
                .distortionAxis(1)  // Y axis
                .erosion(0.05f, 2.5f, 1.0f)
                .palette(7)  // Ocean (if exists, else defaults)
                .lightDir(1.5f, 3, -2)
                .lightColor(0.9f, 0.95f, 1.0f)
                .colorStrength(1.2f)
                .duration(8).fps(30)
                .track("distortionOffset")
                    .key(0, -2.0).key(2, 2.0).key(4, -2.0).key(6, 2.0).key(8, -2.0).done()
                .build(),
            new int[]{0, 30, 60, 120, 180, 240}
        ));

        // =====================================================================
        // D03: "Triple Morph Cycle" — A → B → A with orbit
        //
        // Mandelbulb → Sierpinski → Mandelbulb. Full morph cycle.
        // Orbit camera provides spatial context during transformation.
        // =====================================================================
        scenes.add(new Scene("D03_triple_morph",
            SceneBuilder.mandelbulb()
                .name("Triple Morph Cycle")
                .param("power", 8.0)
                .orbitCamera(
                    new float[]{0, 0, 0}, 3.5f, 0.5f, -20, 200, 10.0
                )
                .morph("sierpinski")
                .boolScale(1.0f)
                .palette(3)  // Neon
                .lightDir(2, 3, -1)
                .colorStrength(1.2f)
                .duration(10).fps(30)
                .track("boolBlend")
                    .key(0, 0.0)       // Mandelbulb
                    .key(2, 0.0)       // hold
                    .key(4, 1.0)       // morph to Sierpinski
                    .key(6, 1.0)       // hold
                    .key(8, 0.0)       // morph back
                    .key(10, 0.0)      // hold
                    .done()
                .build(),
            new int[]{0, 60, 120, 180, 240, 300}
        ));

        // =====================================================================
        // D04: "Bend It" — Menger with animated bend
        //
        // Menger Sponge curves like a banana via bend distortion.
        // Strength animates from 0 to max and back.
        // Crane rising to show the bend from above.
        // =====================================================================
        scenes.add(new Scene("D04_bend_it",
            SceneBuilder.menger()
                .name("Bend It")
                .param("maxIterations", 6)
                .craneCamera(
                    new float[]{0, 0, 0}, 4.5f, 10, -1.0f, 2.5f, 8.0
                )
                .bend(0.0f, 0.5f)
                .palette(5)  // Sunset
                .lightDir(2, 4, -1)
                .colorStrength(1.1f)
                .duration(8).fps(30)
                .track("distortionStrength")
                    .key(0, 0.0).key(4, 0.35).key(8, 0.0).done()
                .build(),
            new int[]{0, 30, 60, 120, 180, 240}
        ));

        // =====================================================================
        // D05: "Deep Blue" — Mandelbulb + SSS + ice palette + fog
        //
        // Subsurface scattering makes the fractal glow from within.
        // Combined with fog and ice palette for underwater feel.
        // Slow dolly approach.
        // =====================================================================
        scenes.add(new Scene("D05_deep_blue",
            SceneBuilder.mandelbulb()
                .name("Deep Blue")
                .param("power", 8.0)
                .dollyCamera(
                    new float[]{0, 0, -5},
                    new float[]{0, 0, -2.5f},
                    new float[]{0, 0, 0},
                    8.0
                )
                .sss(0.6f, 0.2f)
                .sssColor(0.2f, 0.5f, 1.0f)
                .fog(0.06f)
                .fogColor(0.1f, 0.2f, 0.4f)
                .palette(1)  // Ice
                .lightDir(0, 4, -1)
                .lightColor(0.7f, 0.85f, 1.0f)
                .ambientColor(0.05f, 0.08f, 0.15f)
                .colorStrength(1.3f)
                .duration(8).fps(30)
                .build(),
            new int[]{0, 30, 60, 120, 180, 240}
        ));

        // =====================================================================
        // D06: "Fractal Seasons" — Static cam + erosion + moss + crystal sequence
        //
        // Time-lapse of "seasons": clean → eroded → mossy → crystallized.
        // Each effect layer appears in sequence.
        // Like watching a year in seconds.
        // =====================================================================
        scenes.add(new Scene("D06_seasons",
            SceneBuilder.mandelbulb()
                .name("Fractal Seasons")
                .camera(0, 0.5f, -2.8f).lookAt(0, 0, 0)
                .param("power", 8.0)
                .erosion(0.0f, 0.0f, 1.0f)
                .moss(0.0f, 0.0f, 1.2f)
                .mossColor(0.12f, 0.4f, 0.06f)
                .crystal(0.0f, 0.0f, 1.0f)
                .palette(0)  // Magma (earthy base)
                .lightDir(2, 3, -1.5f)
                .colorStrength(1.1f)
                .duration(12).fps(30)
                // Phase 1 (0-4s): Erosion grows
                .track("erosionTime").key(0, 0.0).key(4, 3.0).key(12, 3.0).done()
                .track("erosionStrength").key(0, 0.0).key(4, 0.07).key(12, 0.07).done()
                // Phase 2 (3-7s): Moss spreads
                .track("mossStrength").key(0, 0.0).key(3, 0.0).key(7, 0.4).key(12, 0.4).done()
                .track("mossTime").key(0, 0.0).key(3, 0.0).key(7, 4.0).key(12, 4.0).done()
                // Phase 3 (7-12s): Crystals emerge
                .track("crystalTime").key(0, 0.0).key(7, 0.0).key(12, 2.0).done()
                .track("crystalStrength").key(0, 0.0).key(7, 0.0).key(10, 0.2).key(12, 0.3).done()
                .build(),
            new int[]{0, 60, 120, 180, 240, 300, 360}
        ));

        // =====================================================================
        // D07: "Infinite Orbit" — Repetition 1D + orbit
        //
        // Single-axis repetition creates an infinite line of Menger Sponges.
        // Camera orbits to show the infinite corridor from different angles.
        // =====================================================================
        scenes.add(new Scene("D07_infinite_orbit",
            SceneBuilder.menger()
                .name("Infinite Orbit")
                .param("maxIterations", 5)
                .orbitCamera(
                    new float[]{0, 0, 0}, 5.0f, 1.5f, -30, 150, 8.0
                )
                .repetition(0.2f)  // period=5, X axis by default
                .distortionAxis(2)  // Z axis for depth repetition
                .palette(0)  // Magma
                .fog(0.05f)
                .fogColor(0.25f, 0.25f, 0.35f)
                .lightDir(2, 3, -1)
                .maxRaySteps(300)
                .colorStrength(1.0f)
                .duration(8).fps(30)
                .build(),
            new int[]{0, 30, 60, 120, 180, 240}
        ));

        // =====================================================================
        // D08: "Sunset Mandelbulb" — Golden hour lighting
        //
        // Low-angle warm light, long shadows, Sunset palette.
        // Dolly approach with gentle camera tilt.
        // The "hero shot" for portfolio/showcase.
        // =====================================================================
        scenes.add(new Scene("D08_sunset_hero",
            SceneBuilder.mandelbulb()
                .name("Sunset Hero")
                .param("power", 8.0)
                .dollyCamera(
                    new float[]{1.5f, 0.3f, -4},
                    new float[]{0.5f, 0.2f, -2.5f},
                    new float[]{0, 0, 0},
                    6.0
                )
                .palette(5)  // Sunset
                .lightDir(-3, 1.5f, -1)  // Low angle, from the left
                .lightColor(1, 0.75f, 0.5f)  // Warm golden
                .lightIntensity(1.6f)
                .ambientColor(0.15f, 0.1f, 0.2f)  // Cool shadows
                .ambientIntensity(0.2f)
                .colorStrength(1.5f)
                .glowIntensity(0.2f)
                .duration(6).fps(30)
                .build(),
            new int[]{0, 30, 60, 90, 120, 150, 180}
        ));

        // =====================================================================
        // D09: "Taper Mandelbulb" — Cone-shaped distortion
        //
        // Taper makes the Mandelbulb narrow at one end and wide at the other.
        // Animated: normal → tapered → normal.
        // Orbit to show the 3D effect.
        // =====================================================================
        scenes.add(new Scene("D09_taper",
            SceneBuilder.mandelbulb()
                .name("Taper")
                .param("power", 8.0)
                .orbitCamera(
                    new float[]{0, 0, 0}, 3.5f, 0.5f, 0, 180, 8.0
                )
                .taper(0.0f, 1.0f)
                .palette(4)  // Spectral
                .lightDir(2, 3, -1)
                .colorStrength(1.2f)
                .duration(8).fps(30)
                // Animate taper strength
                .track("distortionStrength")
                    .key(0, 0.0).key(4, 0.35).key(8, 0.0).done()
                .build(),
            new int[]{0, 30, 60, 120, 180, 240}
        ));

        // =====================================================================
        // D10: "Erosion + Morph" — Shape morphs while eroding
        //
        // Two dramatic effects at once: shape changes (morph) AND surface
        // erodes. Creates a feeling of total transformation.
        // Spiral in for dramatic reveal.
        // =====================================================================
        scenes.add(new Scene("D10_erosion_morph",
            SceneBuilder.mandelbulb()
                .name("Erosion Morph")
                .param("power", 8.0)
                .spiralCamera(
                    new float[]{0, 0, 0},
                    6.0f, 5.0f,   // stay far (Mandelbox is much larger than Mandelbulb)
                    1.5f, 0.8f,
                    -20, 160,
                    10.0
                )
                .morph("sierpinski")
                .boolScale(1.0f)
                .erosion(0.0f, 0.0f, 1.0f)
                .palette(0)  // Magma
                .lightDir(2, 3, -1)
                .colorStrength(1.2f)
                .duration(10).fps(30)
                .track("boolBlend")
                    .key(0, 0.0).key(3, 0.0).key(7, 1.0).key(10, 1.0).done()
                .track("erosionTime")
                    .key(0, 0.0).key(10, 3.5).done()
                .track("erosionStrength")
                    .key(0, 0.0).key(3, 0.04).key(10, 0.07).done()
                .build(),
            new int[]{0, 45, 90, 150, 225, 300}
        ));

        // =====================================================================
        // D11: "Subtle Reflections" — Metallic Mandelbulb
        //
        // Metallic material type with reflection intensity.
        // Orbit shows reflective surfaces catching the light.
        // Studio sky for clean reflections.
        // =====================================================================
        scenes.add(new Scene("D11_metallic",
            SceneBuilder.mandelbulb()
                .name("Metallic")
                .param("power", 8.0)
                .orbitCamera(
                    new float[]{0, 0, 0}, 3.0f, 0.3f, -20, 100, 6.0
                )
                .materialType(1)  // Metallic
                .metalness(0.8f)
                .roughness(0.3f)
                .reflectionIntensity(0.6f)
                .palette(1)  // Ice — cool metallic look
                .skyType(3)  // Studio — clean reflections
                .lightDir(2, 3, -1)
                .lightColor(1, 0.95f, 0.9f)
                .colorStrength(1.0f)
                .duration(6).fps(30)
                .build(),
            new int[]{0, 30, 60, 90, 120, 150, 180}
        ));

        // =====================================================================
        // D12: "The Birth" — Power 2→12 with dramatic crane + all effects
        //
        // The ultimate hero scene. Starts as simple sphere (power=2),
        // evolves into complex Mandelbulb (power=12), while camera rises
        // and every effect layer kicks in progressively.
        // =====================================================================
        scenes.add(new Scene("D12_the_birth",
            SceneBuilder.mandelbulb()
                .name("The Birth")
                .craneCamera(
                    new float[]{0, 0, 0}, 3.5f, -10,
                    -2.0f, 3.0f, 12.0
                )
                .erosion(0.0f, 0.0f, 1.0f)
                .crystal(0.0f, 0.0f, 1.0f)
                .moss(0.0f, 0.0f, 1.2f)
                .mossColor(0.1f, 0.35f, 0.05f)
                .palette(5)  // Sunset
                .lightDir(1, 3, -2)
                .lightColor(1, 0.85f, 0.7f)
                .ambientColor(0.1f, 0.07f, 0.15f)
                .colorStrength(1.3f)
                .glowIntensity(0.15f)
                .duration(12).fps(30)
                // Power evolution: sphere → complex
                .track("mandelbulb.power")
                    .key(0, 2.0).key(4, 5.0).key(8, 8.0).key(12, 11.0).done()
                // Erosion starts at 4s
                .track("erosionTime")
                    .key(0, 0.0).key(4, 0.0).key(12, 3.0).done()
                .track("erosionStrength")
                    .key(0, 0.0).key(4, 0.0).key(7, 0.05).key(12, 0.07).done()
                // Moss appears at 6s
                .track("mossStrength")
                    .key(0, 0.0).key(6, 0.0).key(10, 0.3).key(12, 0.35).done()
                .track("mossTime")
                    .key(0, 0.0).key(6, 0.0).key(12, 3.0).done()
                // Crystals emerge at 9s
                .track("crystalTime")
                    .key(0, 0.0).key(9, 0.0).key(12, 1.5).done()
                .track("crystalStrength")
                    .key(0, 0.0).key(9, 0.0).key(11, 0.15).key(12, 0.25).done()
                // Light direction shifts for drama
                .track("lightDir", "float[]", Arrays.asList(1.0, 3.0, -2.0))
                    .spline(true)
                    .key(0, Arrays.asList(1.0, 3.0, -2.0))
                    .key(4, Arrays.asList(-2.0, 4.0, 1.0))
                    .key(8, Arrays.asList(2.0, 2.0, -1.0))
                    .key(12, Arrays.asList(-1.0, 5.0, -2.0))
                    .done()
                .build(),
            new int[]{0, 60, 120, 180, 240, 300, 360}
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
