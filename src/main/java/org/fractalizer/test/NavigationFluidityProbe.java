package org.fractalizer.test;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.render.ViewportEvent;
import org.fractalizer.ui.GLSLFractalizerController;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * How fluid is the viewport while the camera moves?
 *
 * Drives the controller exactly as the app's render loop does: from the JavaFX thread, one
 * {@code requestRender} per animation frame while the camera turns, for a few seconds. It
 * measures what the user feels:
 *   - how long each request holds the JavaFX thread (a slider or a drag cannot be processed
 *     meanwhile),
 *   - the longest the JavaFX thread went without answering at all (a pinger posts to it),
 *   - how many preview images reached the viewport per second,
 *   - the delay between a request and the next image on screen,
 *   - and, with the refinement pass running, how long a camera move waits for its first
 *     preview image (the interrupt latency of the refinement).
 *
 * Usage:
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.NavigationFluidityProbe" \
 *       -Dexec.args="presets/JULIA_BULB_OVERVIEW.frac 1280x720 4"
 *   The scene is a .frac or a FractalType name; WxH is the viewport; the last arg is the
 *   number of seconds of navigation.
 */
public class NavigationFluidityProbe {

    public static void main(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "MANDELBULB";
        String[] res = (args.length > 1 ? args[1] : "1280x720").split("x");
        int w = Integer.parseInt(res[0]), h = Integer.parseInt(res[1]);
        double seconds = args.length > 2 ? Double.parseDouble(args[2]) : 4;

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        controller.setViewportSize(w, h);
        loadScene(controller, spec);
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        controller.renderStill(64, 36, 1, () -> false);   // compile off the FX thread, once

        System.out.printf("=== NavigationFluidityProbe: %s at %dx%d, previewScale %.2f, fast shading %s, %.0f s of camera motion ===%n",
                spec, w, h, params.getPreviewScale(), params.isPreviewFastShading(), seconds);

        // ---- listener: counts images, measures request -> image ----------------------------
        AtomicInteger images = new AtomicInteger();
        AtomicLong lastRequest = new AtomicLong(0);
        List<Long> latencyMs = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<CountDownLatch> nextImage = new AtomicReference<>(new CountDownLatch(1));
        AtomicReference<ViewportEvent.Phase> phase = new AtomicReference<>();
        controller.setViewportListener(new ViewportEvent.Listener() {
            @Override public void onImage(ViewportEvent.ViewportImage img) {
                images.incrementAndGet();
                if (lastRequest.get() > 0) latencyMs.add((System.nanoTime() - lastRequest.get()) / 1_000_000);
                nextImage.get().countDown();
            }
            @Override public void onStatus(ViewportEvent.Status st) { phase.set(st.phase()); }
        });

        // ---- A. navigation ---------------------------------------------------------------
        List<Long> callMs = Collections.synchronizedList(new ArrayList<>());
        AtomicLong worstStall = new AtomicLong(0);
        AtomicInteger stallsOver16 = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        Thread pinger = new Thread(() -> {
            while (done.getCount() > 0) {
                CountDownLatch pong = new CountDownLatch(1);
                long sent = System.nanoTime();
                Platform.runLater(pong::countDown);
                try { pong.await(); } catch (InterruptedException e) { return; }
                long gap = (System.nanoTime() - sent) / 1_000_000;
                worstStall.accumulateAndGet(gap, Math::max);
                if (gap > 16) stallsOver16.incrementAndGet();
                try { Thread.sleep(5); } catch (InterruptedException e) { return; }
            }
        }, "fx-pinger");
        pinger.setDaemon(true);

        long tStart = System.nanoTime();
        Platform.runLater(() -> {
            pinger.start();
            new AnimationTimer() {
                @Override public void handle(long now) {
                    if ((now - tStart) / 1e9 >= seconds) { stop(); done.countDown(); return; }
                    params.getCamera().rotate(0.4f, 0.1f);
                    long t0 = System.nanoTime();
                    lastRequest.set(t0);
                    controller.requestRender();
                    callMs.add((System.nanoTime() - t0) / 1_000_000);
                }
            }.start();
        });
        done.await((long) seconds + 60, TimeUnit.SECONDS);
        double elapsed = (System.nanoTime() - tStart) / 1e9;
        Thread.sleep(300);   // let the last images land
        pinger.interrupt();
        lastRequest.set(0);

        System.out.println("--- A. camera moving, one requestRender per frame from the JavaFX thread ---");
        System.out.printf("  frames requested        %5d   (%.1f per second)%n", callMs.size(), callMs.size() / elapsed);
        System.out.printf("  requestRender() on FX   mean %4d ms   p95 %4d ms   max %4d ms   <- time the UI cannot react%n",
                mean(callMs), pct(callMs, 95), max(callMs));
        System.out.printf("  FX thread stall         worst %4d ms   %d stalls over 16 ms%n", worstStall.get(), stallsOver16.get());
        System.out.printf("  images shown            %5d   (%.1f per second)%n", images.get(), images.get() / elapsed);
        System.out.printf("  request -> image        median %4d ms   p95 %4d ms   max %4d ms%n",
                pct(latencyMs, 50), pct(latencyMs, 95), max(latencyMs));

        // ---- B. interrupting the refinement -----------------------------------------------
        System.out.println("--- B. refinement running, then one camera move: wait for the first preview image ---");
        List<Long> interrupt = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            Platform.runLater(controller::refineNow);
            Thread.sleep(700);   // deep in the refinement
            CountDownLatch first = new CountDownLatch(1);
            nextImage.set(first);
            AtomicLong t0 = new AtomicLong();
            Platform.runLater(() -> {
                params.getCamera().rotate(0.4f, 0f);
                t0.set(System.nanoTime());
                controller.requestRender();
            });
            boolean got = first.await(60, TimeUnit.SECONDS);
            long ms = (System.nanoTime() - t0.get()) / 1_000_000;
            interrupt.add(got ? ms : -1);
            System.out.printf("  round %d: first preview image after %s (was %s)%n", round + 1,
                    got ? ms + " ms" : "NEVER (60 s)", phase.get());
        }
        System.out.printf("  interrupt latency       max %d ms%n", Collections.max(interrupt));

        controller.close();
        Platform.exit();
        System.exit(0);
    }

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

    private static long mean(List<Long> v) { if (v.isEmpty()) return 0; long s = 0; for (long x : v) s += x; return s / v.size(); }
    private static long max(List<Long> v) { return v.isEmpty() ? 0 : Collections.max(v); }
    private static long pct(List<Long> v, int p) {
        if (v.isEmpty()) return 0;
        List<Long> s = new ArrayList<>(v); Collections.sort(s);
        return s.get(Math.min(s.size() - 1, (int) Math.floor(s.size() * p / 100.0)));
    }
}
