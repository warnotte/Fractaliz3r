package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.explore.FrameScorer;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.GraphNodeNamer;
import org.fractalizer.graph.HybridNode;
import org.fractalizer.graph.HybridNode.DEMode;
import org.fractalizer.graph.HybridNode.Step;
import org.fractalizer.graph.HybridNode.StepType;
import org.fractalizer.graph.HybridPresets;
import org.fractalizer.ui.GLSLFractalizerController;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Autonomous prospector for new fractals in hybrid-chain space.
 *
 * A hybrid chain composes maps inside one iteration loop: 28 step types, each with
 * parameters, an estimator, iteration gating. A random chain of three or four steps is a
 * formula nobody has written down. Most are nothing (the orbit escapes at once, or never:
 * an empty frame or a solid ball), some are a known shape under another name, a few are
 * worlds. This harness draws chains, renders them, scores them, throws the nothing away,
 * marks the known, and writes the rest as presets.
 *
 * What is baked into a chain's GLSL and what is a uniform decides the shape of the search
 * (GraphCompiler.generateHybridGLSL): the step types, their order, the gating, the
 * estimator and the axes are compiled in; every numeric parameter is a uniform. So the
 * search compiles one shader per <b>structure</b> (types + gating + estimator, ~10 s each
 * on the dev machine) and then sweeps many <b>parameter draws</b> through it at a few
 * milliseconds a frame. Twenty structures times ten draws is two hundred candidates in
 * about four minutes, nearly all of it compile time.
 *
 * Each candidate is framed automatically (back off while the view is filled or cut by the
 * frame, come closer while it is empty, on the depth AOV), then scored like a framing in
 * FractalNavigator (fine-detail energy, surface coverage in a pleasing band, where the
 * detail sits) times a structure factor (FrameScorer.structure: surface whose neighbours
 * are surface at a continuous depth), because detail alone scores a ball of dust as high
 * as a carved solid. A candidate whose step sequence is one of the library's chains is kept
 * but marked and ranked down: the point is what the library does not have.
 *
 * Usage:
 *   -Dexec.args="&lt;outDir&gt; &lt;structures&gt; &lt;drawsPerStructure&gt; &lt;WxH&gt; &lt;samples&gt; [keep] [seed]"
 *   -Dexec.args="out/prospect_hybrid 20 10 320x180 6 8 1"
 * Output under outDir: one PNG per surviving candidate, _top.png (the twelve best as a
 * labelled sheet), ranking.txt, and FOUND_NN.frac for the best `keep`, in the showcase look
 * with the camera the search settled on.
 */
public class HybridProspector {

    // ------------------------------------------------------------------
    // Structures: what gets compiled
    // ------------------------------------------------------------------

    /** One compiled shape of chain: the steps' types, order, gating and axes, the
     *  estimator, the iteration count and bailout. Parameters are drawn per candidate. */
    record Structure(String recipe, List<Step> steps, int iterations, float bailout, DEMode deMode, boolean julia) {
        String signature() {
            StringBuilder sb = new StringBuilder();
            for (Step s : steps) {
                if (sb.length() > 0) sb.append('>');
                sb.append(s.getType().name());
            }
            return sb.toString();
        }
    }

    private static final StepType[] POWER_STEPS = {
        StepType.BULB, StepType.BULB_COSINE, StepType.QUAT_SQUARE, StepType.BRISTOR,
        StepType.BENESI_MAG, StepType.RIEMANN, StepType.COMPLEX_POWER};
    /** Folds that behave as IFS contractions on their own (a chain of them is a shape). */
    private static final StepType[] IFS_FOLDS = {
        StepType.MENGER_FOLD, StepType.SIERPINSKI_FOLD, StepType.OCTA_FOLD, StepType.ICOSA_FOLD,
        StepType.BENESI_FOLD, StepType.BOX_FOLD, StepType.AMAZING_SURF, StepType.ABOX_MOD};
    /** Folds that reshape an orbit but do not contract it: they need a power map or an
     *  IFS fold next to them. */
    private static final StepType[] SHAPING_FOLDS = {
        StepType.BOX_FOLD_ONLY, StepType.SPHERE_FOLD, StepType.ABS_FOLD, StepType.PLANE_FOLD,
        StepType.ROTATIONAL_FOLD, StepType.KALI_FOLD, StepType.SPHERE_INVERT};
    private static final StepType[] TRANSFORMS = {
        StepType.ROTATE, StepType.ROTATE_ITER, StepType.TWIST, StepType.SCALE};

    private static <T> T pick(Random rnd, T[] from) { return from[rnd.nextInt(from.length)]; }

    /** A random structure from one of four recipes. The recipes are the grammar of the
     *  chains that are known to work (escape-time maps want a seed and the log estimator,
     *  IFS folds want the linear one, the Kleinian fold its plane trap), so the draw spends
     *  its time inside the region where shapes live instead of proving that a bare rotation
     *  is not a fractal. */
    static Structure randomStructure(Random rnd) {
        List<Step> steps = new ArrayList<>();
        int recipe = rnd.nextInt(4);
        String name;
        DEMode de;
        int iterations;
        float bailout;
        boolean julia;
        switch (recipe) {
            case 0 -> {   // escape-time: [transform] power [shaping fold] seed
                name = "power";
                if (rnd.nextInt(3) == 0) steps.add(new Step(pick(rnd, TRANSFORMS)));
                steps.add(new Step(pick(rnd, POWER_STEPS)));
                if (rnd.nextInt(2) == 0) steps.add(new Step(pick(rnd, SHAPING_FOLDS)));
                steps.add(new Step(StepType.ADD_C));
                de = DEMode.LOG; iterations = 10 + rnd.nextInt(7); bailout = 4f; julia = rnd.nextInt(2) == 0;
            }
            case 1 -> {   // IFS: one to three contracting folds with transforms between, no seed
                name = "ifs";
                int n = 1 + rnd.nextInt(3);
                for (int i = 0; i < n; i++) {
                    steps.add(new Step(pick(rnd, IFS_FOLDS)));
                    if (i < n - 1 && rnd.nextInt(2) == 0) steps.add(new Step(pick(rnd, TRANSFORMS)));
                }
                if (rnd.nextInt(3) == 0) steps.add(new Step(pick(rnd, SHAPING_FOLDS)));
                de = DEMode.LINEAR; iterations = 8 + rnd.nextInt(7); bailout = 100f; julia = false;
            }
            case 2 -> {   // Mandelbox-like: fold(s), seed; the seed makes it a Mandelbrot-type set
                name = "boxlike";
                steps.add(new Step(pick(rnd, IFS_FOLDS)));
                if (rnd.nextInt(2) == 0) steps.add(new Step(pick(rnd, SHAPING_FOLDS)));
                if (rnd.nextInt(3) == 0) steps.add(new Step(pick(rnd, TRANSFORMS)));
                steps.add(new Step(StepType.ADD_C));
                de = DEMode.LINEAR; iterations = 8 + rnd.nextInt(7); bailout = 100f; julia = rnd.nextInt(3) == 0;
            }
            default -> { // mixed: power map and a fold in one loop, seed
                name = "mixed";
                boolean powerFirst = rnd.nextBoolean();
                Step power = new Step(pick(rnd, POWER_STEPS));
                Step fold = new Step(rnd.nextBoolean() ? pick(rnd, IFS_FOLDS) : pick(rnd, SHAPING_FOLDS));
                if (powerFirst) { steps.add(power); steps.add(fold); } else { steps.add(fold); steps.add(power); }
                if (rnd.nextInt(3) == 0) steps.add(new Step(pick(rnd, TRANSFORMS)));
                steps.add(new Step(StepType.ADD_C));
                de = DEMode.LOG; iterations = 10 + rnd.nextInt(5); bailout = 4f; julia = rnd.nextInt(2) == 0;
            }
        }
        // The Kleinian fold is its own world: it never escapes, so it wants the plane trap.
        boolean kleinian = false;
        if (rnd.nextInt(8) == 0 && recipe != 0) {
            steps.add(rnd.nextInt(steps.size() + 1), new Step(StepType.KLEINIAN_FOLD));
            kleinian = true;
        }
        if (kleinian) { de = DEMode.PLANE; bailout = 1000f; }
        // Axes are baked: draw them now. Gate one step in four structures: the first
        // passes only, or every second pass. Never the seed, never the only shape.
        for (Step s : steps) {
            if (s.getType() == StepType.TWIST || s.getType() == StepType.ROTATIONAL_FOLD) s.setAxis(rnd.nextInt(3));
        }
        if (steps.size() >= 3 && rnd.nextInt(4) == 0) {
            Step g = steps.get(rnd.nextInt(steps.size()));
            if (g.getType() != StepType.ADD_C) {
                if (rnd.nextBoolean()) { g.setIterStart(0); g.setIterEnd(2 + rnd.nextInt(3)); }
                else { g.setIterStart(rnd.nextInt(2)); g.setIterEvery(2); }
            }
        }
        return new Structure(name, steps, iterations, bailout, de, julia);
    }

    // ------------------------------------------------------------------
    // Parameter draws: what stays a uniform
    // ------------------------------------------------------------------

    private static float uni(Random rnd, float lo, float hi) { return lo + rnd.nextFloat() * (hi - lo); }

    /** Fill a step's parameters with a plausible draw for its type. Ranges are the ones
     *  the library's chains live in, widened a little; the setters clamp the rest. */
    static void drawParams(Random rnd, Step s) {
        switch (s.getType()) {
            case BULB, BULB_COSINE -> s.setPower(2 + rnd.nextInt(8));
            case COMPLEX_POWER, RIEMANN -> { s.setPower(2 + rnd.nextInt(5)); s.setScale(uni(rnd, 0.6f, 2.0f)); }
            case BOX_FOLD, AMAZING_SURF, ABOX_MOD -> {
                float sc = uni(rnd, 1.4f, 3.0f);
                s.setScale(rnd.nextInt(5) < 2 ? -sc : sc);
                s.setMinRadius(uni(rnd, 0.1f, 0.6f)); s.setFixedRadius(uni(rnd, 0.8f, 1.5f));
                s.setFoldLimit(uni(rnd, 0.7f, 1.4f));
                s.setOffsetX(uni(rnd, 0.6f, 1.4f)); s.setOffsetY(uni(rnd, 0.6f, 1.4f)); s.setOffsetZ(uni(rnd, 0.6f, 1.4f));
            }
            case BOX_FOLD_ONLY -> s.setFoldLimit(uni(rnd, 0.5f, 1.5f));
            case SPHERE_FOLD -> { s.setMinRadius(uni(rnd, 0.1f, 0.6f)); s.setFixedRadius(uni(rnd, 0.8f, 1.5f)); }
            case MENGER_FOLD, SIERPINSKI_FOLD, OCTA_FOLD, ICOSA_FOLD, BENESI_FOLD -> {
                s.setScale(uni(rnd, 1.6f, 3.2f));
                s.setOffsetX(uni(rnd, 0.4f, 1.6f)); s.setOffsetY(uni(rnd, 0.0f, 1.6f)); s.setOffsetZ(uni(rnd, 0.0f, 1.6f));
            }
            case ABS_FOLD -> { s.setOffsetX(uni(rnd, 0.2f, 1.5f)); s.setOffsetY(uni(rnd, 0.2f, 1.5f)); s.setOffsetZ(uni(rnd, 0.2f, 1.5f)); }
            case PLANE_FOLD -> {
                s.setOffsetX(uni(rnd, -1f, 1f)); s.setOffsetY(uni(rnd, -1f, 1f)); s.setOffsetZ(uni(rnd, -1f, 1f));
                s.setDist(uni(rnd, -0.5f, 0.5f));
            }
            case ROTATIONAL_FOLD -> s.setCount(3 + rnd.nextInt(7));
            case KALI_FOLD -> {
                s.setOffsetX(uni(rnd, 0.2f, 1.0f)); s.setOffsetY(uni(rnd, 0.2f, 1.0f)); s.setOffsetZ(uni(rnd, 0.2f, 1.0f));
                s.setRadius(uni(rnd, 0.5f, 1.5f));
            }
            case KLEINIAN_FOLD -> {
                s.setOffsetX(uni(rnd, 0.6f, 1.2f)); s.setOffsetY(uni(rnd, 0.6f, 1.2f)); s.setOffsetZ(uni(rnd, 0.6f, 1.2f));
                s.setRadius(uni(rnd, 0.6f, 1.4f));
            }
            case SPHERE_INVERT -> s.setRadius(uni(rnd, 0.5f, 1.5f));
            case ROTATE -> { s.setRotX(uni(rnd, -90f, 90f)); s.setRotY(uni(rnd, -90f, 90f)); s.setRotZ(uni(rnd, -90f, 90f)); }
            case ROTATE_ITER -> { s.setRotX(uni(rnd, 0f, 30f)); s.setRotY(uni(rnd, 0f, 30f)); s.setRotZ(uni(rnd, 0f, 30f)); }
            case TWIST -> s.setRotX(uni(rnd, 5f, 60f));
            case SCALE -> { s.setScale(uni(rnd, 0.8f, 2.5f)); s.setOffsetX(uni(rnd, 0f, 1f)); s.setOffsetY(uni(rnd, 0f, 1f)); s.setOffsetZ(uni(rnd, 0f, 1f)); }
            case QUAT_SQUARE, BRISTOR, BENESI_MAG, ADD_C -> { }
        }
    }

    /** A Julia seed on a shell between 0.3 and 1.1: inside the set it is a blob, far
     *  outside it is dust, the shell is where the boundary tends to be. */
    static float[] drawJulia(Random rnd) {
        float x = (float) rnd.nextGaussian(), y = (float) rnd.nextGaussian(), z = (float) rnd.nextGaussian();
        float n = (float) Math.sqrt(x * x + y * y + z * z);
        if (n < 1e-6f) return new float[]{0.5f, 0.2f, 0.1f};
        float r = uni(rnd, 0.3f, 1.1f);
        return new float[]{x / n * r, y / n * r, z / n * r};
    }

    /** Build the node for one structure; parameters are drawn separately. */
    static HybridNode nodeFor(Structure st) {
        List<Step> copy = new ArrayList<>();
        for (Step s : st.steps()) copy.add(s.copy());
        HybridNode n = new HybridNode(copy, st.iterations(), st.bailout(), st.deMode());
        GraphNodeNamer.ensureAllNamed(n);
        return n;
    }

    // ------------------------------------------------------------------
    // Candidates
    // ------------------------------------------------------------------

    record Candidate(int structureIndex, Structure structure, HybridNode node, float[] eye,
                     double score, double detail, double coverage, double solidity, String known, String tag) {
        String chain() { return node.describeChain(); }
    }

    private static final float[] VIEW_DIR = unit(0.42f, 0.30f, -0.85f);

    private static float[] unit(float x, float y, float z) {
        float n = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / n, y / n, z / n};
    }

    /** Point the scene camera at the origin from distance d along the fixed direction. */
    private static float[] aim(NodeGraphParams params, float d) {
        float[] eye = {VIEW_DIR[0] * d, VIEW_DIR[1] * d, VIEW_DIR[2] * d};
        params.getCamera().setPosition(eye[0], eye[1], eye[2]);
        float[] q = CameraUtils.lookAt(eye, new float[]{0, 0, 0});
        params.getCamera().setQuaternion(q[0], q[1], q[2], q[3]);
        params.setFovDegrees(50f);
        return eye;
    }

    /** Fraction of the frame's outer two-pixel ring that is surface: an object cut by the
     *  frame is not framed, whatever its coverage says. */
    static double edgeCoverage(float[] depth, int w, int h) {
        int hit = 0, tot = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            boolean ring = x < 2 || y < 2 || x >= w - 2 || y >= h - 2;
            if (!ring) continue;
            tot++;
            if (depth[y * w + x] > FrameScorer.BACKGROUND) hit++;
        }
        return tot == 0 ? 0 : (double) hit / tot;
    }

    /** Library chains by step signature, for the novelty mark. */
    private static Map<String, String> librarySignatures() {
        Map<String, String> m = new HashMap<>();
        for (HybridPresets.Preset p : HybridPresets.all()) {
            StringBuilder sb = new StringBuilder();
            for (Step s : p.steps()) {
                if (sb.length() > 0) sb.append('>');
                sb.append(s.getType().name());
            }
            m.putIfAbsent(sb.toString(), p.name());
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "out/prospect_hybrid";
        int nStructures = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        int draws = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        String[] res = (args.length > 3 ? args[3] : "320x180").split("x");
        int W = Integer.parseInt(res[0]), H = Integer.parseInt(res[1]);
        int samples = args.length > 4 ? Integer.parseInt(args[4]) : 6;
        int keep = args.length > 5 ? Integer.parseInt(args[5]) : 8;
        long seed = args.length > 6 ? Long.parseLong(args[6]) : 1L;
        new File(outDir).mkdirs();
        Random rnd = new Random(seed);
        Map<String, String> library = librarySignatures();

        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        // No loadAllShaders: a node graph compiles its own program on the first render,
        // the fifteen built-in shaders (two minutes) are not needed here.
        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.setFractalType(FractalType.NODE_GRAPH);
        NodeGraphParams params = (NodeGraphParams) controller.getParams();
        HybridPresets.showcaseLook(params);
        controller.updatePaletteTexture(params.getCustomGradient());

        System.out.printf("=== Hybrid prospector: %d structures x %d draws, %dx%d, %d spp, seed %d ===%n",
                nStructures, draws, W, H, samples, seed);
        int dw = Math.max(32, W / 2), dh = Math.max(18, H / 2);   // depth passes for framing
        List<Candidate> scored = new ArrayList<>();
        int empty = 0, solid = 0, flat = 0;
        long tAll = System.nanoTime(), compileTotal = 0;
        Set<String> seen = new HashSet<>();
        List<Structure> structures = new ArrayList<>();

        for (int si = 0; si < nStructures; si++) {
            Structure st = randomStructure(rnd);
            if (!seen.add(st.signature() + "/" + st.deMode())) { si--; continue; }   // same structure twice: draw another
            structures.add(st);
            HybridNode node = nodeFor(st);
            params.setGraphRoot(node);          // dirty: the next render compiles this structure
            String known = library.get(st.signature());
            System.out.printf("%n[%02d] %-8s %s  (%s, %d it%s)%n", si, st.recipe(), node.describeChain(),
                    st.deMode(), st.iterations(), known != null ? ", library: " + known : "");
            System.out.flush();

            long tc = System.nanoTime();
            boolean compiled = false;
            for (int di = 0; di < draws; di++) {
                for (Step s : node.getSteps()) drawParams(rnd, s);
                if (st.julia()) {
                    float[] c = drawJulia(rnd);
                    node.setJuliaCx(c[0]); node.setJuliaCy(c[1]); node.setJuliaCz(c[2]);
                } else {
                    node.setJuliaCx(0); node.setJuliaCy(0); node.setJuliaCz(0);
                }
                params.updateUniforms();

                // Auto-frame on the depth AOV: closer while the frame is empty, farther while
                // it is filled or the object runs off the edges, closer again while it sits
                // small in the middle. Six tries, then the candidate is what it is. The
                // first run had no edge test and its top four were walls of blocks seen
                // from inside: sixty percent coverage, the object ten times the frame.
                float d = st.deMode() == DEMode.LOG ? 3.2f : (st.deMode() == DEMode.PLANE ? 4.0f : 7.0f);
                double cov = 0, edge = 0;
                float[] eye = null;
                for (int t = 0; t < 6; t++) {
                    eye = aim(params, d);
                    float[] depth = controller.renderDepthAOV(dw, dh);
                    if (!compiled) {
                        compileTotal += System.nanoTime() - tc;
                        System.out.printf("     compiled + first depth in %d ms%n", (System.nanoTime() - tc) / 1_000_000);
                        compiled = true;
                    }
                    cov = FrameScorer.coverage(depth);
                    edge = edgeCoverage(depth, dw, dh);
                    if (cov < 0.04) d *= 0.55f;                          // nothing in view: come closer
                    else if (cov > 0.85 || edge > 0.25) d *= 1.6f;       // filled, or cut by the frame: back off
                    else if (cov < 0.12 && edge < 0.02) d *= 0.7f;       // small and whole: come closer
                    else break;
                }
                String tag = String.format(Locale.ROOT, "s%02d_%02d", si, di);
                if (cov < 0.04) { empty++; continue; }
                if (cov > 0.97) { solid++; continue; }

                BufferedImage img = controller.renderStill(W, H, samples, () -> false);
                float[] depth = controller.renderDepthAOV(W, H);
                FrameScorer.FrameScore fs = FrameScorer.score(img, depth);
                if (fs.detail() < 1.0) { flat++; continue; }
                // Detail alone cannot tell a carved solid from a ball of dust: both are busy.
                // The structure factor (surface whose neighbours are surface, at a continuous
                // depth) is what separates them; the floor keeps a candidate visible in the
                // ranking rather than deleting it on one metric.
                FrameScorer.Structure stc = FrameScorer.structure(depth, W, H);
                double solidity = Math.max(0.05, stc.factor());
                double score = fs.aesthetic() * solidity * (known != null ? 0.5 : 1.0);
                ImageIO.write(img, "png", new File(outDir, tag + ".png"));
                scored.add(new Candidate(si, st, snapshot(node), eye, score, fs.detail(), fs.coverage(), stc.factor(), known, tag));
                System.out.printf(Locale.ROOT, "     %s  score %8.0f  detail %8.0f  cov %3.0f%%  solid %.2f smooth %.2f  edge %3.0f%%  d=%.2f%s%n",
                        tag, score, fs.detail(), 100 * fs.coverage(), stc.solid(), stc.smooth(), 100 * edge, d, st.julia() ? "  julia" : "");
            }
            if (!compiled) System.out.println("     (no draw reached a render)");
            System.out.flush();
        }

        double totalS = (System.nanoTime() - tAll) / 1e9;
        System.out.printf(Locale.ROOT, "%n%d candidates rendered, %d empty, %d solid, %d flat, in %.0f s (%.0f s of it compiling %d structures)%n",
                scored.size(), empty, solid, flat, totalS, compileTotal / 1e9, nStructures);
        // Which recipes pay: structures drawn, candidates that rendered, and the share of
        // the top twelve, so the grammar can be tuned on numbers instead of impressions.
        Map<String, int[]> perRecipe = new java.util.TreeMap<>();
        for (Structure st : structures) perRecipe.computeIfAbsent(st.recipe(), k -> new int[3])[0]++;
        for (Candidate c : scored) perRecipe.computeIfAbsent(c.structure().recipe(), k -> new int[3])[1]++;

        // Rank; at most two per structure so the sheet shows the variety of the space.
        scored.sort(Comparator.comparingDouble(Candidate::score).reversed());
        List<Candidate> diverse = new ArrayList<>();
        Map<Integer, Integer> perStructure = new HashMap<>();
        for (Candidate c : scored) {
            int n = perStructure.getOrDefault(c.structureIndex(), 0);
            if (n >= 2) continue;
            perStructure.put(c.structureIndex(), n + 1);
            diverse.add(c);
        }

        for (int i = 0; i < Math.min(12, diverse.size()); i++) {
            perRecipe.computeIfAbsent(diverse.get(i).structure().recipe(), k -> new int[3])[2]++;
        }
        System.out.printf("%n%-8s %10s %10s %6s%n", "recipe", "structures", "rendered", "top12");
        for (var e : perRecipe.entrySet()) {
            System.out.printf("%-8s %10d %10d %6d%n", e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]);
        }

        try (PrintWriter pw = new PrintWriter(new File(outDir, "ranking.txt"))) {
            pw.println("# rank tag score detail coverage solidity known chain");
            for (int i = 0; i < scored.size(); i++) {
                Candidate c = scored.get(i);
                pw.printf(Locale.ROOT, "%3d %s %10.0f %10.0f %5.2f %5.2f %-16s %s%n", i + 1, c.tag(), c.score(), c.detail(),
                        c.coverage(), c.solidity(), c.known() == null ? "-" : c.known(), c.chain());
            }
        }

        System.out.println();
        System.out.println("top 12 (at most two per structure):");
        for (int i = 0; i < Math.min(12, diverse.size()); i++) {
            Candidate c = diverse.get(i);
            System.out.printf(Locale.ROOT, "%3d %-8s %8.0f  solid %.2f  %s%s%n", i + 1, c.tag(), c.score(), c.solidity(), c.chain(),
                    c.known() != null ? "   [library: " + c.known() + "]" : "");
        }

        // Contact sheet, labelled
        int sheetN = Math.min(12, diverse.size());
        if (sheetN > 0) {
            int cols = 4, rows = (sheetN + cols - 1) / cols;
            BufferedImage sheet = new BufferedImage(W * cols, H * rows, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = sheet.createGraphics();
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(10, H / 14)));
            for (int i = 0; i < sheetN; i++) {
                Candidate c = diverse.get(i);
                BufferedImage im = ImageIO.read(new File(outDir, c.tag() + ".png"));
                int x = (i % cols) * W, y = (i / cols) * H;
                g.drawImage(im, x, y, null);
                String label = String.format(Locale.ROOT, "%d  %s", i + 1, c.chain());
                if (label.length() > 46) label = label.substring(0, 45) + "…";
                g.setColor(new Color(0, 0, 0, 160));
                g.fillRect(x, y + H - g.getFontMetrics().getHeight() - 4, W, g.getFontMetrics().getHeight() + 4);
                g.setColor(c.known() != null ? new Color(255, 200, 120) : Color.WHITE);
                g.drawString(label, x + 4, y + H - 6);
            }
            g.dispose();
            ImageIO.write(sheet, "png", new File(outDir, "_top.png"));
        }

        // Winners as presets: the chain, the showcase look, the camera the search settled on.
        int written = 0;
        for (int i = 0; i < diverse.size() && written < keep; i++) {
            Candidate c = diverse.get(i);
            if (c.known() != null) continue;
            NodeGraphParams p = new NodeGraphParams();
            p.setGraphRoot(c.node());
            HybridPresets.showcaseLook(p);
            p.getCamera().setPosition(c.eye()[0], c.eye()[1], c.eye()[2]);
            float[] q = CameraUtils.lookAt(c.eye(), new float[]{0, 0, 0});
            p.getCamera().setQuaternion(q[0], q[1], q[2], q[3]);
            p.setFovDegrees(50f);
            File f = new File(outDir, String.format(Locale.ROOT, "FOUND_%02d.frac", ++written));
            FractalConfigManager.save(FractalConfig.fromParams(p), f);
        }
        System.out.println();
        System.out.println("contact sheet -> " + new File(outDir, "_top.png").getAbsolutePath());
        System.out.println("presets       -> " + written + " FOUND_NN.frac in " + new File(outDir).getAbsolutePath());
        System.exit(0);
    }

    /** A deep copy of the node as it is now: the search keeps mutating the live one. */
    private static HybridNode snapshot(HybridNode n) {
        List<Step> copy = new ArrayList<>();
        for (Step s : n.getSteps()) copy.add(s.copy());
        HybridNode c = new HybridNode(copy, n.getMaxIterations(), n.getBailout(), n.getDeMode());
        c.setJuliaCx(n.getJuliaCx()); c.setJuliaCy(n.getJuliaCy()); c.setJuliaCz(n.getJuliaCz());
        GraphNodeNamer.ensureAllNamed(c);
        return c;
    }
}
