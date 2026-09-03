package org.fractalizer.test;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/**
 * Measures how long a full-quality pass can ignore you.
 *
 * ProgressiveRenderer submits a batch of samples to the GL thread as one blocking call and
 * only tests the cancelled flag between batches, so the gap between two progress callbacks
 * is exactly the worst-case delay before navigation can interrupt a render. That gap, not
 * the total render time, is what a hesitant user actually feels when they nudge the camera
 * and the viewport refuses to respond.
 *
 * Usage:
 *   -Dexec.args="&lt;file.frac&gt; &lt;WxH&gt; &lt;samples&gt;"
 */
public class ResponsivenessProbe {

    public static void main(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "presets/JULIA_BULB_OVERVIEW.frac";
        String[] res = (args.length > 1 ? args[1] : "1280x720").split("x");
        int W = Integer.parseInt(res[0]), H = Integer.parseInt(res[1]);
        int samples = args.length > 2 ? Integer.parseInt(args[2]) : 24;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        FractalConfig cfg = FractalConfigManager.load(new File(spec));
        controller.setFractalType(cfg.getFractalTypeEnum());
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        cfg.applyTo(params);
        params.setPathTracingEnabled(true);
        controller.updatePaletteTexture(params.getCustomGradient());
        controller.setViewportSize(W, H);
        controller.setFullSamples(samples);

        System.out.printf("=== ResponsivenessProbe: %s at %dx%d, %d samples, path traced ===%n",
                spec, W, H, samples);

        List<Long> gaps = new ArrayList<>();
        long[] last = { System.nanoTime() };
        CountDownLatch done = new CountDownLatch(1);

        controller.renderFull(img -> { }, progress -> {
            long now = System.nanoTime();
            gaps.add(now - last[0]);
            last[0] = now;
            if (progress >= 1.0) done.countDown();
        }, null);

        if (!done.await(240, java.util.concurrent.TimeUnit.SECONDS)) {
            System.out.println("  (timed out waiting for the render to finish)");
        }

        if (gaps.isEmpty()) {
            System.out.println("  no progress ticks observed");
        } else {
            long max = 0, sum = 0;
            for (long g : gaps) { max = Math.max(max, g); sum += g; }
            System.out.printf(Locale.ROOT, "  ticks           %d%n", gaps.size());
            System.out.printf(Locale.ROOT, "  mean tick       %8.0f ms%n", sum / (double) gaps.size() / 1e6);
            System.out.printf(Locale.ROOT, "  LONGEST tick    %8.0f ms   <- worst case before a cancel can land%n",
                    max / 1e6);
        }
        System.exit(0);
    }
}
