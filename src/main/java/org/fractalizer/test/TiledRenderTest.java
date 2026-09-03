package org.fractalizer.test;

import org.fractalizer.ui.GLSLFractalizerController;
import org.fractalizer.fractals.FractalType;

import javafx.application.Platform;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

/**
 * Headless visual test for tiled rendering.
 * Exports the same scene at multiple resolutions and saves PNGs for visual comparison.
 *
 * Usage: mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.TiledRenderTest"
 */
public class TiledRenderTest {

    private static final String OUTPUT_DIR = "out/test_output";
    private static final int SAMPLES = 4; // Low samples for speed

    public static void main(String[] args) throws Exception {
        // Initialize JavaFX toolkit (needed for Platform.runLater in export callbacks)
        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(() -> latch.countDown());
        latch.await();

        // Create output directory
        File outDir = new File(OUTPUT_DIR);
        if (!outDir.exists()) outDir.mkdirs();

        System.out.println("=== Tiled Render Test ===");

        // Initialize controller (creates hidden GL window + compiles shaders)
        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((msg, progress) ->
            System.out.printf("  [%.0f%%] %s%n", progress * 100, msg)
        );

        // Use Mandelbulb with default params
        controller.setFractalType(FractalType.MANDELBULB);

        // Test 1: 1920x1080 - no tiling (reference)
        exportTest(controller, 1920, 1080, "01_1920x1080_reference.png");

        // Test 2: 4096x4096 - single tile (boundary case)
        exportTest(controller, 4096, 4096, "02_4096x4096_boundary.png");

        // Test 3: 5000x2000 - 2x1 tiles (just over threshold on X)
        exportTest(controller, 5000, 2000, "03_5000x2000_2x1tiles.png");

        // Test 4: 8192x4096 - 2x1 tiles (360 8K preset)
        exportTest(controller, 8192, 4096, "04_8192x4096_2x1tiles.png");

        // Test 5: 7680x4320 - 2x2 tiles (8K preset)
        exportTest(controller, 7680, 4320, "05_7680x4320_8K.png");

        System.out.println("\n=== All tests complete ===");
        System.out.println("Output in: " + outDir.getAbsolutePath());

        System.exit(0);
    }

    private static void exportTest(GLSLFractalizerController controller, int w, int h, String filename) throws Exception {
        System.out.printf("%nExporting %s (%dx%d)...%n", filename, w, h);
        long start = System.currentTimeMillis();

        controller.setExportSize(w, h);
        File file = new File(OUTPUT_DIR, filename);

        CompletableFuture<Void> future = controller.exportToPNG(
            file, SAMPLES,
            progress -> {}, // silent progress
            () -> false     // never cancel
        );
        future.get(); // block until done

        long elapsed = System.currentTimeMillis() - start;
        long sizeKB = file.length() / 1024;
        System.out.printf("  Done in %dms, file size: %dKB%n", elapsed, sizeKB);
    }
}
