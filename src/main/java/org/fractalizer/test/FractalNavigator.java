package org.fractalizer.test;

import org.fractalizer.ui.GLSLFractalizerController;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.engine.Camera;

import javafx.application.Platform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;

/**
 * Fractal navigator / auto-traveller (human-in-the-loop framing).
 *
 * Two modes:
 *   list   - render a file of explicit cameras (name eye tgt fov per line).
 *   travel - AUTO-DIVE from a fractal's default camera into fine detail: aim at
 *            the origin, read the centre surface distance from the depth AOV to
 *            get a surface point S, then walk the camera toward S along the view
 *            axis in shrinking steps. Works for any origin-centred fractal.
 *
 * Orientation is always via CameraUtils.lookAt (never hand-built quaternions).
 * Depth: AOV logDepth = 1 - clamp(log(d+0.1)/log(15),0,1); inverted to
 * d = 15^(1-v) - 0.1 (saturates for d < ~0.9).
 *
 * Usage:
 *   list:   -Dexec.args="MANDELBULB nav 640x360 12 nav/cams.txt"
 *   travel: -Dexec.args="MENGER_SPONGE nav 640x360 12 travel 6 0.55 50"
 *                          (TYPE out WxH samples travel steps shrink [fov])
 */
public class FractalNavigator {

    static GLSLFractalizerController controller;
    static AbstractFractalParams params;
    static Camera camera;
    static String outDir;
    static int W, H, samples;

    public static void main(String[] args) throws Exception {
        args = extractOverrides(args);
        if (args[0].equalsIgnoreCase("manifest")) { generateManifest(args); return; }
        String spec = args[0];                       // FractalType name OR a .frac file
        outDir = args[1];
        String[] res = args[2].split("x");
        W = Integer.parseInt(res[0]); H = Integer.parseInt(res[1]);
        samples = Integer.parseInt(args[3]);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();
        new File(outDir).mkdirs();

        controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});

        FractalType type;
        if (spec.toLowerCase().endsWith(".frac")) {
            org.fractalizer.config.FractalConfig cfg = org.fractalizer.config.FractalConfigManager.load(new File(spec));
            type = cfg.getFractalTypeEnum();
            controller.setFractalType(type);
            params = (AbstractFractalParams) controller.getParams();
            cfg.applyTo(params);
        } else {
            type = FractalType.valueOf(spec);
            controller.setFractalType(type);
            params = (AbstractFractalParams) controller.getParams();
        }
        params.setPathTracingEnabled(false);
        applyOverrides();
        camera = params.getCamera();

        if (args.length > 4 && (args[4].equalsIgnoreCase("travel") || args[4].equalsIgnoreCase("fly"))) {
            int steps = Integer.parseInt(args[5]);
            float shrink = Float.parseFloat(args[6]);
            float fov = (args.length > 7) ? Float.parseFloat(args[7]) : 50f;
            Plan plan = travel(type, steps, shrink, fov);
            if (args[4].equalsIgnoreCase("fly")) {
                int frames = (args.length > 8) ? Integer.parseInt(args[8]) : 48;
                renderFlight(plan, fov, frames);
            }
        } else {
            File camFile = new File(args[4]);
            System.out.printf("=== Navigator(list): %s (%dx%d, %d spp) ===%n", type, W, H, samples);
            for (String line : Files.readAllLines(camFile.toPath())) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                String[] t = s.split("\\s+");
                float[] eye = {f(t[1]), f(t[2]), f(t[3])};
                float[] tgt = {f(t[4]), f(t[5]), f(t[6])};
                renderCam(t[0], eye, tgt, f(t[7]), true);
            }
        }
        System.out.println("\nDONE -> " + new File(outDir).getAbsolutePath());
        System.exit(0);
    }

    /** Generate a detail-scene manifest: for each fractal, run the traveller and
     *  write its sweet-spot camera (type eye target fov) — feeds RenderRegression so
     *  perf/quality is validated on fine-detail views, not default global ones. */
    static void generateManifest(String[] args) throws Exception {
        String outFile = args[1];
        String[] res = args[2].split("x");
        W = Integer.parseInt(res[0]); H = Integer.parseInt(res[1]);
        samples = Integer.parseInt(args[3]);
        String[] tns = args[4].split(",");

        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();
        outDir = "nav/_manifest_tmp";
        new File(outDir).mkdirs();
        controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});

        StringBuilder sb = new StringBuilder("# detail scenes (sweet-spot cameras) — type eyeX eyeY eyeZ tgtX tgtY tgtZ fov\n");
        for (String tn : tns) {
            FractalType ty = FractalType.valueOf(tn.trim());
            controller.setFractalType(ty);
            params = (AbstractFractalParams) controller.getParams();
            params.setPathTracingEnabled(false);
            camera = params.getCamera();
            Plan pl = travel(ty, 8, 0.62f, 50f);
            float[] es = sub(pl.S(), scale(pl.fwd(), (float) pl.sweetCamDist()));
            sb.append(String.format(java.util.Locale.ROOT, "%s %.5f %.5f %.5f %.5f %.5f %.5f 50%n",
                tn.trim(), es[0], es[1], es[2], pl.S()[0], pl.S()[1], pl.S()[2]));
            System.out.printf("manifest: %-22s sweet camDist=%.3f%n", tn.trim(), pl.sweetCamDist());
        }
        java.nio.file.Files.writeString(new File(outFile).toPath(), sb.toString());
        System.out.println("MANIFEST -> " + outFile);
        System.exit(0);
    }

    /** Auto-dive into fine detail: auto-frame the global view, pick a solid target
     *  from the depth map, then walk the camera toward it. Works on hollow / sparse /
     *  oversized fractals, not just centred blobs. */
    static Plan travel(FractalType type, int steps, float shrink, float fov) throws Exception {
        float[] origin = {0, 0, 0};
        float[] eye0 = camera.getPosition();
        float[] dirEye = (len(eye0) < 1e-3f) ? new float[]{0, 0, -1} : normalize(eye0);
        System.out.printf("=== Travel: %s defaultEye=(%.2f,%.2f,%.2f) fov=%.0f steps=%d shrink=%.2f ===%n",
            type, eye0[0], eye0[1], eye0[2], fov, steps, shrink);

        // A) Auto-frame: back off if the fractal overflows / camera is inside, move
        // in if it is tiny, until coverage is healthy with the centre near a surface.
        float R = Math.max(len(eye0), 0.5f);
        for (int i = 0; i < 6; i++) {
            Probe p = probe(scale(dirEye, R), origin, fov);
            System.out.printf("  autoframe R=%.2f cov=%d%% center=%s%n", R, p.coverage(), p.label());
            if (p.coverage() > 85 || p.saturated()) R *= 1.5f;       // too close / inside
            else if (p.coverage() < 25) R *= 0.72f;                  // too far / sparse
            else break;
        }
        float[] eyeG = scale(dirEye, R);
        renderCam("t0_global", eyeG, origin, fov, true);

        // B) Depth-guided target: scan a 3x3 grid of aim points across the view
        // plane; pick the densest whose centre actually hits the surface (skips
        // Menger's hollow core and the empty gaps of sparse IFS).
        float[] q = CameraUtils.lookAt(eyeG, origin);
        camera.setPosition(eyeG[0], eyeG[1], eyeG[2]);
        camera.setQuaternion(q[0], q[1], q[2], q[3]);
        float[] right = camera.getRightVector();
        float[] up = camera.getUpVector();
        float extent = (float) (R * Math.tan(Math.toRadians(fov * 0.5)) * 0.5);

        float[] bestT = null; double bestDepth = 0; double bestDetail = -1;
        for (int k = -1; k <= 1; k++) for (int m = -1; m <= 1; m++) {
            float[] T = add(origin, add(scale(right, k * extent), scale(up, m * extent)));
            Probe p = probe(eyeG, T, fov);
            if (!p.hit()) continue;
            double det = probeDetail(eyeG, T, fov);   // aim at the most DETAILED solid patch
            if (det > bestDetail) { bestDetail = det; bestT = T; bestDepth = p.saturated() ? 0.6 : p.depth(); }
        }
        if (bestT == null) { bestT = origin; bestDepth = R; System.out.println("  no solid target; diving at origin"); }

        float[] fwd = normalize(sub(bestT, eyeG));
        float[] S = add(eyeG, scale(fwd, (float) bestDepth));
        System.out.printf("  target T=(%.2f,%.2f,%.2f) surfaceS=(%.3f,%.3f,%.3f) d=%.3f detail=%.0f%n",
            bestT[0], bestT[1], bestT[2], S[0], S[1], S[2], bestDepth, bestDetail);

        // C) Dive toward S, scoring each step's fine detail; keep the sweet spot
        // (max detail) rather than the deepest frame (which washes out smooth).
        double camDist = len(sub(S, eyeG));
        String bestName = null; double bestSharp = -1; int bestStep = -1; double bestCamDist = camDist * shrink;
        java.util.List<String> ladder = new java.util.ArrayList<>();
        for (int i = 1; i <= steps; i++) {
            camDist *= shrink;
            float[] eye = sub(S, scale(fwd, (float) camDist));
            String name = String.format(java.util.Locale.ROOT, "t%d_d%.3f", i, camDist);
            renderCam(name, eye, S, fov, true);
            FrameScore fs = scoreFrame(new File(outDir, name + ".png"), new File(outDir, name + "_depth.png"));
            double a = fs.aesthetic();
            ladder.add(String.format(java.util.Locale.ROOT, "    %-14s detail=%.0f cov=%.0f%% score=%.0f", name, fs.detail(), fs.coverage() * 100, a));
            if (a > bestSharp) { bestSharp = a; bestName = name; bestStep = i; bestCamDist = camDist; }
        }
        System.out.println("  --- detail ladder ---");
        ladder.forEach(System.out::println);
        System.out.printf("  SWEET SPOT: step %d (%s) score=%.0f%n", bestStep, bestName, bestSharp);
        return new Plan(eyeG, S, fwd, bestCamDist);
    }

    record Plan(float[] eyeG, float[] S, float[] fwd, double sweetCamDist) {}

    /** Render a smooth eased flight from the global view to the sweet spot. */
    static void renderFlight(Plan pl, float fov, int frames) throws Exception {
        float[] origin = {0, 0, 0};
        float[] eyeSweet = sub(pl.S(), scale(pl.fwd(), (float) pl.sweetCamDist()));
        System.out.printf("  flight: %d frames global -> sweet spot (camDist %.3f)%n", frames, pl.sweetCamDist());
        for (int f = 0; f < frames; f++) {
            double t = (frames <= 1) ? 1.0 : (double) f / (frames - 1);
            float e = (float) (t * t * (3 - 2 * t));                 // smoothstep ease
            float[] eye = CameraUtils.lerp(pl.eyeG(), eyeSweet, e);
            float[] tgt = CameraUtils.lerp(origin, pl.S(), e);
            renderFrame(String.format(java.util.Locale.ROOT, "fly_%04d", f), eye, tgt, fov);
        }
        System.out.println("  flight frames done");
    }

    static void renderFrame(String name, float[] eye, float[] tgt, float fov) throws Exception {
        camera.setPosition(eye[0], eye[1], eye[2]);
        float[] q = CameraUtils.lookAt(eye, tgt);
        camera.setQuaternion(q[0], q[1], q[2], q[3]);
        params.setFovDegrees(fov);
        controller.setExportSize(W, H);
        controller.exportToPNG(new File(outDir, name + ".png"), samples, p -> {}, () -> false).get();
    }

    /** Composed framing score: fine detail, surface coverage, and where the detail
     *  energy sits in the frame. aesthetic() balances them for a pleasing shot. */
    record FrameScore(double detail, double coverage, double centroidDist) {
        double aesthetic() {
            double covBand = Math.exp(-Math.pow((coverage - 0.55) / 0.30, 2)); // peak ~55% coverage
            double centering = 1.0 - 0.6 * Math.min(1.0, centroidDist);        // detail near centre wins
            return detail * covBand * centering;
        }
    }

    /** Detail = variance of the Laplacian over surface pixels (depth-masked); coverage =
     *  surface fraction; centroidDist = where the |Laplacian| energy sits (0=centre,1=corner). */
    static FrameScore scoreFrame(File rgbFile, File depthFile) throws Exception {
        BufferedImage img = ImageIO.read(rgbFile);
        BufferedImage dep = ImageIO.read(depthFile);
        int w = img.getWidth(), h = img.getHeight();
        double[] lum = new double[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int p = img.getRGB(x, y);
            lum[y * w + x] = 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF);
        }
        double sum = 0, sum2 = 0, cx = 0, cy = 0, wsum = 0;
        long n = 0, surf = 0, tot = 0;
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            tot++;
            if (dep.getRaster().getSample(x, y, 0) / 65535.0 <= 0.02) continue; // background
            surf++;
            double lap = -4 * lum[y*w+x] + lum[y*w+x-1] + lum[y*w+x+1] + lum[(y-1)*w+x] + lum[(y+1)*w+x];
            double al = Math.abs(lap);
            sum += lap; sum2 += lap * lap; n++;
            cx += al * x; cy += al * y; wsum += al;
        }
        double detail = (n < 100) ? 0 : (sum2 / n - (sum / n) * (sum / n));
        double coverage = (tot == 0) ? 0 : (double) surf / tot;
        double cdist = 1.0;
        if (wsum > 1e-6) {
            double dx = (cx / wsum - w / 2.0) / (w / 2.0), dy = (cy / wsum - h / 2.0) / (h / 2.0);
            cdist = Math.sqrt(dx * dx + dy * dy) / Math.sqrt(2.0);
        }
        return new FrameScore(detail, coverage, cdist);
    }

    /** Quick colour render at an aim point; returns its detail score (for target choice). */
    static double probeDetail(float[] eye, float[] tgt, float fov) throws Exception {
        camera.setPosition(eye[0], eye[1], eye[2]);
        float[] q = CameraUtils.lookAt(eye, tgt);
        camera.setQuaternion(q[0], q[1], q[2], q[3]);
        params.setFovDegrees(fov);
        controller.setExportSize(W, H);
        File c = new File(outDir, "_probe_rgb.png");
        controller.exportToPNG(c, Math.min(samples, 6), p -> {}, () -> false).get();
        controller.setExportSize(W, H);
        File d = new File(outDir, "_probe_d.png");
        controller.exportAOV(d, 2);
        return scoreFrame(c, d).detail();
    }

    /** Depth-only probe of one camera: centre hit/depth + surface coverage. */
    record Probe(boolean hit, boolean saturated, double depth, int coverage) {
        String label() { return !hit ? "miss" : (saturated ? "<0.9" : String.format(java.util.Locale.ROOT, "%.2f", depth)); }
    }
    static Probe probe(float[] eye, float[] tgt, float fov) throws Exception {
        camera.setPosition(eye[0], eye[1], eye[2]);
        float[] q = CameraUtils.lookAt(eye, tgt);
        camera.setQuaternion(q[0], q[1], q[2], q[3]);
        params.setFovDegrees(fov);
        controller.setExportSize(W, H);
        File dp = new File(outDir, "_probe_depth.png");
        controller.exportAOV(dp, 2);
        BufferedImage img = ImageIO.read(dp);
        int raw = img.getRaster().getSample(img.getWidth() / 2, img.getHeight() / 2, 0);
        double v = raw / 65535.0;
        boolean hit = v > 0.02, sat = v >= 0.999;
        double depth = (hit && !sat) ? (Math.pow(15.0, 1.0 - v) - 0.1) : Double.NaN;
        return new Probe(hit, sat, depth, coverage(dp)[0]);
    }

    /** Render colour + depth AOV for one camera; returns decoded centre depth (NaN if saturated/miss). */
    static double renderCam(String name, float[] eye, float[] tgt, float fov, boolean probe) throws Exception {
        camera.setPosition(eye[0], eye[1], eye[2]);
        float[] q = CameraUtils.lookAt(eye, tgt);
        camera.setQuaternion(q[0], q[1], q[2], q[3]);
        params.setFovDegrees(fov);

        controller.setExportSize(W, H);
        File rgb = new File(outDir, name + ".png");
        long t0 = System.nanoTime();
        controller.exportToPNG(rgb, samples, p -> {}, () -> false).get();
        long ms = (System.nanoTime() - t0) / 1_000_000;

        controller.setExportSize(W, H);
        File depthPng = new File(outDir, name + "_depth.png");
        controller.exportAOV(depthPng, 2);
        double cd = centerDepth(depthPng);
        int[] cov = coverage(depthPng);

        System.out.printf("  %-14s eye=(%.3f,%.3f,%.3f) camDist=%.3f | centerDepth=%s  hit=%d%% (%d ms)%n",
            name, eye[0], eye[1], eye[2], len(sub(eye, tgt)),
            Double.isNaN(cd) ? (centerSat(depthPng) ? "<0.9" : "miss") : String.format("%.3f", cd),
            cov[0], ms);
        return cd;
    }

    // ---- depth helpers -----------------------------------------------------

    static double decode(double v) {
        if (v >= 0.999 || v <= 0.001) return Double.NaN; // saturated near / far-miss
        return Math.pow(15.0, 1.0 - v) - 0.1;
    }
    static double centerDepth(File depthPng) throws Exception {
        BufferedImage img = ImageIO.read(depthPng);
        int raw = img.getRaster().getSample(img.getWidth()/2, img.getHeight()/2, 0);
        return decode(raw / 65535.0);
    }
    static boolean centerSat(File depthPng) throws Exception {
        BufferedImage img = ImageIO.read(depthPng);
        int raw = img.getRaster().getSample(img.getWidth()/2, img.getHeight()/2, 0);
        return raw / 65535.0 >= 0.999;
    }
    /** Percentage of pixels that hit the surface (depth not far/background). */
    static int[] coverage(File depthPng) throws Exception {
        BufferedImage img = ImageIO.read(depthPng);
        int w = img.getWidth(), h = img.getHeight(), hit = 0, n = 0;
        for (int y = 0; y < h; y += 4) for (int x = 0; x < w; x += 4) {
            n++;
            if (img.getRaster().getSample(x, y, 0) / 65535.0 > 0.02) hit++; // >0.02 => not background
        }
        return new int[]{ Math.round(100f * hit / n) };
    }

    // ---- parameter overrides -----------------------------------------------
    // Any "name=value" argument is pulled out of the positional list and applied to
    // the params before travelling, so a variant (a Julia constant, a different
    // power) can be framed and dived without authoring a .frac for it first.

    static final java.util.Map<String, String> overrides = new java.util.LinkedHashMap<>();

    static String[] extractOverrides(String[] args) {
        java.util.List<String> positional = new java.util.ArrayList<>();
        for (String a : args) {
            int eq = a.indexOf('=');
            if (eq > 0 && a.chars().noneMatch(Character::isWhitespace) && !a.toLowerCase().endsWith(".frac")) {
                overrides.put(a.substring(0, eq), a.substring(eq + 1));
            } else {
                positional.add(a);
            }
        }
        return positional.toArray(new String[0]);
    }

    /** Apply overrides to the params, or to the node graph leaf params when the setter
     *  is fractal-specific (every type now routes through NodeGraphParams). */
    static void applyOverrides() throws Exception {
        if (overrides.isEmpty()) return;
        Object[] targets = { params, (params instanceof org.fractalizer.fractals.NodeGraphParams ngp)
                ? ngp.getRootFractalParams() : null };
        for (var e : overrides.entrySet()) {
            String setter = "set" + Character.toUpperCase(e.getKey().charAt(0)) + e.getKey().substring(1);
            boolean applied = false;
            for (Object target : targets) {
                if (target == null || applied) continue;
                for (java.lang.reflect.Method m : target.getClass().getMethods()) {
                    if (!m.getName().equals(setter) || m.getParameterCount() != 1) continue;
                    Class<?> t = m.getParameterTypes()[0];
                    if (t == int.class)          m.invoke(target, Integer.parseInt(e.getValue()));
                    else if (t == float.class)   m.invoke(target, Float.parseFloat(e.getValue()));
                    else if (t == double.class)  m.invoke(target, Double.parseDouble(e.getValue()));
                    else if (t == boolean.class) m.invoke(target, Boolean.parseBoolean(e.getValue()));
                    else continue;
                    applied = true;
                    break;
                }
            }
            if (!applied) throw new IllegalArgumentException("No setter for override: " + e.getKey());
            System.out.printf("  override %s = %s%n", e.getKey(), e.getValue());
        }
    }

    // ---- vec helpers -------------------------------------------------------
    static float[] sub(float[] a, float[] b){ return new float[]{a[0]-b[0],a[1]-b[1],a[2]-b[2]}; }
    static float[] add(float[] a, float[] b){ return new float[]{a[0]+b[0],a[1]+b[1],a[2]+b[2]}; }
    static float[] scale(float[] a, float s){ return new float[]{a[0]*s,a[1]*s,a[2]*s}; }
    static float len(float[] a){ return (float)Math.sqrt(a[0]*a[0]+a[1]*a[1]+a[2]*a[2]); }
    static float[] normalize(float[] a){ float l=len(a); return l<1e-9f?a:scale(a,1f/l); }
    static float f(String s){ return Float.parseFloat(s); }
}
