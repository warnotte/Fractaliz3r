package org.fractalizer.test;

import org.fractalizer.ui.GLSLFractalizerController;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.engine.Camera;

import javafx.application.Platform;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

/**
 * Visual regression test for zoom-out rendering.
 * Renders Mandelbulb at camera Z positions from -2.3 to -11.5 in 3 modes:
 *   1. Standard (no cone tracing, no path tracing)
 *   2. Cone tracing
 *   3. Path tracing
 *
 * Usage: mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.ZoomOutTest"
 */
public class ZoomOutTest {

    private static final String OUTPUT_DIR = "out/test_output/zoom_out";
    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;
    private static final int SAMPLES = 4;
    private static final int PT_SAMPLES = 16; // path tracing needs more

    // Camera Z positions: progressive zoom out (15 steps for smooth transition)
    private static final float[] Z_POSITIONS = {
        -2.3f, -2.9f, -3.5f, -4.0f, -4.5f, -5.0f, -5.5f, -6.0f,
        -6.5f, -7.0f, -8.0f, -9.0f, -10.0f, -11.0f, -11.5f
    };

    public static void main(String[] args) throws Exception {
        // Initialize JavaFX toolkit
        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        // Create output directory
        File outDir = new File(OUTPUT_DIR);
        if (!outDir.exists()) outDir.mkdirs();

        System.out.println("=== Zoom-Out Rendering Test ===");
        System.out.printf("Resolution: %dx%d, Samples: %d (PT: %d)%n", WIDTH, HEIGHT, SAMPLES, PT_SAMPLES);

        // Initialize controller
        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((msg, progress) ->
            System.out.printf("  [%.0f%%] %s%n", progress * 100, msg)
        );

        controller.setFractalType(FractalType.MANDELBULB);
        controller.setExportSize(WIDTH, HEIGHT);

        // --- Mode 1: Standard (no cone, no PT) ---
        renderSeries(controller, outDir, "standard", false, false, SAMPLES);

        // --- Mode 2: Cone tracing ---
        renderSeries(controller, outDir, "cone", true, false, SAMPLES);

        // --- Mode 3: Path tracing ---
        renderSeries(controller, outDir, "pt", false, true, PT_SAMPLES);

        // --- Mode 4: Debug NORMALS (bypasses all shading/fog/shadows) ---
        {
            AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
            params.setRenderMode(AbstractFractalParams.RENDER_NORMALS);
            renderSeries(controller, outDir, "normals", false, false, SAMPLES);
            params.setRenderMode(AbstractFractalParams.RENDER_FINAL);
        }

        // --- Mode 5: Debug ITERATIONS (shows ray step count as color) ---
        {
            AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
            params.setRenderMode(AbstractFractalParams.RENDER_ITERATIONS);
            renderSeries(controller, outDir, "iters", false, false, SAMPLES);
            params.setRenderMode(AbstractFractalParams.RENDER_FINAL);
        }

        // --- Mode 6: Debug HIT/MISS (white=hit, gray=distance traveled) ---
        {
            AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
            params.setRenderMode(9); // custom debug mode
            renderSeries(controller, outDir, "hitmiss", false, false, SAMPLES);
            params.setRenderMode(AbstractFractalParams.RENDER_FINAL);
        }

        System.out.printf("%n=== Test complete === Output: %s%n", outDir.getAbsolutePath());
        System.exit(0);
    }

    private static void renderSeries(GLSLFractalizerController controller, File outDir,
                                      String label, boolean cone, boolean pt, int samples) throws Exception {
        System.out.printf("%n--- Mode: %s ---%n", label);
        for (float z : Z_POSITIONS) {
            AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
            Camera cam = params.getCamera();
            cam.setPosition(0f, 0f, z);
            params.setConeTracingEnabled(cone);
            params.setPathTracingEnabled(pt);

            String filename = String.format("%s_z%.1f.png", label, z);
            System.out.printf("  Rendering %s (camZ=%.1f)...", filename, z);
            long start = System.currentTimeMillis();

            File file = new File(outDir, filename);
            CompletableFuture<Void> future = controller.exportToPNG(
                file, samples, progress -> {}, () -> false
            );
            future.get();

            long elapsed = System.currentTimeMillis() - start;
            System.out.printf(" %dms (%dKB)%n", elapsed, file.length() / 1024);
        }
    }
}
