package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.render.ViewportEvent;
import org.fractalizer.ui.GLSLFractalizerController;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The refinement pass: its throughput and its cadence.
 *
 * Runs the viewport scheduler's refinement on a scene (preview first, then
 * {@code refineNow}) and reports the time per sample, the number of images the viewport
 * received, and the longest gap between two of them. Interruptibility is measured by
 * NavigationFluidityProbe; this one guards the cost of it: slicing a sample into steps
 * must not make the refinement slower than whole samples were.
 *
 * Usage:
 *   -Dexec.args="&lt;file.frac&gt; &lt;WxH&gt; &lt;samples&gt;"
 */
public class ResponsivenessProbe {

    public static void main(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "presets/JULIA_BULB_OVERVIEW.frac";
        String[] res = (args.length > 1 ? args[1] : "1280x720").split("x");
        int w = Integer.parseInt(res[0]), h = Integer.parseInt(res[1]);
        int samples = args.length > 2 ? Integer.parseInt(args[2]) : 24;

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        controller.setViewportSize(w, h);
        controller.setFullSamples(samples);
        FractalConfig cfg = FractalConfigManager.load(new File(spec));
        controller.setFractalType(cfg.getFractalTypeEnum());
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        cfg.applyTo(params);
        controller.updatePaletteTexture(params.getCustomGradient());
        controller.renderStill(64, 36, 1, () -> false);   // compile once, off the FX thread
        controller.getViewport().setAutoRefine(false);     // the probe starts the refinement itself

        List<Long> imageTimes = new ArrayList<>();
        CountDownLatch previewDone = new CountDownLatch(1);
        CountDownLatch refineDone = new CountDownLatch(1);
        long[] doneStatus = new long[2];
        controller.setViewportListener(new ViewportEvent.Listener() {
            @Override public void onImage(ViewportEvent.ViewportImage img) {
                imageTimes.add(System.nanoTime());
                previewDone.countDown();
            }
            @Override public void onStatus(ViewportEvent.Status st) {
                if (st.phase() == ViewportEvent.Phase.DONE) { doneStatus[0] = st.samples(); doneStatus[1] = st.elapsedMs(); refineDone.countDown(); }
            }
        });

        Platform.runLater(controller::requestRender);
        previewDone.await(60, TimeUnit.SECONDS);
        Thread.sleep(200);
        imageTimes.clear();
        long t0 = System.nanoTime();
        Platform.runLater(controller::refineNow);
        boolean finished = refineDone.await(600, TimeUnit.SECONDS);
        long total = (System.nanoTime() - t0) / 1_000_000;

        long worstGap = 0;
        for (int i = 1; i < imageTimes.size(); i++) worstGap = Math.max(worstGap, (imageTimes.get(i) - imageTimes.get(i - 1)) / 1_000_000);

        System.out.printf("=== ResponsivenessProbe: %s at %dx%d, %d samples, path traced %s ===%n",
                spec, w, h, samples, params.isPathTracingEnabled());
        System.out.printf("  refinement            %s in %d ms (%d samples reported in %d ms)%n",
                finished ? "complete" : "NOT COMPLETE", total, doneStatus[0], doneStatus[1]);
        System.out.printf("  per sample            %5d ms%n", finished && doneStatus[0] > 0 ? doneStatus[1] / doneStatus[0] : -1);
        System.out.printf("  images to the viewport %4d   worst gap between two %d ms%n", imageTimes.size(), worstGap);

        controller.close();
        Platform.exit();
        System.exit(finished ? 0 : 1);
    }
}
