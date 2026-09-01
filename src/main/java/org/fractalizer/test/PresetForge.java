package org.fractalizer.test;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.graph.HybridNode;
import org.fractalizer.graph.HybridNode.DEMode;
import org.fractalizer.graph.HybridNode.Step;
import org.fractalizer.graph.HybridNode.StepType;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

/**
 * Builds demo presets and renders a preview for each, so a candidate can be judged
 * before it is kept.
 *
 * Cameras come from FractalNavigator sweet spots (see nav/detail_scenes.txt and the
 * traveller logs) rather than default global framings — the point of a demo preset is
 * the fine detail, which default cameras do not show.
 *
 * Usage:
 *   -Dexec.args="&lt;outDir&gt; &lt;previewDir&gt; &lt;WxH&gt; &lt;samples&gt;"
 *   -Dexec.args="presets presets_preview 640x360 24"
 */
public class PresetForge {

    /** Julia Mandelbulb constant validated for deep-zoom detail (see docs/RENDERING.md). */
    private static final double JCX = 0.42, JCY = 0.18, JCZ = -0.31;

    // Palette gradients — {position, r, g, b}. The gradient is what colours the
    // fractal; paletteIndex does not feed the palette texture.
    private static final float[][] AMBER = {
        {0.00f, 0.00f, 0.00f, 0.04f}, {0.25f, 0.35f, 0.00f, 0.15f},
        {0.50f, 0.90f, 0.30f, 0.00f}, {0.75f, 1.00f, 0.85f, 0.20f}, {1.00f, 1.00f, 1.00f, 0.85f}};
    private static final float[][] ICE = {
        {0.00f, 0.01f, 0.03f, 0.09f}, {0.35f, 0.06f, 0.30f, 0.55f},
        {0.65f, 0.40f, 0.78f, 0.95f}, {1.00f, 0.92f, 0.99f, 1.00f}};
    private static final float[][] VIOLET = {
        {0.00f, 0.03f, 0.00f, 0.10f}, {0.30f, 0.32f, 0.04f, 0.45f},
        {0.60f, 0.85f, 0.25f, 0.62f}, {1.00f, 1.00f, 0.86f, 0.96f}};
    private static final float[][] EMERALD = {
        {0.00f, 0.01f, 0.05f, 0.03f}, {0.35f, 0.04f, 0.33f, 0.20f},
        {0.70f, 0.45f, 0.85f, 0.38f}, {1.00f, 0.95f, 1.00f, 0.78f}};

    // --- Hybrid chains: several formulas composed inside one iteration loop. No CSG
    // combination of two finished distance fields can reach these shapes. ---

    private static Step bulbStep(float power) {
        Step s = new Step(StepType.BULB);
        s.setPower(power);
        return s;
    }

    private static Step boxFoldStep(float scale, float minR, float fixedR, float limit) {
        Step s = new Step(StepType.BOX_FOLD);
        s.setScale(scale); s.setMinRadius(minR); s.setFixedRadius(fixedR); s.setFoldLimit(limit);
        return s;
    }

    private static Step rotateStep(float rx, float ry, float rz) {
        Step s = new Step(StepType.ROTATE);
        s.setRotX(rx); s.setRotY(ry); s.setRotZ(rz);
        return s;
    }

    static Map<String, Supplier<SceneBuilder>> presets() {
        Map<String, Supplier<SceneBuilder>> p = new LinkedHashMap<>();

        // --- Julia Mandelbulb: fractal at every scale, unlike the Mandelbrot form ---
        p.put("JULIA_BULB_OVERVIEW", () -> SceneBuilder.mandelbulb()
                .param("maxIterations", 20)
                .param("juliaCx", JCX).param("juliaCy", JCY).param("juliaCz", JCZ)
                .camera(0f, 0.30f, -2.30f).lookAt(0f, 0.30f, 0f).fov(45)
                .pathTracing(true).skyType(1)
                .lightDir(2, 3, -2).lightIntensity(1.3f)
                .ambientColor(0.10f, 0.14f, 0.22f).ambientIntensity(0.35f)
                .palette(2).colorStrength(0.9f)
                .metalness(0.25f).roughness(0.45f));

        p.put("JULIA_BULB_SWEETSPOT", () -> SceneBuilder.mandelbulb()
                .param("maxIterations", 20)
                .param("juliaCx", JCX).param("juliaCy", JCY).param("juliaCz", JCZ)
                .camera(0f, 0.319f, -1.461f).lookAt(0f, 0.457f, -0.793f).fov(45)
                .gradient(VIOLET)
                .pathTracing(true).skyType(1)
                .lightDir(2.5f, 2.0f, -1.5f).lightIntensity(1.6f)
                .ambientColor(0.07f, 0.08f, 0.13f).ambientIntensity(0.18f)
                .palette(5).colorStrength(1.0f)
                .metalness(0.15f).roughness(0.45f));

        p.put("JULIA_BULB_ABYSS", () -> SceneBuilder.mandelbulb()
                .param("maxIterations", 20)
                .param("juliaCx", JCX).param("juliaCy", JCY).param("juliaCz", JCZ)
                .camera(0f, 0.450f, -0.827f).lookAt(0f, 0.457f, -0.793f).fov(45)
                .detailLOD(2f, 24)
                .pathTracing(true).skyType(1)
                .lightDir(2f, 1.5f, -2f).lightIntensity(1.4f)
                .ambientColor(0.16f, 0.12f, 0.10f).ambientIntensity(0.45f)
                .palette(1).colorStrength(1.1f)
                .metalness(0.35f).roughness(0.30f));

        p.put("JULIA_BULB_CORAL", () -> SceneBuilder.mandelbulb()
                .param("maxIterations", 20).param("power", 8.0)
                .param("juliaCx", -0.15).param("juliaCy", 0.55).param("juliaCz", 0.20)
                .camera(0f, 0f, -2.40f).lookAt(0f, 0f, 0f).fov(45)
                .gradient(ICE)
                .pathTracing(true).skyType(2)
                .lightDir(-2f, 2.5f, -2f).lightIntensity(1.4f)
                .ambientColor(0.10f, 0.16f, 0.20f).ambientIntensity(0.35f)
                .palette(4).colorStrength(0.8f)
                .metalness(0.4f).roughness(0.3f));

        // --- Found by JuliaProspector, not by hand: constants sitting on the Mandelbulb
        // boundary whose Julia sets branch instead of closing into a ball. ---
        p.put("JULIA_FOUND_BRANCH", () -> SceneBuilder.mandelbulb()
                .param("maxIterations", 20)
                .param("juliaCx", -0.2422).param("juliaCy", 0.9995).param("juliaCz", 0.2219)
                .camera(1.20f, 0.85f, -1.85f).lookAt(0f, 0f, 0f).fov(50)
                .gradient(EMERALD)
                .pathTracing(true).skyType(1)
                .lightDir(2f, 2.5f, -2f).lightIntensity(1.4f)
                .ambientColor(0.09f, 0.14f, 0.18f).ambientIntensity(0.32f)
                .colorStrength(0.9f).metalness(0.35f).roughness(0.35f));

        p.put("JULIA_FOUND_CLUSTER", () -> SceneBuilder.mandelbulb()
                .param("maxIterations", 20)
                .param("juliaCx", 0.7126).param("juliaCy", 0.7796).param("juliaCz", 0.2602)
                .camera(1.20f, 0.85f, -1.85f).lookAt(0f, 0f, 0f).fov(50)
                .gradient(VIOLET)
                .pathTracing(true).skyType(2)
                .lightDir(-1.8f, 2.2f, -2f).lightIntensity(1.4f)
                .ambientColor(0.12f, 0.10f, 0.18f).ambientIntensity(0.34f)
                .colorStrength(0.9f).metalness(0.3f).roughness(0.4f));

        p.put("HYBRID_BOXBULB", () -> SceneBuilder.nodeGraph(new HybridNode(
                    java.util.List.of(bulbStep(8f), boxFoldStep(1.2f, 0.5f, 1f, 1f),
                                      new Step(StepType.ADD_C)),
                    12, 8f, DEMode.LOG))
                .camera(1.26f, 0.90f, -2.55f).lookAt(0f, 0f, 0f).fov(50)
                .gradient(AMBER)
                .pathTracing(true).skyType(1)
                .lightDir(2f, 3f, -2f).lightIntensity(1.3f)
                .ambientColor(0.10f, 0.13f, 0.20f).ambientIntensity(0.33f)
                .colorStrength(0.9f).metalness(0.35f).roughness(0.35f));

        p.put("HYBRID_ROTOBOX", () -> SceneBuilder.nodeGraph(new HybridNode(
                    java.util.List.of(bulbStep(6f), rotateStep(24f, 37f, 0f),
                                      boxFoldStep(1.3f, 0.5f, 1f, 1f), new Step(StepType.ADD_C)),
                    12, 8f, DEMode.LOG))
                .camera(1.26f, 0.90f, -2.55f).lookAt(0f, 0f, 0f).fov(50)
                .gradient(ICE)
                .pathTracing(true).skyType(1)
                .lightDir(-1.8f, 2.4f, -2f).lightIntensity(1.4f)
                .ambientColor(0.09f, 0.13f, 0.20f).ambientIntensity(0.33f)
                .colorStrength(0.9f).metalness(0.4f).roughness(0.3f));

        // --- Mandelbox: self-similar, holds detail at any zoom; LOD earns its cost here ---
        p.put("MANDELBOX_DEEP", () -> SceneBuilder.mandelbox()
                .param("scale", 2.0).param("minRadius", 0.25)
                .camera(-1.4863f, 1.4465f, -6.0484f).lookAt(-1.45361f, 1.45941f, -6.01285f).fov(45)
                .detailLOD(2f, 24)
                .gradient(EMERALD)
                .pathTracing(true).skyType(1)
                .lightDir(2, 3, -1).lightIntensity(1.3f)
                .ambientColor(0.12f, 0.16f, 0.22f).ambientIntensity(0.35f)
                .palette(3).colorStrength(0.85f)
                .metalness(0.5f).roughness(0.25f));

        p.put("MANDELBOX_LEDGE", () -> SceneBuilder.mandelbox()
                .param("scale", 2.0).param("minRadius", 0.25)
                .camera(-1.5518f, 1.4207f, -6.1194f).lookAt(-1.45361f, 1.45941f, -6.01285f).fov(45)
                .detailLOD(2f, 24)
                .gradient(VIOLET)
                .pathTracing(true).skyType(1)
                .lightDir(-1.5f, 2.5f, -2f).lightIntensity(1.4f)
                .ambientColor(0.10f, 0.12f, 0.18f).ambientIntensity(0.30f)
                .palette(0).colorStrength(1.0f)
                .metalness(0.2f).roughness(0.5f));

        // --- Detail camera from the traveller manifest ---
        p.put("MENGER_WALL", () -> SceneBuilder.menger()
                .camera(-0.21993f, 1.03134f, -2.08363f).lookAt(-0.57876f, -0.08390f, -0.96397f).fov(50)
                .gradient(ICE)
                .pathTracing(true).skyType(1)
                .lightDir(2, 3, -2).lightIntensity(1.3f)
                .ambientColor(0.12f, 0.14f, 0.20f).ambientIntensity(0.35f)
                .palette(4).colorStrength(0.9f)
                .metalness(0.4f).roughness(0.35f));

        return p;
    }

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "presets";
        String previewDir = args.length > 1 ? args[1] : "presets_preview";
        String[] res = (args.length > 2 ? args[2] : "640x360").split("x");
        int W = Integer.parseInt(res[0]), H = Integer.parseInt(res[1]);
        int samples = args.length > 3 ? Integer.parseInt(args[3]) : 24;

        new File(outDir).mkdirs();
        new File(previewDir).mkdirs();

        Map<String, Supplier<SceneBuilder>> specs = presets();
        List<String> written = new ArrayList<>();
        for (var e : specs.entrySet()) {
            e.getValue().get().writeTo(new File(outDir, e.getKey() + ".frac"));
            written.add(e.getKey());
        }
        System.out.printf("wrote %d presets to %s%n", written.size(), new File(outDir).getAbsolutePath());

        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});

        System.out.printf("%n=== previews (%dx%d, %d spp) ===%n", W, H, samples);
        for (String name : written) {
            File frac = new File(outDir, name + ".frac");
            FractalConfig cfg = FractalConfigManager.load(frac);
            FractalType type = cfg.getFractalTypeEnum();
            controller.setFractalType(type);
            AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
            cfg.applyTo(params);
            // The gradient only reaches the GPU through this call; without it every
            // render comes out monochrome regardless of the stops in the preset.
            controller.updatePaletteTexture(params.getCustomGradient());

            controller.setExportSize(W, H);
            long t0 = System.nanoTime();
            controller.exportToPNG(new File(previewDir, name + ".png"), samples, p -> {}, () -> false).get();
            System.out.printf("  %-22s %-22s %6d ms%n", name, type, (System.nanoTime() - t0) / 1_000_000);
            System.out.flush();
        }
        System.out.println();
        System.out.println("previews -> " + new File(previewDir).getAbsolutePath());
        System.exit(0);
    }
}
