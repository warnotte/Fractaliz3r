package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.engine.Camera;
import org.fractalizer.engine.GLSLEngine;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.PrimitiveNode;
import org.fractalizer.graph.TransformNode;
import org.fractalizer.render.ViewportEvent;
import org.fractalizer.ui.GLSLFractalizerController;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The probe (the scene's own program asked where camera rays land) and the focus pick that
 * uses it: is the distance right, and does a pick take effect at once?
 *
 * A. Exactness. A sphere (a node graph primitive, off centre) seen at an angle: hit or miss
 *    and the distance along each of a grid of rays against the ray-sphere intersection
 *    computed here in double, and the clearance against |camera - centre| - radius. This
 *    checks the rays themselves (aspect, orientation, y up) and the reading, not a fractal.
 *    The march, the view's own, stops within a pixel's footprint of the surface along the
 *    normal (the cone's epsilon), so that is the tolerance, not a fraction of the distance.
 * B. The scene given. The probe at the centre of a grid of pixels against the depth the
 *    image itself accumulated there (every sample, adaptive sampling off, no depth of field):
 *    the same rays, so the same surface, but for the sub-pixel jitter of the image's samples
 *    at an edge.
 * C. Cost. How long a probe takes from the asking thread to the answer, idle.
 * D. Latency. The viewport refining, a pick at the centre from the JavaFX thread as the app
 *    makes it on Ctrl+click: when the result came back, whether the focal distance was set
 *    to it, and when the next image arrived.
 *
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.FocusPickProbe" \
 *       -Dexec.args="presets/JULIA_BULB_OVERVIEW.frac 640x360 64"
 *
 * Exit code 1 when a sphere ray hits or misses wrongly or lands more than a pixel's footprint
 * off the surface, its clearance is off by more than 1e-4 (relative), the scene's median is
 * off by more than 0.5 %, or a pick does not set the focal distance.
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
        boolean ok = true;
        System.out.printf("=== FocusPickProbe: %s at %dx%d, %d samples ===%n", spec, w, h, samples);

        // ---- A. a sphere, against the exact intersection ----------------------------------
        controller.setFractalType(FractalType.NODE_GRAPH);
        NodeGraphParams ngp = (NodeGraphParams) controller.getParams();
        double radius = 0.5;
        double[] centre = {0.3, -0.2, 0.15};
        PrimitiveNode sphere = new PrimitiveNode(PrimitiveNode.PrimitiveType.SPHERE);
        sphere.setSizeX((float) radius);
        ngp.setGraphRoot(new TransformNode(sphere, new float[]{(float) centre[0], (float) centre[1], (float) centre[2]}));
        ngp.markDirty();
        // Close and off centre: the sphere fills about half the frame, to the lower left, so
        // a ray flipped in x or y, or stretched by a wrong aspect, misses where it should hit.
        float[] eye = {0.9f, 0.15f, -0.85f};
        float[] q = CameraUtils.lookAt(eye, new float[]{0.45f, -0.12f, 0.15f});
        Camera cam = ngp.getCamera();
        cam.setPosition(eye[0], eye[1], eye[2]);
        cam.setQuaternion(q[0], q[1], q[2], q[3]);
        ngp.setFovDegrees(50);
        ngp.setDofEnabled(false);
        controller.renderStill(64, 36, 1, () -> false);     // compile, once
        controller.restoreViewportSize();

        int gx = 8, gy = 5;
        List<float[]> grid = new ArrayList<>();
        for (int j = 0; j < gy; j++) for (int i = 0; i < gx; i++) grid.add(new float[]{px(i, gx, w), px(j, gy, h)});
        GLSLEngine.ProbeHit[] got = probeAll(controller, grid);

        double[] o = cam.getPositionD();
        float[] f = cam.getForwardVector(), r = cam.getRightVector(), u = cam.getUpVector();
        double halfH = Math.tan(Math.toRadians(50) * 0.5), halfW = halfH * w / (double) h;
        // A ray that passes within a pixel's width of the outline is an edge: a march stops
        // where the ray comes within a pixel of the surface (the cone's epsilon), which for a
        // grazing ray is short of where it would cross. Those are counted, not measured.
        double pixelAngle = 2.0 * halfH / h;
        double worstHit = 0, worstRel = 0, clearanceExact = dist(o, centre) - radius;
        int agree = 0, hits = 0, edges = 0;
        for (int k = 0; k < grid.size(); k++) {
            float[] uv = controller.screenPoint(grid.get(k)[0], grid.get(k)[1]);
            double[] d = new double[3];
            for (int c = 0; c < 3; c++) d[c] = uv[0] * halfW * r[c] + uv[1] * halfH * u[c] + f[c];
            double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            for (int c = 0; c < 3; c++) d[c] /= len;
            double expected = raySphere(o, d, centre, radius);
            double[] oc = {centre[0] - o[0], centre[1] - o[1], centre[2] - o[2]};
            double along = oc[0] * d[0] + oc[1] * d[1] + oc[2] * d[2];
            double miss = Math.sqrt(Math.max(0, dist(o, centre) * dist(o, centre) - along * along));
            double edgePixels = Math.abs(radius - miss) / (pixelAngle * along);
            if (edgePixels < 1.0) { edges++; continue; }
            boolean hitExpected = expected > 0, hitGot = got[k].hit();
            if (hitExpected == hitGot) agree++;
            else System.out.printf("  ray %d: expected %s, got %s (%.1f px from the outline)%n", k, hitExpected ? "a hit" : "a miss", hitGot ? "a hit" : "a miss", edgePixels);
            if (hitExpected && hitGot) {
                // The view's march stops within a pixel's footprint of the surface (the cone's
                // epsilon), measured along the normal; along a slanted ray that is longer.
                hits++;
                double[] n = new double[3];
                for (int c = 0; c < 3; c++) n[c] = (o[c] + expected * d[c] - centre[c]) / radius;
                double cos = -(n[0] * d[0] + n[1] * d[1] + n[2] * d[2]);
                double offPixels = Math.abs(got[k].distance() - expected) * cos / (pixelAngle * expected);
                worstHit = Math.max(worstHit, offPixels);
                worstRel = Math.max(worstRel, Math.abs(got[k].distance() - expected) / expected);
            }
        }
        double clearanceErr = Math.abs(got[0].clearance() - clearanceExact) / clearanceExact;
        System.out.printf("--- A. a sphere, %d rays against the exact intersection ---%n", grid.size());
        System.out.printf("  hit or miss as expected: %d of %d (%d within a pixel of the outline left out)%n", agree, grid.size() - edges, edges);
        System.out.printf("  over %d hits: off the surface by %.3f pixel footprints at worst, along the normal (%.2e along the ray, relative)%n",
                hits, worstHit, worstRel);
        System.out.printf("  clearance %.7f, exact %.7f, off by %.2e (relative)%n", got[0].clearance(), clearanceExact, clearanceErr);
        ok &= agree == grid.size() - edges && hits > 8 && worstHit < 1.0 && clearanceErr < 1e-4;

        // ---- B. the scene, against the depth its own image accumulated ---------------------
        loadScene(controller, spec);
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        params.setAdaptiveSampling(false);
        boolean dof = params.isDofEnabled();
        params.setDofEnabled(false);
        controller.renderStill(64, 36, 1, () -> false);
        controller.restoreViewportSize();
        GLSLEngine engine = controller.getEngine();
        Map<String, Object> uniforms = controller.buildUniformsForProbe();
        engine.resetAccumulation();
        engine.renderSamples(uniforms, samples);
        gx = 16; gy = 9;
        grid.clear();
        for (int j = 0; j < gy; j++) for (int i = 0; i < gx; i++) grid.add(new float[]{px(i, gx, w), px(j, gy, h)});
        got = probeAll(controller, grid);
        List<Double> err = new ArrayList<>();
        int bothMiss = 0, disagree = 0;
        float nearest = Float.MAX_VALUE;
        for (int k = 0; k < grid.size(); k++) {
            float ref = engine.readDepthAt((int) grid.get(k)[0], (int) grid.get(k)[1]);
            boolean refHit = ref > 0 && ref < 100f;
            if (got[k].hit()) nearest = Math.min(nearest, got[k].distance());
            if (!refHit && !got[k].hit()) { bothMiss++; continue; }
            if (refHit != got[k].hit()) { disagree++; continue; }
            err.add(Math.abs(got[k].distance() - ref) / (double) ref);
        }
        System.out.printf("--- B. the scene: probe against the image's own depth, %d pixels ---%n", grid.size());
        System.out.printf("  surface under %d, sky under %d, hit/miss differ under %d (an edge the jitter straddles)%n",
                err.size(), bothMiss, disagree);
        System.out.printf("  distance median %.3f %%  90th %.3f %%  worst %.3f %%, within 1 %%: %d of %d%n",
                pct(err, 50) * 100, pct(err, 90) * 100, max(err) * 100, count(err, 0.01), err.size());
        System.out.printf("  clearance at the camera %.6g, nearest surface along the grid %.6g%n", got[0].clearance(), nearest);
        ok &= !err.isEmpty() && pct(err, 50) < 0.005;

        // ---- C. what a probe costs, idle ----------------------------------------------------
        List<Double> one = new ArrayList<>(), eight = new ArrayList<>();
        float[] eightUV = new float[2 * GLSLEngine.PROBE_RAYS];
        for (int k = 0; k < 40; k++) {
            one.add(timeProbe(controller, new float[]{0f, 0f}));
            eight.add(timeProbe(controller, eightUV));
        }
        System.out.println("--- C. a probe, asked and answered, idle ---");
        System.out.printf("  1 ray  median %.2f ms  worst %.2f ms%n", pct(one, 50), max(one));
        System.out.printf("  8 rays median %.2f ms  worst %.2f ms%n", pct(eight, 50), max(eight));

        // ---- D. a pick during a refinement --------------------------------------------------
        System.out.println("--- D. viewport refining, a pick at the centre from the JavaFX thread ---");
        params.setAdaptiveSampling(true);
        params.setDofEnabled(true);
        if (!(params.getAperture() > 0)) params.setAperture(0.01f);
        AtomicReference<CountDownLatch> nextImage = new AtomicReference<>(new CountDownLatch(1));
        controller.setViewportListener(new ViewportEvent.Listener() {
            @Override public void onImage(ViewportEvent.ViewportImage img) { nextImage.get().countDown(); }
            @Override public void onStatus(ViewportEvent.Status st) { }
        });
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
            float[] pick = new float[2];
            Platform.runLater(() -> {
                t0.set(System.nanoTime());
                controller.pickFocalDistance(w / 2, h / 2, (depth, tookMs) -> {
                    tAnswer.set(System.nanoTime());
                    pick[0] = depth;
                    pick[1] = params.getFocalDistance();
                    nextImage.set(image);
                    controller.requestRender();                // as the app does
                    answered.countDown();
                });
            });
            boolean a = answered.await(60, TimeUnit.SECONDS);
            boolean im = a && image.await(60, TimeUnit.SECONDS);
            if (im) tImage.set(System.nanoTime());
            boolean set = a && pick[0] > 0 && pick[1] == pick[0];
            System.out.printf("  round %d: distance %.5g, focal distance %s, answer after %s, next image after %s%n",
                    round + 1, pick[0], set ? "set to it" : "NOT SET (" + pick[1] + ")",
                    a ? (tAnswer.get() - t0.get()) / 1_000_000 + " ms" : "NEVER (60 s)",
                    im ? (tImage.get() - t0.get()) / 1_000_000 + " ms" : "NEVER (60 s)");
            ok &= set && im;
        }
        params.setDofEnabled(dof);

        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        controller.close();
        Platform.exit();
        System.exit(ok ? 0 : 1);
    }

    /** The probe over viewport pixels, eight at a time, waited for. */
    static GLSLEngine.ProbeHit[] probeAll(GLSLFractalizerController controller, List<float[]> pixels) throws Exception {
        GLSLEngine.ProbeHit[] all = new GLSLEngine.ProbeHit[pixels.size()];
        for (int start = 0; start < pixels.size(); start += GLSLEngine.PROBE_RAYS) {
            int n = Math.min(GLSLEngine.PROBE_RAYS, pixels.size() - start);
            float[] uv = new float[2 * n];
            for (int k = 0; k < n; k++) {
                float[] p = controller.screenPoint(pixels.get(start + k)[0], pixels.get(start + k)[1]);
                uv[2 * k] = p[0]; uv[2 * k + 1] = p[1];
            }
            CompletableFuture<GLSLEngine.ProbeHit[]> f = new CompletableFuture<>();
            controller.probe(uv, f::complete);
            GLSLEngine.ProbeHit[] hits = f.get(30, TimeUnit.SECONDS);
            if (hits == null) throw new IllegalStateException("probe: no program for the scene");
            System.arraycopy(hits, 0, all, start, n);
        }
        return all;
    }

    private static double timeProbe(GLSLFractalizerController controller, float[] uv) throws Exception {
        CompletableFuture<GLSLEngine.ProbeHit[]> f = new CompletableFuture<>();
        long t0 = System.nanoTime();
        controller.probe(uv, f::complete);
        f.get(30, TimeUnit.SECONDS);
        return (System.nanoTime() - t0) / 1e6;
    }

    /** Distance along a unit ray to the sphere, or -1 for a miss. */
    private static double raySphere(double[] o, double[] d, double[] c, double r) {
        double[] oc = {o[0] - c[0], o[1] - c[1], o[2] - c[2]};
        double b = oc[0] * d[0] + oc[1] * d[1] + oc[2] * d[2];
        double disc = b * b - (oc[0] * oc[0] + oc[1] * oc[1] + oc[2] * oc[2] - r * r);
        if (disc < 0) return -1;
        double t = -b - Math.sqrt(disc);
        return t > 0 ? t : -1;
    }

    private static double dist(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    /** The centre of cell i of n across a size, in pixels. */
    private static int px(int i, int n, int size) { return (int) ((i + 0.5) * size / n); }

    static void loadScene(GLSLFractalizerController controller, String spec) throws Exception {
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

    private static long count(List<Double> v, double below) { return v.stream().filter(x -> x < below).count(); }
    private static double max(List<Double> v) { return v.isEmpty() ? 0 : Collections.max(v); }
    private static double pct(List<Double> v, int p) {
        if (v.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(v); Collections.sort(s);
        return s.get(Math.min(s.size() - 1, (int) Math.floor(s.size() * p / 100.0)));
    }
}
