package org.fractalizer.test;

import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.MandelbulbParams;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/**
 * Autonomous prospector for new Julia Mandelbulbs.
 *
 * The Julia constant is a 3D parameter and every value of it is a different fractal,
 * so the space of shapes is continuous and mostly uninteresting: a constant well
 * inside the Mandelbulb gives a smooth blob, one well outside gives disconnected
 * dust. The interesting constants are the ones ON the boundary — the Mandelbrot /
 * Julia duality — and the distance estimator already knows where that boundary is.
 *
 * So the search is not random:
 *   1. CPU: sphere-trace the Mandelbulb DE inward along a low-discrepancy set of
 *      directions to land exactly on its surface. Every landing point is a boundary
 *      constant by construction, no rendering needed. An offset walks the candidate
 *      slightly inside or outside to vary how connected the resulting set is.
 *   2. GPU: render a small thumbnail of the Julia set for each surviving candidate.
 *   3. Score it the way FractalNavigator scores a framing — fine-detail energy,
 *      surface coverage in a pleasing band, and where the detail sits in frame.
 *   4. Rank, write a contact sheet, and emit the winners as .frac presets.
 *
 * Usage:
 *   -Dexec.args="&lt;outDir&gt; &lt;candidates&gt; &lt;WxH&gt; &lt;samples&gt; [keepAsPresets]"
 *   -Dexec.args="out/prospect 96 320x180 6 8"
 */
public class JuliaProspector {

    // Mandelbulb parameters the search is run against; the CPU DE below must match
    // the GLSL one in fractals/mandelbulb.glsl for the landing points to be real.
    private static final double POWER = 8.0, BAILOUT = 2.0;
    private static final int ITERS = 15;

    // ------------------------------------------------------------------
    // CPU distance estimator — the same formula as the shader
    // ------------------------------------------------------------------

    static double mandelbulbDE(double[] p) {
        double zx = p[0], zy = p[1], zz = p[2];
        double dr = 1.0, r = 0.0;
        for (int i = 0; i < ITERS; i++) {
            r = Math.sqrt(zx * zx + zy * zy + zz * zz);
            if (r > BAILOUT) break;
            if (r < 1e-12) break;
            double theta = Math.acos(zz / r) * POWER;
            double phi = Math.atan2(zy, zx) * POWER;
            dr = Math.pow(r, POWER - 1.0) * POWER * dr + 1.0;
            double zr = Math.pow(r, POWER);
            zx = zr * Math.sin(theta) * Math.cos(phi) + p[0];
            zy = zr * Math.sin(theta) * Math.sin(phi) + p[1];
            zz = zr * Math.cos(theta) + p[2];
        }
        if (r < 1e-12) return 0.0;
        return 0.5 * Math.log(r) * r / dr;
    }

    /** March inward from outside along -dir until the DE says we are on the surface.
     *  Returns the landing point, or null if the ray missed the fractal entirely. */
    static double[] landOnSurface(double[] dir) {
        double[] p = {dir[0] * 2.0, dir[1] * 2.0, dir[2] * 2.0};
        for (int i = 0; i < 160; i++) {
            double d = mandelbulbDE(p);
            if (d < 1e-4) return p;
            double step = Math.max(d * 0.9, 1e-5);
            p = new double[]{p[0] - dir[0] * step, p[1] - dir[1] * step, p[2] - dir[2] * step};
            double rr = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
            if (rr < 0.05) return null;   // fell through the middle: no surface this way
        }
        return null;
    }

    /** Fibonacci sphere: even, deterministic coverage of directions. */
    static double[] sphereDirection(int i, int n) {
        double phi = Math.PI * (3.0 - Math.sqrt(5.0));      // golden angle
        double y = 1.0 - 2.0 * (i + 0.5) / n;
        double radius = Math.sqrt(Math.max(0.0, 1.0 - y * y));
        double theta = phi * i;
        return new double[]{Math.cos(theta) * radius, y, Math.sin(theta) * radius};
    }

    // ------------------------------------------------------------------

    /** Minimum distance between two kept constants, in constant space. */
    private static final double MIN_SEPARATION = 0.35;

    record Candidate(double[] c, double score, double detail, double coverage, String tag) {}

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "out/prospect";
        int wanted = args.length > 1 ? Integer.parseInt(args[1]) : 96;
        String[] res = (args.length > 2 ? args[2] : "320x180").split("x");
        int W = Integer.parseInt(res[0]), H = Integer.parseInt(res[1]);
        int samples = args.length > 3 ? Integer.parseInt(args[3]) : 6;
        int keep = args.length > 4 ? Integer.parseInt(args[4]) : 8;

        new File(outDir).mkdirs();

        // --- Phase 1: boundary constants, found on the CPU, no rendering ---
        // Three offsets per direction: just inside, on, and just outside the surface.
        // Inside tends to give fat connected sets, outside more filigree.
        double[] offsets = {-0.02, 0.0, 0.03};
        List<double[]> constants = new ArrayList<>();
        int dirs = (wanted + offsets.length - 1) / offsets.length;
        int missed = 0;
        for (int i = 0; i < dirs; i++) {
            double[] dir = sphereDirection(i, dirs);
            double[] surf = landOnSurface(dir);
            if (surf == null) { missed++; continue; }
            for (double off : offsets) {
                constants.add(new double[]{surf[0] * (1 + off), surf[1] * (1 + off), surf[2] * (1 + off)});
            }
        }
        System.out.printf("=== Julia prospector ===%n");
        System.out.printf("boundary search: %d directions, %d missed, %d candidate constants%n",
                dirs, missed, constants.size());

        // --- Phase 2/3: render and score ---
        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        controller.setFractalType(FractalType.MANDELBULB);
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        MandelbulbParams mb = (MandelbulbParams) ((NodeGraphParams) params).getRootFractalParams();
        params.setPathTracingEnabled(false);          // classic shading: fast enough to scan
        mb.setMaxIterations(20);
        controller.updatePaletteTexture(params.getCustomGradient());

        // A Julia bulb sits inside roughly |z| < 1.5; one camera frames them all well
        // enough for ranking, and a bad fit is punished by the coverage term anyway.
        float[] eye = {1.20f, 0.85f, -1.85f}, target = {0f, 0f, 0f};
        params.getCamera().setPosition(eye[0], eye[1], eye[2]);
        float[] q = CameraUtils.lookAt(eye, target);
        params.getCamera().setQuaternion(q[0], q[1], q[2], q[3]);
        params.setFovDegrees(50);

        List<Candidate> scored = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int i = 0; i < constants.size(); i++) {
            double[] c = constants.get(i);
            mb.setJuliaCx((float) c[0]);
            mb.setJuliaCy((float) c[1]);
            mb.setJuliaCz((float) c[2]);

            String tag = String.format(Locale.ROOT, "j%03d", i);
            controller.setExportSize(W, H);
            File rgb = new File(outDir, tag + ".png");
            controller.exportToPNG(rgb, samples, p -> {}, () -> false).get();
            controller.setExportSize(W, H);
            File dep = new File(outDir, tag + "_d.png");
            controller.exportAOV(dep, 2);

            org.fractalizer.explore.FrameScorer.FrameScore fs = FractalNavigator.scoreFrame(rgb, dep);
            dep.delete();
            scored.add(new Candidate(c, fs.aesthetic(), fs.detail(), fs.coverage(), tag));
            if ((i + 1) % 16 == 0) {
                System.out.printf("  scored %d/%d%n", i + 1, constants.size());
                System.out.flush();
            }
        }
        System.out.printf("rendered+scored %d in %.1f s%n",
                scored.size(), (System.nanoTime() - t0) / 1e9);

        // --- Phase 4: rank and report ---
        scored.sort(Comparator.comparingDouble(Candidate::score).reversed());

        // Constants that sit close together produce near-identical sets, so ranking
        // alone returns a family portrait. Walk the ranking and keep a candidate only
        // if it is far enough in constant space from everything already kept — the
        // gallery then shows the variety of the space rather than its best neighbourhood.
        List<Candidate> diverse = new ArrayList<>();
        for (Candidate k : scored) {
            boolean tooClose = false;
            for (Candidate d : diverse) {
                double dx = k.c()[0] - d.c()[0], dy = k.c()[1] - d.c()[1], dz = k.c()[2] - d.c()[2];
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) < MIN_SEPARATION) { tooClose = true; break; }
            }
            if (!tooClose) diverse.add(k);
        }
        System.out.printf("diversity filter: %d of %d survive a %.2f separation%n",
                diverse.size(), scored.size(), MIN_SEPARATION);
        try (PrintWriter pw = new PrintWriter(new File(outDir, "ranking.txt"))) {
            pw.println("# rank tag score detail coverage juliaCx juliaCy juliaCz");
            for (int i = 0; i < scored.size(); i++) {
                Candidate k = scored.get(i);
                pw.printf(Locale.ROOT, "%3d %s %10.0f %10.0f %5.2f %8.4f %8.4f %8.4f%n",
                        i + 1, k.tag(), k.score(), k.detail(), k.coverage(),
                        k.c()[0], k.c()[1], k.c()[2]);
            }
        }

        System.out.println();
        System.out.println("top 12 (diverse):");
        System.out.printf("%4s %-6s %10s %10s %6s  %s%n", "rank", "tag", "score", "detail", "cov", "juliaC");
        for (int i = 0; i < Math.min(12, diverse.size()); i++) {
            Candidate k = diverse.get(i);
            System.out.printf(Locale.ROOT, "%4d %-6s %10.0f %10.0f %5.0f%%  (%.4f, %.4f, %.4f)%n",
                    i + 1, k.tag(), k.score(), k.detail(), 100 * k.coverage(),
                    k.c()[0], k.c()[1], k.c()[2]);
        }

        // Contact sheet of the winners, in rank order
        int sheetN = Math.min(12, diverse.size());
        int cols = 4, rows = (sheetN + cols - 1) / cols;
        BufferedImage sheet = new BufferedImage(W * cols, H * rows, BufferedImage.TYPE_INT_RGB);
        var g = sheet.createGraphics();
        for (int i = 0; i < sheetN; i++) {
            BufferedImage im = ImageIO.read(new File(outDir, diverse.get(i).tag() + ".png"));
            g.drawImage(im, (i % cols) * W, (i / cols) * H, null);
        }
        g.dispose();
        ImageIO.write(sheet, "png", new File(outDir, "_top.png"));

        // Winners as presets, ready to open
        for (int i = 0; i < Math.min(keep, diverse.size()); i++) {
            Candidate k = diverse.get(i);
            SceneBuilder.mandelbulb()
                .param("maxIterations", 20)
                .param("juliaCx", k.c()[0]).param("juliaCy", k.c()[1]).param("juliaCz", k.c()[2])
                .camera(1.20f, 0.85f, -1.85f).lookAt(0f, 0f, 0f).fov(50)
                .pathTracing(true).skyType(1)
                .lightDir(2, 3, -2).lightIntensity(1.3f)
                .ambientColor(0.10f, 0.14f, 0.22f).ambientIntensity(0.35f)
                .colorStrength(0.9f).metalness(0.3f).roughness(0.4f)
                .writeTo(new File(outDir, String.format(Locale.ROOT, "FOUND_%02d.frac", i + 1)));
        }

        System.out.println();
        System.out.println("contact sheet -> " + new File(outDir, "_top.png").getAbsolutePath());
        System.out.println("ranking       -> " + new File(outDir, "ranking.txt").getAbsolutePath());
        System.exit(0);
    }
}
