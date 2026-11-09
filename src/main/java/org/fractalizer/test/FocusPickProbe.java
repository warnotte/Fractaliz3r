package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.engine.GLSLEngine;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.render.ViewportEvent;
import org.fractalizer.ui.GLSLFractalizerController;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Does a focus pick read the right depth, and does it take effect at once?
 *
 * A. Accuracy. The same scene accumulated N samples with adaptive sampling off (every pixel
 *    draws every sample: the depth in the accumulation's alpha over N is the reference) and
 *    on (converged pixels stop drawing). On the second, each pixel of a grid is read the way
 *    the engine reads it now (the variance image: drawn samples' depth over their count) and
 *    the way it used to (the accumulation's alpha over the global sample count), against
 *    the reference. The old read under-reads wherever a pixel stopped early.
 *
 * B. Latency. The viewport refining, a pick at the centre from the JavaFX thread as the app
 *    makes it on Ctrl+click: when the GL thread reached the read, when the result came back,
 *    whether the focal distance was set to it, and when the next image arrived.
 *
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.FocusPickProbe" \
 *       -Dexec.args="presets/JULIA_BULB_OVERVIEW.frac 640x360 64"
 *
 * Exit code 1 when the read is off the reference by more than 2 % at a surface pixel, or a
 * pick does not set the focal distance.
 */
public class FocusPickProbe {

    public static void main(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "MANDELBOX";
        String[] res = (args.length > 1 ? args[1] : "640x360").split("x");
        int w = Integer.parseInt(res[0]), h = Integer.parseInt(res[1]);
        int samples = args.length > 2 ? Integer.parseInt(args[2]) : 64;

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        controller.setViewportSize(w, h);
        loadScene(controller, spec);
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        controller.renderStill(64, 36, 1, () -> false);   // compile off the FX thread, once
        GLSLEngine engine = controller.getEngine();
        boolean ok = true;

        System.out.printf("=== FocusPickProbe: %s at %dx%d, %d samples ===%n", spec, w, h, samples);

        // ---- A. accuracy ------------------------------------------------------------------
        engine.resize(w, h);
        params.setAdaptiveSampling(false);
        Map<String, Object> uniforms = controller.buildUniformsForProbe();
        engine.resetAccumulation();
        engine.renderSamples(uniforms, samples);
        int gx = 16, gy = 9;
        float[] ref = new float[gx * gy];
        for (int j = 0; j < gy; j++)
            for (int i = 0; i < gx; i++)
                ref[j * gx + i] = engine.readDepthAt(px(i, gx, w), px(j, gy, h));

        params.setAdaptiveSampling(true);
        uniforms = controller.buildUniformsForProbe();
        engine.resetAccumulation();
        engine.renderSamples(uniforms, samples);
        float[] raw = engine.readRawImage();               // alpha = depth sum / global sample count
        List<Double> errNew = new ArrayList<>(), errOld = new ArrayList<>();
        for (int j = 0; j < gy; j++) {
            for (int i = 0; i < gx; i++) {
                float r = ref[j * gx + i];
                if (!(r > 0 && r < 100f)) continue;          // sky, or a pixel the background touches
                int x = px(i, gx, w), y = px(j, gy, h);
                float now = engine.readDepthAt(x, y);
                float old = raw[(y * w + x) * 4 + 3];
                errNew.add(Math.abs(now - r) / (double) r);
                errOld.add(Math.abs(old - r) / (double) r);
            }
        }
        System.out.printf("--- A. depth under %d surface pixels, adaptive sampling on, against every-sample reference ---%n", errNew.size());
        System.out.printf("  read now (variance image)          median %6.2f %%   worst %6.2f %%%n", pct(errNew, 50) * 100, max(errNew) * 100);
        System.out.printf("  read before (alpha / sample count) median %6.2f %%   worst %6.2f %%%n", pct(errOld, 50) * 100, max(errOld) * 100);
        ok &= !errNew.isEmpty() && max(errNew) < 0.02;

        // ---- B. latency through the viewport ------------------------------------------------
        System.out.println("--- B. viewport refining, a pick at the centre from the JavaFX thread ---");
        AtomicReference<CountDownLatch> nextImage = new AtomicReference<>(new CountDownLatch(1));
        controller.setViewportListener(new ViewportEvent.Listener() {
            @Override public void onImage(ViewportEvent.ViewportImage img) { nextImage.get().countDown(); }
            @Override public void onStatus(ViewportEvent.Status st) { }
        });
        params.setDofEnabled(true);
        params.setAperture(0.01f);
        CountDownLatch first = new CountDownLatch(1);
        nextImage.set(first);
        Platform.runLater(controller::requestRender);
        first.await(60, TimeUnit.SECONDS);
        for (int round = 0; round < 3; round++) {
            Platform.runLater(controller::refineNow);
            Thread.sleep(700);                               // deep in the refinement
            params.setFocalDistance(50f);                    // far from anything: a pick must change it
            CountDownLatch answered = new CountDownLatch(1), image = new CountDownLatch(1);
            AtomicLong t0 = new AtomicLong(), tAnswer = new AtomicLong(), tImage = new AtomicLong();
            float[] got = new float[2];
            Platform.runLater(() -> {
                t0.set(System.nanoTime());
                controller.pickFocalDistance(w / 2, h / 2, (depth, waitedMs) -> {
                    tAnswer.set(System.nanoTime());
                    got[0] = depth;
                    got[1] = params.getFocalDistance();
                    nextImage.set(image);
                    controller.requestRender();                // as the app does
                    answered.countDown();
                });
            });
            boolean a = answered.await(60, TimeUnit.SECONDS);
            boolean im = a && image.await(60, TimeUnit.SECONDS);
            if (im) tImage.set(System.nanoTime());
            boolean set = a && got[0] > 0 && got[1] == got[0];
            System.out.printf("  round %d: depth %.5g, focal distance %s, answer after %s, next image after %s%n",
                    round + 1, got[0], set ? "set to it" : "NOT SET (" + got[1] + ")",
                    a ? (tAnswer.get() - t0.get()) / 1_000_000 + " ms" : "NEVER (60 s)",
                    im ? (tImage.get() - t0.get()) / 1_000_000 + " ms" : "NEVER (60 s)");
            ok &= set && im;
        }

        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        controller.close();
        Platform.exit();
        System.exit(ok ? 0 : 1);
    }

    /** The centre of cell i of n across a size, in pixels. */
    private static int px(int i, int n, int size) { return (int) ((i + 0.5) * size / n); }

    private static void loadScene(GLSLFractalizerController controller, String spec) throws Exception {
        File f = new File(spec);
        if (f.isFile()) {
            FractalConfig cfg = FractalConfigManager.load(f);
            controller.setFractalType(cfg.getFractalTypeEnum());
            AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
            cfg.applyTo(params);
            controller.updatePaletteTexture(params.getCustomGradient());
            if (cfg.postProcess != null) controller.getEngine().getPostProcessParams().copyFrom(cfg.postProcess);
            controller.applyEnvironmentMap(cfg.effects.envMap);
        } else {
            controller.setFractalType(FractalType.valueOf(spec));
        }
    }

    private static double max(List<Double> v) { return v.isEmpty() ? 0 : Collections.max(v); }
    private static double pct(List<Double> v, int p) {
        if (v.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(v); Collections.sort(s);
        return s.get(Math.min(s.size() - 1, (int) Math.floor(s.size() * p / 100.0)));
    }
}
