package org.fractalizer.explore;

import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.GraphNodeNamer;
import org.fractalizer.graph.HybridNode;
import org.fractalizer.graph.HybridNode.DEMode;
import org.fractalizer.graph.HybridNode.Step;
import org.fractalizer.graph.HybridNode.StepType;
import org.fractalizer.graph.HybridPresets;
import org.fractalizer.test.CameraUtils;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * Autonomous discovery of new fractals in hybrid-chain space.
 *
 * <p>A hybrid chain composes maps inside one iteration loop: 28 step types, each with
 * parameters, an estimator, iteration gating. A random chain of three or four steps is a
 * formula nobody has written down. Most are nothing (the orbit escapes at once, or never:
 * an empty frame or a solid ball), some are a known shape under another name, a few are
 * worlds. The prospector draws chains, renders them, scores them, throws the nothing
 * away, marks the known, and hands the rest to its listener as they are found.
 *
 * <p>What the chain compiler bakes into GLSL and what it leaves as a uniform decides the
 * shape of the search ({@code GraphCompiler.generateHybridGLSL}): the step types, their
 * order, the gating, the estimator and the axes are compiled in, a <b>structure</b>; every
 * numeric parameter and the Julia seed are uniforms, a <b>draw</b>. A structure costs a
 * shader compile (~12 s on the dev machine, once per machine thanks to the driver's shader
 * cache), a draw costs a few milliseconds. So: few structures, many draws each.
 *
 * <p>Each candidate is framed on the depth AOV (closer while the frame is empty, farther
 * while it is filled or cut by the frame, closer again while small and whole), then scored
 * as {@link FrameScorer} scores a framing, times a structure factor
 * ({@link FrameScorer#structure}: surface whose neighbours are surface at a continuous
 * depth) because detail alone rates a ball of dust as high as a carved solid. A candidate
 * whose shape steps are those of a library chain, or that has a single shape step (one
 * power map or one fold is a family with a name however it is turned between passes), is
 * marked known and ranked at half.
 *
 * <p>The search runs on the caller's thread and drives a {@link ChainRenderer}; in the app
 * that is the GPU controller and the caller is a worker thread that pauses the preview
 * loop, as {@link CameraExplorer} is run. {@code test/HybridProspector} is the command
 * line over it, with contact sheets and {@code .frac} output.
 */
public final class ChainProspector {

    /** How much to search and at what size. {@code seed} makes a run reproducible. */
    public record Settings(int structures, int drawsPerStructure, int samples, long seed, int width, int height) {
        public static Settings defaults() { return new Settings(10, 8, 6, System.nanoTime() & 0xFFFF, 320, 180); }
    }

    /** What the search needs from a renderer: a chain to make the scene, a way to say its
     *  uniforms changed, a depth map and a picture from a camera. */
    public interface ChainRenderer {
        /** Make this chain the scene. The next render compiles it. */
        void setChain(HybridNode chain);
        /** The chain's parameters changed; the shader has not. */
        void chainParamsChanged();
        /** Palette, light, sky and material for the next picture. Uniforms and a texture,
         *  never a compile. */
        void applyLook(Look look);
        float[] depth(float[] eye, float[] target, float fovDeg, int w, int h);
        BufferedImage colour(float[] eye, float[] target, float fovDeg, int w, int h, int samples);
    }

    /** One candidate that rendered. {@code known} names the library chain or family it
     *  already is, or is null for a new one. The chain is a private copy; the look is the
     *  one its thumbnail was rendered under and the one a click loads. */
    public record Discovery(int structureIndex, String recipe, HybridNode chain, float[] eye, double score,
                            FrameScorer.FrameScore frame, double solidity, String known, Look look, BufferedImage thumbnail) {
        public String label() { return chain.describeChain(); }
        public boolean isNew() { return known == null; }
    }

    /** Everything a run produced, with the counts that say where the draws went. */
    public record Result(List<Discovery> discoveries, int structures, int empty, int solid, int flat, int cut,
                         long compileNanos, Map<String, int[]> perRecipe) {}

    public interface Listener {
        /** A candidate rendered and scored; called on the search thread, in search order. */
        void found(Discovery d);
        void status(double progress, String message);
    }

    /** What became of one candidate: a discovery, or the reason it was thrown away
     *  ("empty", "solid", "flat", "cut"). */
    public record Verdict(Discovery discovery, String rejection) {
        public boolean kept() { return discovery != null; }
    }

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
            return sb + "/" + deMode;
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
     *  is not a fractal. Every recipe yields at least two shape steps: one power map or one
     *  fold, whatever is put around it, is a family that already has a name. */
    static Structure randomStructure(Random rnd) {
        List<Step> steps = new ArrayList<>();
        int recipe = rnd.nextInt(4);
        String name;
        DEMode de;
        int iterations;
        float bailout;
        boolean julia;
        switch (recipe) {
            case 0 -> {   // escape-time: [transform] power, shaping fold, seed
                name = "power";
                if (rnd.nextInt(3) == 0) steps.add(new Step(pick(rnd, TRANSFORMS)));
                steps.add(new Step(pick(rnd, POWER_STEPS)));
                steps.add(new Step(pick(rnd, SHAPING_FOLDS)));
                steps.add(new Step(StepType.ADD_C));
                de = DEMode.LOG; iterations = 10 + rnd.nextInt(7); bailout = 4f; julia = rnd.nextInt(2) == 0;
            }
            case 1 -> {   // IFS: two or three contracting folds with transforms between, no seed
                name = "ifs";
                int n = 2 + rnd.nextInt(2);
                for (int i = 0; i < n; i++) {
                    steps.add(new Step(pick(rnd, IFS_FOLDS)));
                    if (i < n - 1 && rnd.nextInt(2) == 0) steps.add(new Step(pick(rnd, TRANSFORMS)));
                }
                if (rnd.nextInt(3) == 0) steps.add(new Step(pick(rnd, SHAPING_FOLDS)));
                de = DEMode.LINEAR; iterations = 8 + rnd.nextInt(7); bailout = 100f; julia = false;
            }
            case 2 -> {   // Mandelbox-like: a contracting fold, a second fold, seed
                name = "boxlike";
                steps.add(new Step(pick(rnd, IFS_FOLDS)));
                steps.add(new Step(rnd.nextInt(3) == 0 ? pick(rnd, IFS_FOLDS) : pick(rnd, SHAPING_FOLDS)));
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
        if (rnd.nextInt(8) == 0 && recipe != 0) {
            steps.add(rnd.nextInt(steps.size() + 1), new Step(StepType.KLEINIAN_FOLD));
            de = DEMode.PLANE;
            bailout = 1000f;
        }
        // Axes are baked: draw them now. Gate one step in four structures: the first
        // passes only, or every second pass. Never the seed.
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

    /** A deep copy of a chain as it is now. */
    public static HybridNode snapshot(HybridNode n) {
        List<Step> copy = new ArrayList<>();
        for (Step s : n.getSteps()) copy.add(s.copy());
        HybridNode c = new HybridNode(copy, n.getMaxIterations(), n.getBailout(), n.getDeMode());
        c.setJuliaCx(n.getJuliaCx()); c.setJuliaCy(n.getJuliaCy()); c.setJuliaCz(n.getJuliaCz());
        GraphNodeNamer.ensureAllNamed(c);
        return c;
    }

    // ------------------------------------------------------------------
    // Novelty
    // ------------------------------------------------------------------

    /** The steps that make a shape: power maps and folds, in order. Transforms, gating
     *  and the seed are left out: a rotated Mandelbox is still a Mandelbox. */
    public static String canonical(List<Step> steps) {
        StringBuilder sb = new StringBuilder();
        for (Step s : steps) {
            HybridNode.Family f = s.getType().getFamily();
            if (f != HybridNode.Family.POWER && f != HybridNode.Family.FOLD) continue;
            if (sb.length() > 0) sb.append('>');
            sb.append(s.getType().name());
        }
        return sb.toString();
    }

    /** Library chains by canonical signature, for the novelty mark. */
    public static Map<String, String> librarySignatures() {
        Map<String, String> m = new HashMap<>();
        for (HybridPresets.Preset p : HybridPresets.all()) m.putIfAbsent(canonical(p.steps()), p.name());
        return m;
    }

    /**
     * What a chain is already known as, or null when it is new. Known: its shape steps are
     * those of a library chain (whatever transforms, gating or seed differ), or there is
     * only one of them (one power map or one fold is a family with a name, a Mandelbulb or
     * a Mandelbox, however it is turned or scaled between passes).
     */
    public static String knownAs(List<Step> steps, Map<String, String> library) {
        String canon = canonical(steps);
        String lib = library.get(canon);
        if (lib != null) return lib;
        int shapes = canon.isEmpty() ? 0 : canon.split(">").length;
        if (shapes <= 1) {
            for (Step s : steps) {
                HybridNode.Family f = s.getType().getFamily();
                if (f == HybridNode.Family.POWER || f == HybridNode.Family.FOLD) return "family: " + s.getType().getDisplayName();
            }
            return "no shape";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Framing
    // ------------------------------------------------------------------

    static final float[] VIEW_DIR = unit(0.42f, 0.30f, -0.85f);
    static final float FOV = 50f;
    private static final float[] ORIGIN = {0, 0, 0};

    private static float[] unit(float x, float y, float z) {
        float n = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / n, y / n, z / n};
    }

    static float[] eyeAt(float d) {
        return new float[]{VIEW_DIR[0] * d, VIEW_DIR[1] * d, VIEW_DIR[2] * d};
    }

    /** Fraction of the frame's outer two-pixel ring that is surface: an object cut by the
     *  frame is not framed, whatever its coverage says. */
    public static double edgeCoverage(float[] depth, int w, int h) {
        int hit = 0, tot = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            boolean ring = x < 2 || y < 2 || x >= w - 2 || y >= h - 2;
            if (!ring) continue;
            tot++;
            if (depth[y * w + x] > FrameScorer.BACKGROUND) hit++;
        }
        return tot == 0 ? 0 : (double) hit / tot;
    }

    /** A fresh scene that is this discovery: the chain as the node graph, the look its
     *  thumbnail was rendered under, the camera the search settled on. What a click in the
     *  browser loads and what the command line writes as a preset. */
    public static NodeGraphParams toParams(Discovery d) {
        NodeGraphParams p = new NodeGraphParams();
        p.setGraphRoot(snapshot(d.chain()));
        d.look().apply(p);
        float[] eye = d.eye();
        p.getCamera().setPosition(eye[0], eye[1], eye[2]);
        float[] q = CameraUtils.lookAt(eye, ORIGIN);
        p.getCamera().setQuaternion(q[0], q[1], q[2], q[3]);
        p.setFovDegrees(FOV);
        return p;
    }

    /** Best first, at most {@code perStructure} from any one structure, so a list shows
     *  the variety of the space rather than the best neighbourhood. */
    public static List<Discovery> diverse(List<Discovery> all, int perStructure) {
        List<Discovery> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingDouble(Discovery::score).reversed());
        List<Discovery> out = new ArrayList<>();
        Map<Integer, Integer> count = new HashMap<>();
        for (Discovery d : sorted) {
            int n = count.getOrDefault(d.structureIndex(), 0);
            if (n >= perStructure) continue;
            count.put(d.structureIndex(), n + 1);
            out.add(d);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // One candidate
    // ------------------------------------------------------------------

    /**
     * Frame, render and score the renderer's current chain (its parameters and look already
     * applied), starting the framing at {@code startDistance} along the fixed view
     * direction. The breeder and the search share this, so a child is judged exactly as a
     * find is. The chain is copied into the discovery; {@code known} is passed through.
     */
    public Verdict evaluate(int structureIndex, String recipe, HybridNode node, Look look, float startDistance,
                            String known, Settings s) {
        int W = s.width(), H = s.height();
        int dw = Math.max(32, W / 2), dh = Math.max(18, H / 2);
        float d = startDistance;
        double cov = 0, edge = 0;
        float[] eye = null;
        for (int t = 0; t < 6; t++) {
            eye = eyeAt(d);
            float[] depth = renderer.depth(eye, ORIGIN, FOV, dw, dh);
            cov = FrameScorer.coverage(depth);
            edge = edgeCoverage(depth, dw, dh);
            if (cov < 0.04) d *= 0.55f;                          // nothing in view: come closer
            else if (cov > 0.85 || edge > 0.25) d *= 1.6f;       // filled, or cut by the frame: back off
            else if (cov < 0.12 && edge < 0.02) d *= 0.7f;       // small and whole: come closer
            else break;
        }
        if (cov < 0.04) return new Verdict(null, "empty");
        if (cov > 0.97) return new Verdict(null, "solid");
        if (edge > 0.5) return new Verdict(null, "cut");     // still running off the frame after six tries: a Kleinian seen from inside

        BufferedImage img = renderer.colour(eye, ORIGIN, FOV, W, H, s.samples());
        float[] depth = renderer.depth(eye, ORIGIN, FOV, W, H);
        FrameScorer.FrameScore fs = FrameScorer.score(img, depth);
        if (fs.detail() < 1.0) return new Verdict(null, "flat");
        FrameScorer.Structure stc = FrameScorer.structure(depth, W, H);
        double solidity = Math.max(0.05, stc.factor());
        double score = fs.aesthetic() * solidity * (known != null ? 0.5 : 1.0);
        return new Verdict(new Discovery(structureIndex, recipe, snapshot(node), eye, score, fs, stc.factor(), known, look, img), null);
    }

    // ------------------------------------------------------------------
    // The search
    // ------------------------------------------------------------------

    private final ChainRenderer renderer;
    private final Listener listener;
    private final BooleanSupplier cancelled;

    public ChainProspector(ChainRenderer renderer, Listener listener, BooleanSupplier cancelled) {
        this.renderer = renderer;
        this.listener = listener;
        this.cancelled = cancelled;
    }

    public Result prospect(Settings s) {
        Random rnd = new Random(s.seed());
        Map<String, String> library = librarySignatures();
        List<Discovery> found = new ArrayList<>();
        Map<String, int[]> perRecipe = new TreeMap<>();
        int empty = 0, solid = 0, flat = 0, cut = 0;
        long compileTotal = 0;
        Set<String> seen = new HashSet<>();

        for (int si = 0; si < s.structures() && !cancelled.getAsBoolean(); si++) {
            Structure st = randomStructure(rnd);
            for (int retry = 0; retry < 20 && !seen.add(st.signature()); retry++) st = randomStructure(rnd);
            perRecipe.computeIfAbsent(st.recipe(), k -> new int[2])[0]++;
            HybridNode node = nodeFor(st);
            String known = knownAs(node.getSteps(), library);
            renderer.setChain(node);
            listener.status(si / (double) s.structures(),
                    String.format(Locale.ROOT, "Structure %d/%d: compiling %s", si + 1, s.structures(), node.describeChain()));

            long tc = System.nanoTime();
            boolean compiled = false;
            for (int di = 0; di < s.drawsPerStructure() && !cancelled.getAsBoolean(); di++) {
                for (Step step : node.getSteps()) drawParams(rnd, step);
                if (st.julia()) {
                    float[] c = drawJulia(rnd);
                    node.setJuliaCx(c[0]); node.setJuliaCy(c[1]); node.setJuliaCz(c[2]);
                } else {
                    node.setJuliaCx(0); node.setJuliaCy(0); node.setJuliaCz(0);
                }
                renderer.chainParamsChanged();
                // Its own palette, light, sky and material, from the same stream as its
                // parameters: every candidate wears something else, and the tile shows what
                // the click will load.
                Look look = Look.draw(rnd);
                renderer.applyLook(look);

                float d0 = st.deMode() == DEMode.LOG ? 3.2f : (st.deMode() == DEMode.PLANE ? 4.0f : 7.0f);
                long te = System.nanoTime();
                Verdict v = evaluate(si, st.recipe(), node, look, d0, known, s);
                if (!compiled) {
                    compileTotal += System.nanoTime() - tc;
                    compiled = true;
                }
                if (!v.kept()) {
                    switch (v.rejection()) {
                        case "empty" -> empty++;
                        case "solid" -> solid++;
                        case "flat" -> flat++;
                        default -> cut++;
                    }
                    continue;
                }
                Discovery disc = v.discovery();
                found.add(disc);
                perRecipe.get(st.recipe())[1]++;
                listener.found(disc);
                listener.status((si + (di + 1) / (double) s.drawsPerStructure()) / s.structures(),
                        String.format(Locale.ROOT, "Structure %d/%d, draw %d/%d: %d found", si + 1, s.structures(),
                                di + 1, s.drawsPerStructure(), found.size()));
            }
        }
        listener.status(1.0, found.size() + " candidates, " + empty + " empty, " + solid + " solid, " + flat + " flat, " + cut + " cut by the frame");
        return new Result(found, perRecipe.values().stream().mapToInt(a -> a[0]).sum(), empty, solid, flat, cut, compileTotal, perRecipe);
    }
}
