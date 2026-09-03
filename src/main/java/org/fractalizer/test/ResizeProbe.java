package org.fractalizer.test;

import org.fractalizer.engine.GLSLEngine;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/**
 * Times the framebuffer reallocation that a resize triggers.
 *
 * The interactive preview renders at a fraction of the viewport, so every burst of
 * navigation crosses two resizes: down to preview size when the camera starts moving, and
 * back up when the full-quality pass begins. Each one runs recreateFramebuffer(), which
 * deletes and recreates seven textures and five framebuffers (accum, display, variance and
 * two bloom targets) and resets the sample count. This measures what that actually costs,
 * so the decision to keep it or to render into a sub-rectangle instead is made on a number.
 *
 * Usage:
 *   -Dexec.args="&lt;WxH&gt; &lt;previewScale&gt; &lt;rounds&gt;"
 *   -Dexec.args="1920x1080 0.5 20"
 */
public class ResizeProbe {

    static double median(long[] v) {
        long[] c = v.clone();
        Arrays.sort(c);
        return c[c.length / 2] / 1e6;
    }

    public static void main(String[] args) throws Exception {
        String[] res = (args.length > 0 ? args[0] : "1920x1080").split("x");
        int W = Integer.parseInt(res[0]), H = Integer.parseInt(res[1]);
        float scale = args.length > 1 ? Float.parseFloat(args[1]) : 0.5f;
        int rounds = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        int pw = Math.max(160, Math.round(W * scale)), ph = Math.max(90, Math.round(H * scale));

        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        GLSLEngine engine = controller.getEngine();

        System.out.printf("=== ResizeProbe: %dx%d <-> %dx%d, %d rounds ===%n", W, H, pw, ph, rounds);

        // Warm up: first allocation at each size pays for driver-side setup.
        engine.resize(W, H); engine.glSync();
        engine.resize(pw, ph); engine.glSync();

        long[] down = new long[rounds], up = new long[rounds], noop = new long[rounds];
        for (int i = 0; i < rounds; i++) {
            engine.resize(W, H); engine.glSync();

            long t0 = System.nanoTime();
            engine.resize(pw, ph); engine.glSync();
            down[i] = System.nanoTime() - t0;

            // A resize to the size already in use returns without touching anything; this
            // is the floor the measurement sits on (thread hop plus glFinish).
            long t1 = System.nanoTime();
            engine.resize(pw, ph); engine.glSync();
            noop[i] = System.nanoTime() - t1;

            long t2 = System.nanoTime();
            engine.resize(W, H); engine.glSync();
            up[i] = System.nanoTime() - t2;
        }

        System.out.printf(Locale.ROOT, "  shrink to preview   median %7.2f ms%n", median(down));
        System.out.printf(Locale.ROOT, "  grow back to full   median %7.2f ms%n", median(up));
        System.out.printf(Locale.ROOT, "  same-size (no-op)   median %7.2f ms   <- measurement floor%n", median(noop));
        System.out.printf(Locale.ROOT, "  cost of one navigation burst: %.2f ms of pure reallocation%n",
                median(down) + median(up) - 2 * median(noop));
        System.exit(0);
    }
}
