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
        FractalType type = FractalType.valueOf(args[0]);
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
        controller.setFractalType(type);
        params = (AbstractFractalParams) controller.getParams();
        params.setPathTracingEnabled(false);
        camera = params.getCamera();

        if (args.length > 4 && args[4].equalsIgnoreCase("travel")) {
            int steps = Integer.parseInt(args[5]);
            float shrink = Float.parseFloat(args[6]);
            float fov = (args.length > 7) ? Float.parseFloat(args[7]) : 50f;
            travel(type, steps, shrink, fov);
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

    /** Auto-dive into fine detail: auto-frame the global view, pick a solid target
     *  from the depth map, then walk the camera toward it. Works on hollow / sparse /
     *  oversized fractals, not just centred blobs. */
    static void travel(FractalType type, int steps, float shrink, float fov) throws Exception {
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

        float[] bestT = null; double bestDepth = 0; int bestScore = -1;
        for (int k = -1; k <= 1; k++) for (int m = -1; m <= 1; m++) {
            float[] T = add(origin, add(scale(right, k * extent), scale(up, m * extent)));
            Probe p = probe(eyeG, T, fov);
            if (!p.hit()) continue;
            int score = p.coverage() - (Math.abs(k) + Math.abs(m)); // dense, slight centre bias
            if (score > bestScore) { bestScore = score; bestT = T; bestDepth = p.saturated() ? 0.6 : p.depth(); }
        }
        if (bestT == null) { bestT = origin; bestDepth = R; System.out.println("  no solid target; diving at origin"); }

        float[] fwd = normalize(sub(bestT, eyeG));
        float[] S = add(eyeG, scale(fwd, (float) bestDepth));
        System.out.printf("  target T=(%.2f,%.2f,%.2f) surfaceS=(%.3f,%.3f,%.3f) d=%.3f score=%d%n",
            bestT[0], bestT[1], bestT[2], S[0], S[1], S[2], bestDepth, bestScore);

        // C) Dive toward S, scoring each step's fine detail; keep the sweet spot
        // (max detail) rather than the deepest frame (which washes out smooth).
        double camDist = len(sub(S, eyeG));
        String bestName = null; double bestSharp = -1; int bestStep = -1;
        java.util.List<String> ladder = new java.util.ArrayList<>();
        for (int i = 1; i <= steps; i++) {
            camDist *= shrink;
            float[] eye = sub(S, scale(fwd, (float) camDist));
            String name = String.format(java.util.Locale.ROOT, "t%d_d%.3f", i, camDist);
            renderCam(name, eye, S, fov, true);
            double sharp = sharpness(new File(outDir, name + ".png"), new File(outDir, name + "_depth.png"));
            ladder.add(String.format(java.util.Locale.ROOT, "    %-14s detail=%.1f", name, sharp));
            if (sharp > bestSharp) { bestSharp = sharp; bestName = name; bestStep = i; }
        }
        System.out.println("  --- detail ladder ---");
        ladder.forEach(System.out::println);
        System.out.printf("  SWEET SPOT: step %d (%s) detail=%.1f%n", bestStep, bestName, bestSharp);
    }

    /** Fine-detail score = variance of the Laplacian over surface pixels (depth-masked
     *  so smooth background/too-close washout scores low, ciselated structure scores high). */
    static double sharpness(File rgbFile, File depthFile) throws Exception {
        BufferedImage img = ImageIO.read(rgbFile);
        BufferedImage dep = ImageIO.read(depthFile);
        int w = img.getWidth(), h = img.getHeight();
        double[] lum = new double[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int p = img.getRGB(x, y);
            lum[y * w + x] = 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF);
        }
        double sum = 0, sum2 = 0; long n = 0;
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            if (dep.getRaster().getSample(x, y, 0) / 65535.0 <= 0.02) continue; // background
            double lap = -4 * lum[y*w+x] + lum[y*w+x-1] + lum[y*w+x+1] + lum[(y-1)*w+x] + lum[(y+1)*w+x];
            sum += lap; sum2 += lap * lap; n++;
        }
        if (n < 100) return 0;
        double mean = sum / n;
        return sum2 / n - mean * mean;
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

    // ---- vec helpers -------------------------------------------------------
    static float[] sub(float[] a, float[] b){ return new float[]{a[0]-b[0],a[1]-b[1],a[2]-b[2]}; }
    static float[] add(float[] a, float[] b){ return new float[]{a[0]+b[0],a[1]+b[1],a[2]+b[2]}; }
    static float[] scale(float[] a, float s){ return new float[]{a[0]*s,a[1]*s,a[2]*s}; }
    static float len(float[] a){ return (float)Math.sqrt(a[0]*a[0]+a[1]*a[1]+a[2]*a[2]); }
    static float[] normalize(float[] a){ float l=len(a); return l<1e-9f?a:scale(a,1f/l); }
    static float f(String s){ return Float.parseFloat(s); }
}
