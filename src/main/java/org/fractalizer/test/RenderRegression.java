package org.fractalizer.test;

import org.fractalizer.ui.GLSLFractalizerController;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;

import javafx.application.Platform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Render regression + benchmark harness.
 *
 * Renders a fixed set of representative scenes deterministically so shader and
 * rendering changes can be validated automatically (no eyeballing) and timed
 * reliably (median over several runs, several scenes).
 *
 * Usage:
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.RenderRegression" -Dexec.args="update"
 *       -> render every scene and (re)write the golden images. Run once on a known-good build.
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.RenderRegression" -Dexec.args="check"
 *       -> render every scene, diff against the goldens, print PASS/FAIL + timing. Exit code 1 on any FAIL.
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.RenderRegression" -Dexec.args="bench"
 *       -> timing only (median of {@link #BENCH_RUNS} runs per scene).
 *
 * Goldens live in {@link #GOLDEN_DIR}. They are GPU/driver specific, so regenerate
 * them with "update" when moving machines (and never on a build you suspect is broken).
 */
public class RenderRegression {

    private static final String GOLDEN_DIR = "out/test_regression/golden";
    private static final int W = 480, H = 270;
    private static final int TIMED_RUNS = 5;   // measured render passes per scene (check/bench)
    private static final int BENCH_RUNS = 5;

    /** A deterministic scene. tol = max mean per-channel delta (/255) allowed vs golden.
     *  cam = {eX,eY,eZ,tX,tY,tZ,fov} for a detail view, or null for the fractal default. */
    private record Scene(String name, FractalType type, Consumer<AbstractFractalParams> cfg,
                         int samples, double tol, float[] cam) {}

    private static List<Scene> activeScenes;

    private static List<Scene> defaultScenes() {
        List<Scene> s = new ArrayList<>();
        s.add(new Scene("mandelbulb_classic",     FractalType.MANDELBULB,         pt(false), 16, 1.5, null));
        s.add(new Scene("mandelbulb_pathtraced",  FractalType.MANDELBULB,         ptSSS(),   32, 3.0, null));
        s.add(new Scene("menger_classic",         FractalType.MENGER_SPONGE,      pt(false), 16, 1.5, null));
        s.add(new Scene("mandelbox_classic",      FractalType.MANDELBOX,          pt(false), 16, 1.5, null));
        s.add(new Scene("kaleidoscopic_classic",  FractalType.KALEIDOSCOPIC_IFS,  pt(false), 16, 1.5, null));
        s.add(new Scene("apollonian_classic",     FractalType.APOLLONIAN,         pt(false), 16, 1.5, null));
        s.add(new Scene("sierpinski_classic",     FractalType.SIERPINSKI,         pt(false), 16, 1.5, null));
        s.add(new Scene("quatjulia_classic",      FractalType.QUATERNION_JULIA_4D,pt(false), 16, 1.5, null));
        return s;
    }

    /** Detail scenes from a FractalNavigator manifest (type eye3 tgt3 fov per line). */
    private static List<Scene> manifestScenes(String path) throws Exception {
        List<Scene> s = new ArrayList<>();
        for (String line : java.nio.file.Files.readAllLines(new File(path).toPath())) {
            String ln = line.trim();
            if (ln.isEmpty() || ln.startsWith("#")) continue;
            String[] t = ln.split("\\s+");
            float[] cam = new float[7];
            for (int i = 0; i < 7; i++) cam[i] = Float.parseFloat(t[1 + i]);
            s.add(new Scene("detail_" + t[0].toLowerCase(), FractalType.valueOf(t[0]), pt(false), 16, 1.5, cam));
        }
        return s;
    }

    private static Consumer<AbstractFractalParams> pt(boolean on) {
        return p -> p.setPathTracingEnabled(on);
    }

    // Path-traced scene that also exercises the SSS / soft-shadow code paths.
    private static Consumer<AbstractFractalParams> ptSSS() {
        return p -> { p.setPathTracingEnabled(true); p.setSssIntensity(0.6f); p.setSssRadius(0.2f); };
    }

    public static void main(String[] args) throws Exception {
        String mode = (args.length > 0) ? args[0].toLowerCase() : "check";
        String manifest = (args.length > 1) ? args[1] : null;

        Platform.startup(() -> {});
        new File(GOLDEN_DIR).mkdirs();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        controller.setExportSize(W, H);

        activeScenes = (manifest != null) ? manifestScenes(manifest) : defaultScenes();
        System.out.println(manifest != null ? "Scenes: DETAIL views (" + manifest + ")" : "Scenes: default global views");

        boolean ok = switch (mode) {
            case "update" -> update(controller);
            case "bench"  -> { bench(controller); yield true; }
            default       -> check(controller);
        };

        System.exit(ok ? 0 : 1);
    }

    // ---- modes -------------------------------------------------------------

    private static boolean update(GLSLFractalizerController c) throws Exception {
        System.out.println("=== UPDATE goldens (" + W + "x" + H + ") ===");
        for (Scene sc : activeScenes) {
            renderTo(c, sc, new File(GOLDEN_DIR, sc.name + ".png"));
            // Self-diff: render again to a temp file and compare, to expose the
            // non-determinism noise floor (informs whether the tolerances hold).
            File tmp = File.createTempFile("rr_self_", ".png");
            renderTo(c, sc, tmp);
            Diff d = diff(new File(GOLDEN_DIR, sc.name + ".png"), tmp);
            tmp.delete();
            System.out.printf("  %-24s golden written | self-diff meanD=%.3f maxD=%d%n", sc.name, d.mean, d.max);
        }
        System.out.println("Goldens in: " + new File(GOLDEN_DIR).getAbsolutePath());
        return true;
    }

    private static boolean check(GLSLFractalizerController c) throws Exception {
        System.out.println("=== CHECK vs goldens (" + W + "x" + H + ") ===");
        boolean allPass = true;
        for (Scene sc : activeScenes) {
            File golden = new File(GOLDEN_DIR, sc.name + ".png");
            if (!golden.exists()) {
                System.out.printf("  %-24s  NO GOLDEN (run 'update' first)%n", sc.name);
                allPass = false;
                continue;
            }
            File tmp = File.createTempFile("rr_check_", ".png");
            long ms = timedRender(c, sc, tmp);
            Diff d = diff(golden, tmp);
            tmp.delete();
            boolean pass = d.mean <= sc.tol;
            allPass &= pass;
            System.out.printf("  %-24s  %s  meanD=%.3f maxD=%3d  %5.1f%% px>2  | %5d ms%n",
                sc.name, pass ? "PASS" : "FAIL", d.mean, d.max, d.pctChanged, ms);
        }
        System.out.println(allPass ? "RESULT: ALL PASS" : "RESULT: FAIL");
        return allPass;
    }

    private static void bench(GLSLFractalizerController c) throws Exception {
        System.out.println("=== BENCH (" + W + "x" + H + ", median of " + BENCH_RUNS + ") ===");
        long total = 0;
        for (Scene sc : activeScenes) {
            File tmp = File.createTempFile("rr_bench_", ".png");
            long ms = timedRender(c, sc, tmp);
            tmp.delete();
            total += ms;
            System.out.printf("  %-24s  median %5d ms%n", sc.name, ms);
        }
        System.out.printf("  %-24s  total  %5d ms%n", "ALL", total);
    }

    // ---- helpers -----------------------------------------------------------

    private static void renderTo(GLSLFractalizerController c, Scene sc, File out) throws Exception {
        c.setFractalType(sc.type);
        AbstractFractalParams p = (AbstractFractalParams) c.getParams();
        sc.cfg.accept(p);
        if (sc.cam != null) {
            float[] cm = sc.cam;
            p.getCamera().setPosition(cm[0], cm[1], cm[2]);
            float[] q = CameraUtils.lookAt(new float[]{cm[0], cm[1], cm[2]}, new float[]{cm[3], cm[4], cm[5]});
            p.getCamera().setQuaternion(q[0], q[1], q[2], q[3]);
            p.setFovDegrees(cm[6]);
        }
        c.setExportSize(W, H);
        c.exportToPNG(out, sc.samples, p2 -> {}, () -> false).get();
    }

    /** Warmup once, then render TIMED_RUNS times to {@code out}; return the median ms. */
    private static long timedRender(GLSLFractalizerController c, Scene sc, File out) throws Exception {
        renderTo(c, sc, out); // warmup
        long[] t = new long[TIMED_RUNS];
        for (int i = 0; i < TIMED_RUNS; i++) {
            long t0 = System.nanoTime();
            renderTo(c, sc, out);
            t[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        java.util.Arrays.sort(t);
        return t[t.length / 2];
    }

    private record Diff(double mean, int max, double pctChanged) {}

    private static Diff diff(File aFile, File bFile) throws Exception {
        BufferedImage a = ImageIO.read(aFile);
        BufferedImage b = ImageIO.read(bFile);
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        long sum = 0; int max = 0, changed = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y), pb = b.getRGB(x, y);
                int dr = Math.abs(((pa >> 16) & 0xFF) - ((pb >> 16) & 0xFF));
                int dg = Math.abs(((pa >> 8) & 0xFF) - ((pb >> 8) & 0xFF));
                int db = Math.abs((pa & 0xFF) - (pb & 0xFF));
                sum += dr + dg + db;
                int m = Math.max(dr, Math.max(dg, db));
                if (m > max) max = m;
                if (m > 2) changed++;
            }
        }
        long n = (long) w * h;
        return new Diff((double) sum / (n * 3.0), max, 100.0 * changed / n);
    }
}
