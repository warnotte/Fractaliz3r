package org.fractalizer.test;

import org.fractalizer.ui.GLSLFractalizerController;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.engine.Camera;

import javafx.application.Platform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/**
 * Deep-zoom detail lab.
 *
 * Renders the SAME fine-detail camera under a set of parameter variants and reports
 * quantitative detail metrics, so deep-zoom quality work is measured, not eyeballed.
 *
 * Metrics per render (surface pixels only, background masked out via the depth AOV):
 *   detail   - variance of the Laplacian: fine structure energy
 *   edges    - fraction of surface pixels with |Laplacian| &gt; 8: crevice/edge density
 *   lum      - mean surface luminance (0-255): catches "it goes dark" at depth
 *   contrast - stddev of surface luminance: catches "it goes flat/washed out"
 *   cov      - surface coverage %
 *
 * Usage:
 *   -Dexec.args="MANDELBULB dzl 640x360 8 nav/cams.txt iter=15,40,80,150"
 *   -Dexec.args="MANDELBULB dzl 640x360 8 nav/cams.txt base"
 *
 * Variant spec: "base" or "key=v1,v2,..." where key is resolved by reflection
 * against the params object (e.g. iter -&gt; setMaxIterations).
 */
public class DeepZoomLab {

    static GLSLFractalizerController controller;
    static AbstractFractalParams params;
    static Camera camera;
    static String outDir;
    static int W, H, samples;

    record Cam(String name, float[] eye, float[] tgt, float fov) {}
    record Metrics(double detail, double edges, double lum, double contrast, double coverage) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.out.println("Usage: DeepZoomLab <TYPE|file.frac> <outDir> <WxH> <samples> <cams.txt> [variantSpec]");
            System.exit(1);
        }
        String spec = args[0];
        outDir = args[1];
        String[] res = args[2].split("x");
        W = Integer.parseInt(res[0]); H = Integer.parseInt(res[1]);
        samples = Integer.parseInt(args[3]);
        File camFile = new File(args[4]);
        String variantSpec = (args.length > 5) ? args[5] : "base";

        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();
        new File(outDir).mkdirs();

        controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});

        FractalType type;
        if (spec.toLowerCase().endsWith(".frac")) {
            var cfg = org.fractalizer.config.FractalConfigManager.load(new File(spec));
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
        // Without this every render is monochrome regardless of the gradient in the scene.
        controller.updatePaletteTexture(params.getCustomGradient());
        camera = params.getCamera();

        List<Cam> cams = new ArrayList<>();
        for (String line : Files.readAllLines(camFile.toPath())) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            String[] t = s.split("\\s+");
            cams.add(new Cam(t[0], new float[]{f(t[1]), f(t[2]), f(t[3])},
                                   new float[]{f(t[4]), f(t[5]), f(t[6])}, f(t[7])));
        }

        List<String> variants = new ArrayList<>();
        String setterKey = null;
        if (variantSpec.equals("base")) {
            variants.add("base");
        } else {
            String[] kv = variantSpec.split("=", 2);
            setterKey = kv[0];
            for (String v : kv[1].split(",")) variants.add(v.trim());
        }

        System.out.printf("=== DeepZoomLab: %s (%dx%d, %d spp) - %s ===%n", type, W, H, samples, variantSpec);
        System.out.printf("%-14s %-10s | %9s %7s %7s %8s %5s %7s%n",
                "camera", "variant", "detail", "edges%", "lum", "contrast", "cov%", "ms");

        for (Cam c : cams) {
            for (String v : variants) {
                if (setterKey != null) applyVariant(setterKey, v);
                long t0 = System.nanoTime();
                String tag = c.name() + (setterKey == null ? "" : "_" + setterKey + v);
                Metrics m = renderAndScore(tag, c);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                System.out.printf(Locale.ROOT, "%-14s %-10s | %9.1f %7.2f %7.1f %8.1f %5.0f %7d%n",
                        c.name(), v, m.detail(), 100 * m.edges(), m.lum(), m.contrast(), 100 * m.coverage(), ms);
                System.out.flush();
            }
        }
        System.out.println();
        System.out.println("DONE -> " + new File(outDir).getAbsolutePath());
        System.exit(0);
    }

    /** Resolve a short variant key to a params setter and apply the value. */
    static void applyVariant(String key, String value) throws Exception {
        String[] candidates = switch (key) {
            case "iter"    -> new String[]{"setMaxIterations", "setIterations"};
            case "ao"      -> new String[]{"setAoIntensity"};
            case "eps"     -> new String[]{"setEpsilon"};
            case "quality" -> new String[]{"setQualityMultiplier"};
            case "steps"   -> new String[]{"setMaxRaySteps"};
            case "refine"  -> new String[]{"setRefinementSteps"};
            default        -> new String[]{"set" + Character.toUpperCase(key.charAt(0)) + key.substring(1)};
        };
        // Fractal-specific setters live on the node graph's leaf params, not on the
        // NodeGraphParams wrapper every type now routes through.
        Object[] targets = { params, (params instanceof org.fractalizer.fractals.NodeGraphParams ngp)
                ? ngp.getRootFractalParams() : null };
        for (String name : candidates) {
          for (Object target : targets) {
            if (target == null) continue;
            for (Method m : target.getClass().getMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;
                Class<?> p = m.getParameterTypes()[0];
                if (p == int.class)     { m.invoke(target, Integer.parseInt(value)); return; }
                if (p == float.class)   { m.invoke(target, Float.parseFloat(value)); return; }
                if (p == double.class)  { m.invoke(target, Double.parseDouble(value)); return; }
                if (p == boolean.class) { m.invoke(target, Boolean.parseBoolean(value)); return; }
            }
          }
        }
        throw new IllegalArgumentException("No setter found for variant key: " + key);
    }

    static Metrics renderAndScore(String tag, Cam c) throws Exception {
        camera.setPosition(c.eye()[0], c.eye()[1], c.eye()[2]);
        float[] q = CameraUtils.lookAt(c.eye(), c.tgt());
        camera.setQuaternion(q[0], q[1], q[2], q[3]);
        params.setFovDegrees(c.fov());

        controller.setExportSize(W, H);
        File rgb = new File(outDir, tag + ".png");
        controller.exportToPNG(rgb, samples, p -> {}, () -> false).get();

        controller.setExportSize(W, H);
        File depth = new File(outDir, tag + "_depth.png");
        controller.exportAOV(depth, 2);

        return score(rgb, depth);
    }

    /** Surface-masked image statistics. Background pixels are excluded so a shrinking
     *  silhouette does not masquerade as a change in surface quality. */
    static Metrics score(File rgbFile, File depthFile) throws Exception {
        BufferedImage img = ImageIO.read(rgbFile);
        BufferedImage dep = ImageIO.read(depthFile);
        int w = img.getWidth(), h = img.getHeight();
        double[] lum = new double[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int p = img.getRGB(x, y);
            lum[y * w + x] = 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF);
        }
        double lapSum = 0, lapSq = 0, lSum = 0, lSq = 0;
        long n = 0, edgeCount = 0, surf = 0, tot = 0;
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            tot++;
            if (dep.getRaster().getSample(x, y, 0) / 65535.0 <= 0.02) continue;
            surf++;
            double lap = -4 * lum[y*w+x] + lum[y*w+x-1] + lum[y*w+x+1] + lum[(y-1)*w+x] + lum[(y+1)*w+x];
            lapSum += lap; lapSq += lap * lap; n++;
            if (Math.abs(lap) > 8.0) edgeCount++;
            lSum += lum[y*w+x]; lSq += lum[y*w+x] * lum[y*w+x];
        }
        if (n < 100) return new Metrics(0, 0, 0, 0, 0);
        double detail = lapSq / n - (lapSum / n) * (lapSum / n);
        double meanL = lSum / n;
        double contrast = Math.sqrt(Math.max(0, lSq / n - meanL * meanL));
        return new Metrics(detail, (double) edgeCount / n, meanL, contrast, (double) surf / tot);
    }

    static float f(String s) { return Float.parseFloat(s); }
}
