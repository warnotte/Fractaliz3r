package org.fractalizer.test;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.MandelbulbParams;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

/**
 * Before/After comparison at 3 zoom levels.
 * Before: eps=1e-4, steps=200 (current defaults)
 * After:  eps=1e-5, steps=400
 *
 * Usage: mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.PrecisionCompareTest"
 */
public class PrecisionCompareTest {

    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;
    private static final int SAMPLES = 16;

    // Camera positions for 3 zoom levels
    // Far: whole Mandelbulb visible
    static final float[] FAR_POS = {0f, 0f, -3f};
    static final float[] FAR_QUAT = {1f, 0f, 0f, 0f};

    // Medium: half the Mandelbulb fills the frame
    static final float[] MED_POS = {0.6f, 0.2f, -1.5f};
    static final float[] MED_QUAT = {0.98f, -0.05f, -0.18f, -0.01f};

    // Close: from the TestFloat preset (surface detail)
    static final float[] CLOSE_POS = {0.8891418f, -0.048582703f, -0.5969914f};
    static final float[] CLOSE_QUAT = {0.9602462f, -0.19065921f, -0.1999988f, -0.03970813f};

    public static void main(String[] args) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        File outDir = new File("out/test_output/before_after");
        if (!outDir.exists()) outDir.mkdirs();

        System.out.println("=== Before/After Precision Test ===");

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((msg, progress) ->
            System.out.printf("  [%.0f%%] %s%n", progress * 100, msg)
        );

        String[][] zooms = {
            {"far",    "" + FAR_POS[0],   "" + FAR_POS[1],   "" + FAR_POS[2],
                       "" + FAR_QUAT[0],  "" + FAR_QUAT[1],  "" + FAR_QUAT[2],  "" + FAR_QUAT[3]},
            {"medium", "" + MED_POS[0],   "" + MED_POS[1],   "" + MED_POS[2],
                       "" + MED_QUAT[0],  "" + MED_QUAT[1],  "" + MED_QUAT[2],  "" + MED_QUAT[3]},
            {"close",  "" + CLOSE_POS[0], "" + CLOSE_POS[1], "" + CLOSE_POS[2],
                       "" + CLOSE_QUAT[0],"" + CLOSE_QUAT[1],"" + CLOSE_QUAT[2],"" + CLOSE_QUAT[3]},
        };

        // Before and After configs
        float[] epsilons = {0.0001f, 0.00001f};
        int[] steps = {200, 400};
        String[] labels = {"BEFORE", "AFTER"};

        for (String[] zoom : zooms) {
            String zoomName = zoom[0];
            float px = Float.parseFloat(zoom[1]);
            float py = Float.parseFloat(zoom[2]);
            float pz = Float.parseFloat(zoom[3]);
            float qw = Float.parseFloat(zoom[4]);
            float qx = Float.parseFloat(zoom[5]);
            float qy = Float.parseFloat(zoom[6]);
            float qz = Float.parseFloat(zoom[7]);

            for (int ci = 0; ci < 2; ci++) {
                controller.setFractalType(FractalType.MANDELBULB);
                AbstractFractalParams params = (AbstractFractalParams) controller.getParams();

                MandelbulbParams mb = (MandelbulbParams) params;
                mb.setMaxIterations(15);
                mb.setPower(8f);
                mb.setBailout(2f);

                Camera cam = params.getCamera();
                cam.setPosition(px, py, pz);
                cam.setQuaternion(qw, qx, qy, qz);

                params.setEpsilon(epsilons[ci]);
                params.setMaxRaySteps(steps[ci]);
                params.setPathTracingEnabled(false);

                String name = String.format("%s_%s.png", zoomName, labels[ci].toLowerCase());
                File outFile = new File(outDir, name);
                controller.setExportSize(WIDTH, HEIGHT);

                System.out.printf("  %s %s (eps=%.1e, steps=%d)...", labels[ci], zoomName, epsilons[ci], steps[ci]);
                long start = System.currentTimeMillis();

                CompletableFuture<Void> future = controller.exportToPNG(
                    outFile, SAMPLES, progress -> {}, () -> false
                );
                future.get();

                long elapsed = System.currentTimeMillis() - start;
                System.out.printf(" %dms (%dKB)%n", elapsed, outFile.length() / 1024);
            }
        }

        System.out.println("\n=== Done ===");
        System.out.println("Output: " + outDir.getAbsolutePath());
        System.exit(0);
    }
}
