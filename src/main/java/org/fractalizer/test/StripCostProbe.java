package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.engine.GLSLEngine;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.ui.GLSLFractalizerController;

import java.io.File;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * What a sample costs in strips against whole, on the GPU.
 *
 * The refinement of a heavy scene draws each sample as strips it can stop between. This
 * probe times, with GPU timer queries, one whole sample and the same sample as N strips,
 * with the pass bound once for all strips and again with it bound before every strip (as
 * the scheduler must, since a readback may run between two). The difference is the price
 * of interruptibility, and says whether it is the scissor, the rebinding, or neither.
 *
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.StripCostProbe" \
 *       -Dexec.args="presets/ALBEDO_039.frac 1920x1080"
 */
public class StripCostProbe {

    public static void main(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "presets/ALBEDO_039.frac";
        String[] res = (args.length > 1 ? args[1] : "1920x1080").split("x");
        int w = Integer.parseInt(res[0]), h = Integer.parseInt(res[1]);

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        FractalConfig cfg = FractalConfigManager.load(new File(spec));
        controller.setFractalType(cfg.getFractalTypeEnum());
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        cfg.applyTo(params);
        controller.updatePaletteTexture(params.getCustomGradient());
        controller.renderStill(64, 36, 1, () -> false);
        GLSLEngine engine = controller.getEngine();
        engine.resize(w, h);
        Map<String, Object> uniforms = controller.buildUniformsForProbe();

        System.out.printf("=== StripCostProbe: %s at %dx%d, GPU time per sample ===%n", spec, w, h);
        for (int round = 0; round < 2; round++) {
            long[] whole = {0};
            engine.postAndWait(() -> {
                engine.resetAccumulation();
                GLSLEngine.GpuTimer t = engine.timer();
                t.begin(); engine.renderSamples(uniforms, 1); t.end();
                engine.fence().await();
                whole[0] = t.elapsedNs();
            });
            System.out.printf("  whole sample                 %6d ms%n", whole[0] / 1_000_000);
            for (int strips : new int[]{4, 12, 36}) {
                final int n = strips;
                long[] once = {0}, each = {0}, eachFenced = {0};
                engine.postAndWait(() -> {
                    // strips, pass bound once
                    engine.resetAccumulation();
                    GLSLEngine.GpuTimer t = engine.timer();
                    t.begin();
                    engine.beginSample(uniforms);
                    engine.drawRowsBoundOnce(0, h, n);
                    engine.commitSample();
                    t.end();
                    engine.fence().await();
                    once[0] = t.elapsedNs();
                    // strips, pass rebound before each (the scheduler's way), one submission
                    engine.resetAccumulation();
                    t = engine.timer();
                    t.begin();
                    engine.beginSample(uniforms);
                    for (int b = 0; b < n; b++) { int y0 = h * b / n, y1 = h * (b + 1) / n; engine.drawRows(y0, y1 - y0); }
                    engine.commitSample();
                    t.end();
                    engine.fence().await();
                    each[0] = t.elapsedNs();
                    // strips, rebound and fenced one by one (a wait between strips)
                    engine.resetAccumulation();
                    long sum = 0;
                    engine.beginSample(uniforms);
                    for (int b = 0; b < n; b++) {
                        int y0 = h * b / n, y1 = h * (b + 1) / n;
                        GLSLEngine.GpuTimer ts = engine.timer();
                        ts.begin(); engine.drawRows(y0, y1 - y0); ts.end();
                        engine.fence().await();
                        sum += ts.elapsedNs();
                    }
                    engine.commitSample();
                    eachFenced[0] = sum;
                });
                // strips with one submission kept in flight (the scheduler's pattern): submit
                // strip b, then wait for strip b-1
                long[] pipelined = {0}, wall = {0};
                engine.postAndWait(() -> {
                    engine.resetAccumulation();
                    long sum = 0, t0 = System.nanoTime();
                    engine.beginSample(uniforms);
                    GLSLEngine.GpuFence prevFence = null; GLSLEngine.GpuTimer prevTimer = null;
                    for (int b = 0; b < n; b++) {
                        int y0 = h * b / n, y1 = h * (b + 1) / n;
                        GLSLEngine.GpuTimer ts = engine.timer();
                        ts.begin(); engine.drawRows(y0, y1 - y0); ts.end();
                        GLSLEngine.GpuFence f = engine.fence();
                        if (prevFence != null) { prevFence.await(); sum += prevTimer.elapsedNs(); }
                        prevFence = f; prevTimer = ts;
                    }
                    prevFence.await(); sum += prevTimer.elapsedNs();
                    engine.commitSample();
                    pipelined[0] = sum; wall[0] = System.nanoTime() - t0;
                });
                // one in flight, fences only, no timer queries: wall clock
                long[] wallNoTimer = {0};
                engine.postAndWait(() -> {
                    engine.resetAccumulation();
                    long t0 = System.nanoTime();
                    engine.beginSample(uniforms);
                    GLSLEngine.GpuFence prevFence = null;
                    for (int b = 0; b < n; b++) {
                        int y0 = h * b / n, y1 = h * (b + 1) / n;
                        engine.drawRows(y0, y1 - y0);
                        GLSLEngine.GpuFence f = engine.fence();
                        if (prevFence != null) prevFence.await();
                        prevFence = f;
                    }
                    prevFence.await();
                    engine.commitSample();
                    wallNoTimer[0] = System.nanoTime() - t0;
                });
                // two in flight, fences only
                long[] wallTwo = {0};
                engine.postAndWait(() -> {
                    engine.resetAccumulation();
                    long t0 = System.nanoTime();
                    engine.beginSample(uniforms);
                    java.util.ArrayDeque<GLSLEngine.GpuFence> q = new java.util.ArrayDeque<>();
                    for (int b = 0; b < n; b++) {
                        int y0 = h * b / n, y1 = h * (b + 1) / n;
                        engine.drawRows(y0, y1 - y0);
                        q.add(engine.fence());
                        if (q.size() > 2) q.poll().await();
                    }
                    while (!q.isEmpty()) q.poll().await();
                    engine.commitSample();
                    wallTwo[0] = System.nanoTime() - t0;
                });
                // fences without flush, one and two in flight
                long[] nf1 = {0}, nf2 = {0};
                for (int depth = 1; depth <= 2; depth++) {
                    final int d = depth; final long[] out = depth == 1 ? nf1 : nf2;
                    engine.postAndWait(() -> {
                        engine.resetAccumulation();
                        long t0 = System.nanoTime();
                        engine.beginSample(uniforms);
                        java.util.ArrayDeque<GLSLEngine.GpuFence> q = new java.util.ArrayDeque<>();
                        for (int b = 0; b < n; b++) {
                            int y0 = h * b / n, y1 = h * (b + 1) / n;
                            engine.drawRows(y0, y1 - y0);
                            q.add(engine.fence(false));
                            if (q.size() > d) q.poll().await();
                        }
                        while (!q.isEmpty()) q.poll().await();
                        engine.commitSample();
                        out[0] = System.nanoTime() - t0;
                    });
                }
                System.out.printf("  %2d strips: bound once %4d   rebound each %4d   fence each %4d   1 in flight %4d   2 in flight %4d   no-flush 1 in flight %4d   no-flush 2 in flight %4d%n",
                        n, once[0] / 1_000_000, each[0] / 1_000_000, eachFenced[0] / 1_000_000, wallNoTimer[0] / 1_000_000, wallTwo[0] / 1_000_000,
                        nf1[0] / 1_000_000, nf2[0] / 1_000_000);
            }
        }
        controller.close();
        Platform.exit();
        System.exit(0);
    }
}
