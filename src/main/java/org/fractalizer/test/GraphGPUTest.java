package org.fractalizer.test;

import org.fractalizer.fractals.CustomShaderParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.graph.*;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import java.io.File;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

/**
 * GPU test for the graph compiler.
 * Compiles graph GLSL, loads it on the GPU via the custom shader pipeline,
 * and exports rendered PNGs for visual verification.
 *
 * Run: mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.GraphGPUTest"
 *
 * Output in test_output/graph/. Read the PNGs to verify:
 *   01_single_mandelbulb.png  — should look like a normal Mandelbulb
 *   02_union_mandelbulb_menger.png — two fractals merged
 *   03_union_transform.png — Mandelbulb + offset Menger side by side
 *   04_subtract_3fractals.png — complex nested CSG
 */
public class GraphGPUTest {

    private static final String OUTPUT_DIR = "out/test_output/graph";
    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;
    private static final int SAMPLES = 4;

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // Initialize JavaFX toolkit
        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        // Create output directory
        File outDir = new File(OUTPUT_DIR);
        if (!outDir.exists()) outDir.mkdirs();

        System.out.println("=== Graph GPU Test ===");
        System.out.println("Output: " + outDir.getAbsolutePath());

        // Initialize controller
        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((msg, progress) ->
            System.out.printf("  [%.0f%%] %s%n", progress * 100, msg)
        );

        // Switch to CUSTOM_SHADER mode (graph shaders go through this pipeline)
        controller.setFractalType(FractalType.CUSTOM_SHADER);

        // Run tests
        testSingleMandelbulb(controller);
        testUnionMandelbulbMenger(controller);
        testUnionWithTransform(controller);
        testSubtract3Fractals(controller);

        System.out.println("\n========================================");
        System.out.println("Results: " + passed + " passed, " + failed + " failed");
        System.out.println("Output: " + outDir.getAbsolutePath());
        System.exit(failed > 0 ? 1 : 0);
    }

    /**
     * Test 1: Single Mandelbulb via graph compiler.
     * Should look identical to the built-in Mandelbulb.
     */
    private static void testSingleMandelbulb(GLSLFractalizerController controller) {
        System.out.println("\n--- Test 1: Single Mandelbulb ---");
        try {
            GraphNode root = new FractalNode(FractalType.MANDELBULB);
            compileAndRender(controller, root, "01_single_mandelbulb.png");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
    }

    /**
     * Test 2: Union of Mandelbulb + Menger Sponge.
     * Both at origin, smooth blend — should see merged geometry.
     */
    private static void testUnionMandelbulbMenger(GLSLFractalizerController controller) {
        System.out.println("\n--- Test 2: Union Mandelbulb + Menger ---");
        try {
            GraphNode root = new CSGNode(
                CSGNode.Op.UNION,
                new FractalNode(FractalType.MANDELBULB),
                new FractalNode(FractalType.MENGER_SPONGE),
                0.1f
            );
            compileAndRender(controller, root, "02_union_mandelbulb_menger.png");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
    }

    /**
     * Test 3: Mandelbulb + offset Menger (via TransformNode).
     * Should see two distinct fractals side by side.
     */
    private static void testUnionWithTransform(GLSLFractalizerController controller) {
        System.out.println("\n--- Test 3: Union + Transform (offset Menger) ---");
        try {
            GraphNode root = new CSGNode(
                CSGNode.Op.UNION,
                new FractalNode(FractalType.MANDELBULB),
                new TransformNode(
                    new FractalNode(FractalType.MENGER_SPONGE),
                    new float[]{3.0f, 0.0f, 0.0f}
                ),
                0.0f // hard union
            );
            compileAndRender(controller, root, "03_union_transform.png");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
    }

    /**
     * Test 4: 3 fractals — Subtract(Union(Mandelbulb, Menger), Sierpinski).
     * Complex nested CSG. Verifies NVIDIA doesn't crash on 3-fractal shader.
     */
    private static void testSubtract3Fractals(GLSLFractalizerController controller) {
        System.out.println("\n--- Test 4: Subtract(Union(Mandelbulb, Menger), Sierpinski) ---");
        try {
            GraphNode root = new CSGNode(
                CSGNode.Op.SUBTRACT,
                new CSGNode(
                    CSGNode.Op.UNION,
                    new FractalNode(FractalType.MANDELBULB),
                    new FractalNode(FractalType.MENGER_SPONGE),
                    0.05f
                ),
                new TransformNode(
                    new FractalNode(FractalType.SIERPINSKI),
                    new float[]{0.0f, 0.0f, 0.0f},
                    new float[]{0, 0, 0},
                    0.8f // slightly larger to carve visible holes
                ),
                0.02f
            );
            compileAndRender(controller, root, "04_subtract_3fractals.png");
            passed++;
        } catch (Exception e) {
            System.err.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
    }

    // ========================================================================
    // Core: compile graph → load on GPU → render PNG
    // ========================================================================

    private static void compileAndRender(GLSLFractalizerController controller,
                                          GraphNode root, String filename) throws Exception {
        // 1. Compile the graph to GLSL
        GraphCompiler compiler = new GraphCompiler();
        String glsl = compiler.compile(root);
        Map<String, Object> uniforms = compiler.getUniforms(root);

        System.out.printf("  Compiled: %d chars GLSL, %d uniforms%n", glsl.length(), uniforms.size());

        // 2. Load on GPU via custom shader pipeline
        String error = controller.compileCustomShader(glsl);
        if (error != null) {
            throw new RuntimeException("GPU shader compilation failed:\n" + error);
        }
        System.out.println("  GPU compilation: OK");

        // 3. Set uniforms on the params (buildUniforms reads from CustomShaderParams.uniformValues)
        CustomShaderParams params = (CustomShaderParams) controller.getParams();
        params.clearUniformValues();
        for (Map.Entry<String, Object> entry : uniforms.entrySet()) {
            params.setUniformValue(entry.getKey(), entry.getValue());
        }

        // 4. Pull camera back a bit so we see multi-fractal scenes
        params.getCamera().setPosition(0, 0, -4.5f);

        // 5. Export
        controller.setExportSize(WIDTH, HEIGHT);
        File file = new File(OUTPUT_DIR, filename);
        long start = System.currentTimeMillis();

        CompletableFuture<Void> future = controller.exportToPNG(
            file, SAMPLES, progress -> {}, () -> false
        );
        future.get();

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("  Rendered in %dms → %s (%dKB)%n",
            elapsed, file.getName(), file.length() / 1024);
    }
}
