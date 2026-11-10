package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.engine.Camera;
import org.fractalizer.engine.GLSLEngine;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.ui.GLSLFractalizerController;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Precision lab: where does the float march break, and does the double one hold?
 *
 * <p>A float position near |pos| ~ 1 is quantised to ~1e-7 world units. The pixel footprint
 * of a camera at distance c from the surface is about c * 2 tan(fov/2) / height, so below
 * c ~ 1e-4 the footprint drops under the quantum and the renderer evaluates the same handful
 * of points for neighbouring pixels: blocks, speckled normals. Deep precision marches the
 * camera rays in double with the scene's estimator translated to double (DeepSource), for any
 * scene.
 *
 * <p>This lab builds a ladder of cameras at decreasing distance from one surface point and
 * renders every rung twice, float and double, colour and normals, then measures:
 * <ul>
 *   <li>{@code detail}, {@code edges%}, {@code lum}, {@code contrast}, {@code cov%} as
 *       {@link DeepZoomLab} does (surface pixels only);</li>
 *   <li>{@code rough}: the mean angle in degrees between the normals of neighbouring surface
 *       pixels. A rough surface has some; float noise adds a lot;</li>
 *   <li>{@code speckle%}: surface pixels whose normal differs from all four neighbours by
 *       more than 25°: isolated flips, the signature of quantisation;</li>
 *   <li>{@code err} and {@code >10°}: the angle between each pixel's normal in float and in
 *       double, mean and share above 10°. The double render runs the whole orbit in double:
 *       it is the most exact thing the GPU makes, the oracle of the WIP. Where the float
 *       march is still good (the first rungs) the two must agree, which checks the
 *       translation;</li>
 *   <li>{@code ms}: render time, what each path costs.</li>
 * </ul>
 * <p>The surface point and the ladder come from the probe (the scene's own program), in
 * double, and are refined at each rung, so the camera really is at the distance printed. No
 * fractal is known here.
 *
 * <pre>
 *   PrecisionLab scene outDir WxH samples ladder [eyeX,eyeY,eyeZ,targetX,targetY,targetZ] [asis]
 *   ("asis" keeps the scene's own shading, path tracing and fog included, instead of classic)
 *   PrecisionLab MANDELBOX out/precision 640x360 8 1e-1,1e-2,1e-3,1e-4,1e-5,1e-6 -4.55047,0.23943,-9.37107,-1.45361,1.45941,-6.01285
 *   PrecisionLab compile out/precision_compile [NAME]  (every built-in, or those whose enum name
 *                                                       contains NAME, float and double: compiles? how long?)
 * </pre>
 * Writes {@code outDir/report.html}. Exit code 1 when the double program of the scene laddered
 * does not compile, or the float and double normals disagree by more than 2° (mean) on the
 * first rung. In "compile" mode, when a float program fails, or a scene whose double program
 * the driver refuses is not shown in float, the same image, with a message; the double programs
 * refused are listed (the Polyhedral IFS on NVIDIA, whose compiler crashes on it, C9999).
 */
public class PrecisionLab {

    record Result(String tag, DeepZoomLab.Metrics m, double rough, double speckle, long ms, String rgb, String nrm) {}

    public static void main(String[] args) {
        // JavaFX keeps the JVM alive after an exception on this thread; a harness that hangs
        // instead of failing is worse than one that fails.
        try {
            System.exit(args.length > 0 && args[0].equals("compile") ? compileAll(args) : run(args));
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.flush();
            System.exit(1);
        }
    }

    static GLSLFractalizerController start(int w, int h) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();
        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        controller.setViewportSize(w, h);
        return controller;
    }

    /** Every built-in fractal, float and double: does the double program compile, and what
     *  does it cost to compile and to render. */
    static int compileAll(String[] args) throws Exception {
        String outDir = args.length > 1 ? args[1] : "out/precision_compile";
        new File(outDir).mkdirs();
        GLSLFractalizerController controller = start(320, 180);
        System.out.println("=== PrecisionLab compile: every built-in, float and double ===");
        System.out.printf("%-24s | %9s %9s | %9s %9s | %s%n", "fractal", "float ms", "double ms", "float img", "double img", "double program");
        boolean ok = true;
        int total = 0, doubled = 0;
        List<String> refused = new ArrayList<>();
        for (FractalType type : FractalType.values()) {
            if (type.getKernelName() == null || type == FractalType.NODE_GRAPH || type == FractalType.CUSTOM_SHADER
                    || type.name().startsWith("TEST")) continue;
            if (args.length > 2 && !type.name().contains(args[2])) continue;     // one family only
            try {
                controller.setFractalType(type);
            } catch (RuntimeException e) {
                continue;
            }
            AbstractFractalParams p = (AbstractFractalParams) controller.getParams();
            p.setPathTracingEnabled(false);
            long[] ms = new long[4];
            boolean[] held = new boolean[2];
            for (int mode = 0; mode < 2; mode++) {
                p.setDeepPrecision(mode == 1);
                long t0 = System.nanoTime();
                controller.renderStill(32, 18, 1, () -> false);     // the compile
                ms[mode] = (System.nanoTime() - t0) / 1_000_000;
                held[mode] = controller.holdsSceneProgram();
                t0 = System.nanoTime();
                BufferedImage img = controller.renderStill(320, 180, 4, () -> false);
                ms[2 + mode] = (System.nanoTime() - t0) / 1_000_000;
                if (img != null) ImageIO.write(img, "png", new File(outDir, type.getKernelName() + (mode == 1 ? "_double" : "_float") + ".png"));
            }
            System.out.printf("%-24s | %9d %9d | %9d %9d | %s%n", type.getDisplayName(), ms[0], ms[1], ms[2], ms[3],
                    held[1] ? "compiles" : "refused, float kept");
            ok &= held[0];
            total++;
            if (held[1]) doubled++; else refused.add(type.getDisplayName());
        }
        // A double program the driver refuses is not a failure: the scene is shown in float with
        // a message (checked below). Which ones, and how many, is what this mode reports.
        System.out.printf("double programs: %d of %d compile%s%n", doubled, total,
                refused.isEmpty() ? "" : "; shown in float: " + String.join(", ", refused));
        if (args.length <= 2) ok &= fallback(controller, outDir);
        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        controller.close();
        Platform.exit();
        return ok ? 0 : 1;
    }

    /**
     * A scene whose estimator GLSL refuses in double (a custom shader reading a texture in its
     * DE: texture() takes no double coordinates). With deep precision on, the still and the
     * viewport must both show the scene in float, the same image as with it off, and the
     * viewport must say why.
     */
    static boolean fallback(GLSLFractalizerController controller, String outDir) throws Exception {
        controller.setFractalType(FractalType.CUSTOM_SHADER);
        var ngp = (org.fractalizer.fractals.NodeGraphParams) controller.getParams();
        var csp = (org.fractalizer.fractals.CustomShaderParams) ((org.fractalizer.graph.FractalNode) ngp.getGraphRoot()).getFractalParams();
        csp.setShaderSource(csp.getShaderSource().replace(
                "float DE_simple(vec3 pos) {\n    return length(pos) - radius;",
                "float DE_simple(vec3 pos) {\n    return length(pos) - radius + 0.0 * texture(paletteTexture, vec2(pos.x, 0.5)).r;"));
        ngp.markDirty();
        ngp.setPathTracingEnabled(false);
        ngp.setDeepPrecision(false);
        BufferedImage off = controller.renderStill(160, 90, 2, () -> false);
        ngp.setDeepPrecision(true);
        BufferedImage on = controller.renderStill(160, 90, 2, () -> false);
        boolean deepHeld = controller.holdsSceneProgram();
        int differ = 0;
        for (int y = 0; y < 90; y++) for (int x = 0; x < 160; x++) if (off.getRGB(x, y) != on.getRGB(x, y)) differ++;

        // The viewport: the error status, then an image all the same.
        controller.restoreViewportSize();
        java.util.concurrent.atomic.AtomicReference<String> message = new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch image = new CountDownLatch(1);
        controller.setViewportListener(new org.fractalizer.render.ViewportEvent.Listener() {
            @Override public void onImage(org.fractalizer.render.ViewportEvent.ViewportImage img) { image.countDown(); }
            @Override public void onStatus(org.fractalizer.render.ViewportEvent.Status st) {
                if (st.phase() == org.fractalizer.render.ViewportEvent.Phase.ERROR) message.compareAndSet(null, st.message());
            }
        });
        csp.setShaderSource(csp.getShaderSource().replace("radius + 0.0", "radius + 0.0 * 1.0"));   // a new source: compiled afresh
        ngp.markDirty();
        Platform.runLater(controller::requestRender);
        boolean shown = image.await(120, TimeUnit.SECONDS);
        System.out.println("--- a scene whose estimator does not compile in double ---");
        System.out.printf("  still: double program %s, %d of %d pixels differ from deep precision off%n",
                deepHeld ? "COMPILED (expected to fail)" : "refused, float kept", differ, 160 * 90);
        System.out.printf("  viewport: %s; image %s%n", message.get() == null ? "NO MESSAGE" : "\"" + message.get().lines().findFirst().orElse("") + "\"",
                shown ? "shown" : "NEVER SHOWN");
        return !deepHeld && differ == 0 && message.get() != null && message.get().startsWith("Deep precision") && shown;
    }

    static int run(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "MANDELBOX";
        String outDir = args.length > 1 ? args[1] : "out/precision";
        String[] res = (args.length > 2 ? args[2] : "640x360").split("x");
        int w = Integer.parseInt(res[0]), h = Integer.parseInt(res[1]);
        int samples = args.length > 3 ? Integer.parseInt(args[3]) : 8;
        String ladderSpec = args.length > 4 ? args[4] : "1e-1,1e-2,1e-3,1e-4,1e-5,1e-6";
        new File(outDir).mkdirs();

        GLSLFractalizerController controller = start(w, h);
        FocusPickProbe.loadScene(controller, spec);
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        // "asis": the scene's own shading (path tracing, fog...), to see what the app shows;
        // otherwise classic shading, which the normal metrics are made for.
        boolean asis = java.util.Arrays.asList(args).contains("asis");
        if (!asis) {
            params.setPathTracingEnabled(false);
            params.setConeTracingEnabled(true);
        }
        params.setDofEnabled(false);
        Camera camera = params.getCamera();
        if (args.length > 5 && args[5].contains(",")) {
            String[] v = args[5].split(",");
            float[] eye = {Float.parseFloat(v[0]), Float.parseFloat(v[1]), Float.parseFloat(v[2])};
            float[] q = CameraUtils.lookAt(eye, new float[]{Float.parseFloat(v[3]), Float.parseFloat(v[4]), Float.parseFloat(v[5])});
            camera.setPosition(eye[0], eye[1], eye[2]);
            camera.setQuaternion(q[0], q[1], q[2], q[3]);
        }

        // The surface point: the centre ray's hit, by the probe in double.
        params.setDeepPrecision(true);
        controller.renderStill(32, 18, 1, () -> false);
        controller.restoreViewportSize();
        if (!controller.holdsSceneProgram()) {
            System.out.println("The double program does not compile for this scene. RESULT: FAIL");
            return 1;
        }
        float[] f = camera.getForwardVector();
        double fl = Math.sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]);
        double[] fwd = {f[0] / fl, f[1] / fl, f[2] / fl};
        double[] eye0 = camera.getPositionD();
        double t0 = centreDistance(controller);
        if (!(t0 > 0)) {
            System.out.println("The centre ray misses: nothing to dive into. RESULT: FAIL");
            return 1;
        }
        double[] S = along(eye0, fwd, t0);

        System.out.printf(Locale.ROOT, "=== PrecisionLab: %s (%dx%d, %d spp) ===%n", spec, w, h, samples);
        System.out.printf(Locale.ROOT, "surface point (%.12f, %.12f, %.12f), first seen at %.6g%n", S[0], S[1], S[2], t0);
        System.out.printf("%-9s %-6s | %9s %7s %6s %8s %5s | %7s %8s | %7s %7s | %6s%n",
                "distance", "mode", "detail", "edges%", "lum", "contrast", "cov%", "rough", "speckle%", "err", ">10deg%", "ms");

        String[] modeName = {"float", "double"};
        List<double[]> rungs = new ArrayList<>();
        List<Result[]> rows = new ArrayList<>();
        List<double[]> errs = new ArrayList<>();
        for (String s : ladderSpec.split(",")) {
            double c = Double.parseDouble(s.trim());
            // Place the camera c before the point, then refine the point from there: the probe
            // there is exact to c times a float ulp, so each rung is as exact as it needs to be.
            params.setDeepPrecision(true);
            double[] eye = along(S, fwd, -c);
            camera.setPosition(eye[0], eye[1], eye[2]);
            double t = centreDistance(controller);
            if (t > 0) S = along(eye, fwd, t);
            eye = along(S, fwd, -c);
            camera.setPosition(eye[0], eye[1], eye[2]);
            rungs.add(new double[]{c});

            Result[] pair = new Result[2];
            File[] dep = new File[2];
            for (int mode = 0; mode < 2; mode++) {
                params.setDeepPrecision(mode == 1);
                String tag = String.format(Locale.ROOT, "d%s_%s", sci(c), modeName[mode]);
                controller.renderStill(32, 18, 1, () -> false);          // compile outside the timing
                long start = System.nanoTime();
                controller.setExportSize(w, h);
                File rgb = new File(outDir, tag + ".png");
                controller.exportToPNG(rgb, samples, p -> {}, () -> false).get();
                long ms = (System.nanoTime() - start) / 1_000_000;
                controller.setExportSize(w, h);
                File nrm = new File(outDir, tag + "_normals.png");
                controller.exportAOV(nrm, 1);
                controller.setExportSize(w, h);
                dep[mode] = new File(outDir, tag + "_depth.png");
                controller.exportAOV(dep[mode], 2);
                // The light: ambient occlusion and the sun's shadow alone (render modes 3, 4),
                // what the secondary rays from the hit point make of it.
                controller.setExportSize(w, h);
                controller.exportAOV(new File(outDir, tag + "_ao.png"), 3);
                controller.setExportSize(w, h);
                controller.exportAOV(new File(outDir, tag + "_shadow.png"), 4);
                DeepZoomLab.Metrics m = DeepZoomLab.score(rgb, dep[mode]);
                double[] nq = normalStats(nrm, dep[mode]);
                pair[mode] = new Result(tag, m, nq[0], nq[1], ms, rgb.getName(), nrm.getName());
            }
            double[] err = normalError(new File(outDir, pair[0].nrm()), new File(outDir, pair[1].nrm()), dep[1]);
            errs.add(err);
            for (int mode = 0; mode < 2; mode++) {
                Result r = pair[mode];
                System.out.printf(Locale.ROOT, "%-9s %-6s | %9.1f %7.2f %6.1f %8.1f %5.0f | %7.2f %8.2f | %7s %7s | %6d%n",
                        sci(c), modeName[mode], r.m().detail(), 100 * r.m().edges(), r.m().lum(), r.m().contrast(),
                        100 * r.m().coverage(), r.rough(), 100 * r.speckle(),
                        mode == 1 ? "ref" : String.format(Locale.ROOT, "%.2f", err[0]),
                        mode == 1 ? "ref" : String.format(Locale.ROOT, "%.1f", 100 * err[1]), r.ms());
            }
            System.out.flush();
            rows.add(pair);
        }
        writeReport(new File(outDir, "report.html"), spec, rungs, rows, errs);
        boolean ok = !errs.isEmpty() && errs.get(0)[0] < 2.0;
        System.out.println("report: " + new File(outDir, "report.html").getAbsolutePath());
        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL (float and double disagree on the first rung)");
        controller.close();
        Platform.exit();
        return ok ? 0 : 1;
    }

    /** The probe's distance along the centre ray, -1 for a miss. */
    static double centreDistance(GLSLFractalizerController controller) throws Exception {
        CompletableFuture<GLSLEngine.ProbeHit[]> f = new CompletableFuture<>();
        controller.probe(new float[]{0f, 0f}, f::complete);
        GLSLEngine.ProbeHit[] hits = f.get(60, TimeUnit.SECONDS);
        return hits == null || !hits[0].hit() ? -1 : hits[0].distance();
    }

    static double[] along(double[] p, double[] d, double t) {
        return new double[]{p[0] + d[0] * t, p[1] + d[1] * t, p[2] + d[2] * t};
    }

    /** { mean angle between neighbouring surface normals in degrees, share of isolated flips }. */
    static double[] normalStats(File normalsPng, File depthPng) throws Exception {
        BufferedImage nm = ImageIO.read(normalsPng), dep = ImageIO.read(depthPng);
        int w = nm.getWidth(), h = nm.getHeight();
        double[][] n = new double[w * h][];
        boolean[] surf = new boolean[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            n[y * w + x] = decodeNormal(nm.getRGB(x, y));
            surf[y * w + x] = dep.getRaster().getSample(x, y, 0) / 65535.0 > 0.02;
        }
        double angSum = 0;
        long angN = 0, speckle = 0, surfN = 0;
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            int i = y * w + x;
            if (!surf[i]) continue;
            surfN++;
            boolean allFar = true;
            for (int j : new int[]{i - 1, i + 1, i - w, i + w}) {
                if (!surf[j]) { allFar = false; continue; }
                double a = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot(n[i], n[j])))));
                angSum += a;
                angN++;
                if (a <= 25.0) allFar = false;
            }
            if (allFar) speckle++;
        }
        if (surfN < 100) return new double[]{0, 0};
        return new double[]{angN == 0 ? 0 : angSum / angN, (double) speckle / surfN};
    }

    /** { mean angle in degrees between two renders' normals, share above 10° } over the
     *  reference's surface pixels. */
    static double[] normalError(File normalsPng, File referencePng, File referenceDepthPng) throws Exception {
        BufferedImage a = ImageIO.read(normalsPng), o = ImageIO.read(referencePng), dep = ImageIO.read(referenceDepthPng);
        int w = o.getWidth(), h = o.getHeight();
        double sum = 0;
        long n = 0, bad = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if (dep.getRaster().getSample(x, y, 0) / 65535.0 <= 0.02) continue;
            double ang = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot(decodeNormal(a.getRGB(x, y)), decodeNormal(o.getRGB(x, y)))))));
            sum += ang;
            n++;
            if (ang > 10.0) bad++;
        }
        return n == 0 ? new double[]{0, 0} : new double[]{sum / n, (double) bad / n};
    }

    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    static double[] decodeNormal(int p) {
        double nx = ((p >> 16) & 0xFF) / 127.5 - 1, ny = ((p >> 8) & 0xFF) / 127.5 - 1, nz = (p & 0xFF) / 127.5 - 1;
        double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        return l < 1e-6 ? new double[]{0, 0, 1} : new double[]{nx / l, ny / l, nz / l};
    }

    static String sci(double v) {
        return String.format(Locale.ROOT, "%.0e", v).replace("e-0", "e-").replace("e+0", "e+");
    }

    static void writeReport(File out, String spec, List<double[]> rungs, List<Result[]> rows, List<double[]> errs) throws Exception {
        try (PrintWriter p = new PrintWriter(out, StandardCharsets.UTF_8)) {
            p.println("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Precision lab</title><style>");
            p.println("body{font:14px/1.45 system-ui,sans-serif;background:#141518;color:#e6e6e6;margin:0;padding:24px 32px}");
            p.println("h1{font-size:22px;margin:0 0 4px}h2{font-size:16px;margin:24px 0 8px;color:#9fc2ff}");
            p.println("table{border-collapse:collapse;margin:8px 0 16px}th,td{padding:4px 10px;border-bottom:1px solid #2a2c33;text-align:right;font-variant-numeric:tabular-nums}");
            p.println("th{color:#9fc2ff}td:first-child,th:first-child{text-align:left}img{width:100%;display:block;border-radius:4px}");
            p.println(".pair{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}.pair div{font-size:12px;color:#b5b5b5}");
            p.println("</style></head><body>");
            p.printf("<h1>Precision lab: %s</h1>%n", spec);
            p.println("<p>The same point seen from a ladder of distances, marched in float and in double (the scene's estimator translated to double, DeepSource). "
                    + "err: angle between the float and double normals, the double run being the most exact the GPU makes.</p>");
            p.println("<table><tr><th>distance</th><th>mode</th><th>detail</th><th>rough</th><th>speckle %</th><th>err</th><th>&gt;10° %</th><th>ms</th></tr>");
            for (int i = 0; i < rows.size(); i++) {
                for (int mode = 0; mode < 2; mode++) {
                    Result r = rows.get(i)[mode];
                    p.printf(Locale.ROOT, "<tr><td>%s</td><td>%s</td><td>%.1f</td><td>%.2f</td><td>%.2f</td><td>%s</td><td>%s</td><td>%d</td></tr>%n",
                            sci(rungs.get(i)[0]), mode == 0 ? "float" : "double", r.m().detail(), r.rough(), 100 * r.speckle(),
                            mode == 1 ? "ref" : String.format(Locale.ROOT, "%.2f", errs.get(i)[0]),
                            mode == 1 ? "ref" : String.format(Locale.ROOT, "%.1f", 100 * errs.get(i)[1]), r.ms());
                }
            }
            p.println("</table>");
            for (int i = 0; i < rows.size(); i++) {
                Result fl = rows.get(i)[0], db = rows.get(i)[1];
                p.printf("<h2>%s from the surface</h2><div class=\"pair\">%n", sci(rungs.get(i)[0]));
                p.printf("<div><img src=\"%s\">float</div><div><img src=\"%s\">double</div><div><img src=\"%s\">float normals</div><div><img src=\"%s\">double normals</div></div>%n",
                        fl.rgb(), db.rgb(), fl.nrm(), db.nrm());
            }
            p.println("</body></html>");
        }
    }
}
