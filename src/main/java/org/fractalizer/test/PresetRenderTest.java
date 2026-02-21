package org.fractalizer.test;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

/**
 * Headless test that loads a .frac preset and renders a PNG.
 * Used as a baseline for visual regression / unit testing.
 *
 * Usage:
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.PresetRenderTest"
 *
 * Optional args:
 *   -Dexec.args="presets/MyScene.frac 1920x1080 16"
 *
 * Defaults: presets/TestFloat.frac, 960x540, 8 samples
 */
public class PresetRenderTest {

    public static void main(String[] args) throws Exception {
        String fracPath = args.length >= 1 ? args[0] : "presets/TestFloat.frac";
        int width = 960, height = 540;
        int samples = 8;

        if (args.length >= 2) {
            String[] res = args[1].split("x");
            width = Integer.parseInt(res[0]);
            height = Integer.parseInt(res[1]);
        }
        if (args.length >= 3) {
            samples = Integer.parseInt(args[2]);
        }

        File fracFile = new File(fracPath);
        if (!fracFile.exists()) {
            System.err.println("Preset not found: " + fracFile.getAbsolutePath());
            System.exit(1);
        }

        // Initialize JavaFX toolkit
        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        // Output directory
        File outDir = new File("test_output");
        if (!outDir.exists()) outDir.mkdirs();

        System.out.println("=== Preset Render Test ===");
        System.out.printf("Preset: %s%n", fracPath);
        System.out.printf("Resolution: %dx%d, Samples: %d%n", width, height, samples);

        // Load preset
        FractalConfig config = FractalConfigManager.load(fracFile);
        FractalType fractalType = config.getFractalTypeEnum();
        System.out.printf("Fractal: %s%n", fractalType.getDisplayName());

        // Initialize controller + compile shaders
        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((msg, progress) ->
            System.out.printf("  [%.0f%%] %s%n", progress * 100, msg)
        );

        // Apply preset
        controller.setFractalType(fractalType);
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        config.applyTo(params);

        if (params.getCustomGradient() != null) {
            controller.updatePaletteTexture(params.getCustomGradient());
        }

        // Render
        controller.setExportSize(width, height);
        String outName = fracFile.getName().replace(".frac", "") + "_" + width + "x" + height + ".png";
        File outFile = new File(outDir, outName);

        System.out.printf("%nRendering...%n");
        long start = System.currentTimeMillis();

        CompletableFuture<Void> future = controller.exportToPNG(
            outFile, samples,
            progress -> System.out.printf("\r  Progress: %.0f%%", progress * 100),
            () -> false
        );
        future.get();

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("%n  Done in %dms → %s (%dKB)%n", elapsed, outFile.getName(), outFile.length() / 1024);
        System.out.println("Output: " + outFile.getAbsolutePath());

        System.exit(0);
    }
}
