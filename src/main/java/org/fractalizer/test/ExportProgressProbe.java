package org.fractalizer.test;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures how far ahead of the work an export's progress bar runs.
 *
 * engine.renderSamples() submits GL commands and returns; runOnGLThread waits for the
 * commands to be <em>issued</em>, not for the GPU to finish them. The export loop can
 * therefore push every batch into the driver queue far faster than the GPU drains it, so
 * the bar reaches 100% while the image is still rendering — and then sits there.
 *
 * This times the first report of 100% against the moment the export future actually
 * completes. A healthy export has those within a batch of each other.
 *
 * Usage:
 *   -Dexec.args="&lt;file.frac&gt; &lt;WxH&gt; &lt;samples&gt;"
 */
public class ExportProgressProbe {

    public static void main(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "presets/JULIA_BULB_OVERVIEW.frac";
        String[] res = (args.length > 1 ? args[1] : "2600x1600").split("x");
        int W = Integer.parseInt(res[0]), H = Integer.parseInt(res[1]);
        int samples = args.length > 2 ? Integer.parseInt(args[2]) : 128;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        FractalConfig cfg = FractalConfigManager.load(new File(spec));
        controller.setFractalType(cfg.getFractalTypeEnum());
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        cfg.applyTo(params);
        controller.updatePaletteTexture(params.getCustomGradient());
        controller.setExportSize(W, H);

        System.out.printf("=== ExportProgressProbe: %dx%d, %d samples ===%n", W, H, samples);

        File out = File.createTempFile("progress_", ".png");
        AtomicLong fullAt = new AtomicLong(0);
        AtomicLong firstAt = new AtomicLong(0);
        long t0 = System.nanoTime();

        controller.exportToPNG(out, samples, p -> {
            long now = System.nanoTime();
            firstAt.compareAndSet(0, now);
            if (p >= 0.999 && fullAt.get() == 0) fullAt.set(now);
        }, () -> false).get();

        long end = System.nanoTime();
        double toFull = (fullAt.get() == 0) ? -1 : (fullAt.get() - t0) / 1e9;
        double total = (end - t0) / 1e9;

        System.out.printf(Locale.ROOT, "  first progress report   %8.2f s%n", (firstAt.get() - t0) / 1e9);
        System.out.printf(Locale.ROOT, "  bar reaches 100%%        %8.2f s%n", toFull);
        System.out.printf(Locale.ROOT, "  export actually done    %8.2f s%n", total);
        if (toFull >= 0) {
            double stuck = total - toFull;
            System.out.printf(Locale.ROOT, "  time spent at a full bar %7.2f s  (%.0f%% of the export)%n",
                    stuck, 100 * stuck / total);
        }
        out.delete();
        System.exit(0);
    }
}
