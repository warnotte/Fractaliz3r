package org.fractalizer.explore;

import org.fractalizer.explore.ChainProspector.Discovery;
import org.fractalizer.explore.ChainProspector.Result;
import org.fractalizer.explore.ChainProspector.Verdict;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.GraphCompiler;
import org.fractalizer.graph.GraphNodeNamer;
import org.fractalizer.graph.HybridNode;
import org.fractalizer.graph.HybridNode.DEMode;
import org.fractalizer.graph.HybridNode.Family;
import org.fractalizer.graph.HybridNode.Step;
import org.fractalizer.graph.HybridNode.StepType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * Breeding: from one or two parent chains, a generation of children for the eye to choose
 * from. Artificial selection over hybrid chains, the user as the selection pressure.
 *
 * <p>Three kinds of child. A <b>mutant</b> keeps the parent's structure and nudges its
 * parameters (and its Julia seed, and its look): same shader, so it costs milliseconds.
 * A <b>restructured</b> child changes the structure by one move: a shape step swapped for
 * another of its family, a transform put in or taken out, a step's iteration gate toggled,
 * an axis turned; that is a compile (~10 s). A <b>crossover</b>, when there are two parents,
 * is the prefix of one chain followed by the suffix of the other, with the estimator the
 * result asks for and the seed kept when it needs one; a compile too. A generation is
 * mostly mutants with a few structural children, so it arrives in half a minute.
 *
 * <p>Every child is framed, rendered and scored by {@link ChainProspector#evaluate} exactly
 * as a find is, starting the framing from the parent's distance, and handed to the
 * listener as a {@link Discovery} whose {@code recipe} says which kind it is.
 */
public final class ChainBreeder {

    /** A parent: a chain, the look it wears, where its camera was. */
    public record Parent(HybridNode chain, Look look, float[] eye) {
        public static Parent of(Discovery d) {
            return new Parent(ChainProspector.snapshot(d.chain()), d.look(), d.eye().clone());
        }

        /** The scene's chain, when its node graph is one; else null. Its look is the one
         *  the scene wears now, so a library chain can be bred as loaded. */
        public static Parent of(AbstractFractalParams p) {
            if (p instanceof NodeGraphParams ngp && ngp.getGraphRoot() instanceof HybridNode h) {
                return new Parent(ChainProspector.snapshot(h), Look.of(p), p.getCamera().getPosition().clone());
            }
            return null;
        }

        public float distance() {
            return (float) Math.sqrt(eye[0] * eye[0] + eye[1] * eye[1] + eye[2] * eye[2]);
        }

        public String label() { return chain.describeChain(); }
    }

    /** How many children, how many of them structural, and how hard to push. */
    public record Settings(int children, int structural, int samples, long seed, int width, int height, float amplitude) {
        public static Settings defaults() { return new Settings(9, 3, 6, System.nanoTime() & 0xFFFF, 320, 180, 0.25f); }
    }

    private final ChainProspector.ChainRenderer renderer;
    private final ChainProspector.Listener listener;
    private final BooleanSupplier cancelled;
    private final ChainProspector judge;

    public ChainBreeder(ChainProspector.ChainRenderer renderer, ChainProspector.Listener listener, BooleanSupplier cancelled) {
        this.renderer = renderer;
        this.listener = listener;
        this.cancelled = cancelled;
        this.judge = new ChainProspector(renderer, listener, cancelled);
    }

    // ------------------------------------------------------------------
    // Operators
    // ------------------------------------------------------------------

    private static float g(Random rnd) {
        return (float) Math.max(-2.0, Math.min(2.0, rnd.nextGaussian()));
    }

    /** Nudge every parameter the chain's steps read, its seed and its iteration count.
     *  {@code amplitude} 0.25 moves a scale by a quarter at one sigma. Setters clamp. */
    public static void mutateParams(HybridNode node, Random rnd, float amplitude) {
        float a = amplitude;
        for (Step st : node.getSteps()) {
            for (String decl : GraphCompiler.stepUniforms(st.getType())) {
                String name = decl.substring(decl.indexOf('$') + 1);
                switch (name) {
                    case "power" -> st.setPower(st.getPower() + g(rnd) * a * 3f);
                    case "scale" -> st.setScale(st.getScale() * (1f + g(rnd) * a));
                    case "foldLimit" -> st.setFoldLimit(st.getFoldLimit() * (1f + g(rnd) * a));
                    case "minRadius" -> st.setMinRadius(st.getMinRadius() * (1f + g(rnd) * a));
                    case "fixedRadius" -> st.setFixedRadius(st.getFixedRadius() * (1f + g(rnd) * a));
                    case "radius" -> st.setRadius(st.getRadius() * (1f + g(rnd) * a));
                    case "dist" -> st.setDist(st.getDist() + g(rnd) * a * 0.5f);
                    case "count" -> { if (rnd.nextFloat() < a) st.setCount(st.getCount() + (rnd.nextBoolean() ? 1 : -1)); }
                    case "offset", "normal" -> {
                        st.setOffsetX(st.getOffsetX() + g(rnd) * a * 0.5f);
                        st.setOffsetY(st.getOffsetY() + g(rnd) * a * 0.5f);
                        st.setOffsetZ(st.getOffsetZ() + g(rnd) * a * 0.5f);
                    }
                    case "rot" -> {
                        st.setRotX(st.getRotX() + g(rnd) * a * 40f);
                        st.setRotY(st.getRotY() + g(rnd) * a * 40f);
                        st.setRotZ(st.getRotZ() + g(rnd) * a * 40f);
                    }
                    case "rotIter" -> {
                        st.setRotX(st.getRotX() + g(rnd) * a * 15f);
                        st.setRotY(st.getRotY() + g(rnd) * a * 15f);
                        st.setRotZ(st.getRotZ() + g(rnd) * a * 15f);
                    }
                    case "twist" -> st.setRotX(st.getRotX() + g(rnd) * a * 20f);
                    default -> { }
                }
            }
        }
        float cx = node.getJuliaCx(), cy = node.getJuliaCy(), cz = node.getJuliaCz();
        if (cx * cx + cy * cy + cz * cz > 1e-4f) {
            node.setJuliaCx(cx + g(rnd) * a * 0.15f);
            node.setJuliaCy(cy + g(rnd) * a * 0.15f);
            node.setJuliaCz(cz + g(rnd) * a * 0.15f);
        }
        if (rnd.nextFloat() < 0.3f) node.setMaxIterations(node.getMaxIterations() + (rnd.nextBoolean() ? 1 : -1));
    }

    private static boolean isShape(Step s) {
        Family f = s.getType().getFamily();
        return f == Family.POWER || f == Family.FOLD;
    }

    private static StepType randomOfFamily(Random rnd, Family f, StepType not) {
        List<StepType> pool = new ArrayList<>();
        for (StepType t : StepType.values()) if (t.getFamily() == f && t != not) pool.add(t);
        return pool.get(rnd.nextInt(pool.size()));
    }

    private static Step freshStep(Random rnd, StepType t) {
        Step s = new Step(t);
        ChainProspector.drawParams(rnd, s);
        return s;
    }

    private static void clearGate(Step s) {
        Step fresh = new Step(s.getType());
        s.setIterStart(fresh.getIterStart());
        s.setIterEnd(fresh.getIterEnd());
        s.setIterEvery(fresh.getIterEvery());
    }

    /** One structural move on a copy of the chain: swap a shape step within its family,
     *  add or remove a transform, toggle a gate, turn an axis. The shape count is kept, so
     *  a chain with two shape steps stays a hybrid. */
    public static HybridNode mutateStructure(HybridNode parent, Random rnd) {
        List<Step> steps = new ArrayList<>();
        for (Step s : parent.getSteps()) steps.add(s.copy());
        List<Integer> shapes = new ArrayList<>(), transforms = new ArrayList<>(), gateable = new ArrayList<>(), axed = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (isShape(s)) shapes.add(i);
            if (s.getType().getFamily() == Family.TRANSFORM) transforms.add(i);
            if (s.getType() != StepType.ADD_C) gateable.add(i);
            if (s.getType() == StepType.TWIST || s.getType() == StepType.ROTATIONAL_FOLD) axed.add(i);
        }
        List<Integer> moves = new ArrayList<>(List.of(0, 1, 3));   // swap, insert, gate: always possible
        if (!transforms.isEmpty()) moves.add(2);
        if (!axed.isEmpty()) moves.add(4);
        int move = moves.get(rnd.nextInt(moves.size()));
        switch (move) {
            case 0 -> {   // swap a shape step for another of its family
                int i = shapes.get(rnd.nextInt(shapes.size()));
                Step old = steps.get(i);
                Step swapped = freshStep(rnd, randomOfFamily(rnd, old.getType().getFamily(), old.getType()));
                swapped.setIterStart(old.getIterStart()); swapped.setIterEnd(old.getIterEnd()); swapped.setIterEvery(old.getIterEvery());
                steps.set(i, swapped);
            }
            case 1 -> {   // a transform, before the seed if there is one
                int last = steps.size();
                if (last > 0 && steps.get(last - 1).getType() == StepType.ADD_C) last--;
                steps.add(rnd.nextInt(last + 1), freshStep(rnd, randomOfFamily(rnd, Family.TRANSFORM, null)));
            }
            case 2 -> steps.remove((int) transforms.get(rnd.nextInt(transforms.size())));
            case 3 -> {   // toggle a gate
                Step s = steps.get(gateable.get(rnd.nextInt(gateable.size())));
                if (s.isGated()) clearGate(s);
                else if (rnd.nextBoolean()) { s.setIterStart(0); s.setIterEnd(2 + rnd.nextInt(3)); }
                else { s.setIterStart(rnd.nextInt(2)); s.setIterEvery(2); }
            }
            default -> {   // turn an axis
                Step s = steps.get(axed.get(rnd.nextInt(axed.size())));
                s.setAxis((s.getAxis() + 1 + rnd.nextInt(2)) % 3);
            }
        }
        HybridNode child = new HybridNode(steps, parent.getMaxIterations(), parent.getBailout(), parent.getDeMode());
        child.setJuliaCx(parent.getJuliaCx()); child.setJuliaCy(parent.getJuliaCy()); child.setJuliaCz(parent.getJuliaCz());
        GraphNodeNamer.ensureAllNamed(child);
        return child;
    }

    /** The prefix of one chain and the suffix of the other. The estimator follows the
     *  result (a Kleinian fold wants the plane trap, a power map the log estimator, folds
     *  the linear one), an escape-time child keeps exactly one seed at its end, and the
     *  Julia constant comes from the parent that gave the seed. A child that would have a
     *  single shape step is not a hybrid: one structural mutation of the first parent then. */
    public static HybridNode crossover(HybridNode a, HybridNode b, Random rnd) {
        List<Step> sa = a.getSteps(), sb = b.getSteps();
        if (sa.size() < 2 || sb.size() < 2) return mutateStructure(a, rnd);
        int i = 1 + rnd.nextInt(sa.size() - 1), j = 1 + rnd.nextInt(sb.size() - 1);
        List<Step> steps = new ArrayList<>();
        for (int k = 0; k < i; k++) steps.add(sa.get(k).copy());
        for (int k = j; k < sb.size(); k++) steps.add(sb.get(k).copy());
        // one seed at most, and at the end
        boolean hadSeed = steps.removeIf(s -> s.getType() == StepType.ADD_C);
        boolean power = steps.stream().anyMatch(s -> s.getType().getFamily() == Family.POWER);
        boolean kleinian = steps.stream().anyMatch(s -> s.getType() == StepType.KLEINIAN_FOLD);
        if (power || hadSeed) steps.add(new Step(StepType.ADD_C));
        long shapes = steps.stream().filter(ChainBreeder::isShape).count();
        if (shapes < 2) return mutateStructure(a, rnd);
        DEMode de = kleinian ? DEMode.PLANE : (power ? DEMode.LOG : DEMode.LINEAR);
        float bailout = de == DEMode.PLANE ? 1000f : (de == DEMode.LOG ? 4f : 100f);
        HybridNode child = new HybridNode(steps, (a.getMaxIterations() + b.getMaxIterations()) / 2, bailout, de);
        if (steps.get(steps.size() - 1).getType() == StepType.ADD_C) {
            // the constant travels with the seed: the first parent's if it had one, else the second's
            HybridNode seedFrom = hasSeed(sa) ? a : b;
            child.setJuliaCx(seedFrom.getJuliaCx()); child.setJuliaCy(seedFrom.getJuliaCy()); child.setJuliaCz(seedFrom.getJuliaCz());
        }
        GraphNodeNamer.ensureAllNamed(child);
        return child;
    }

    private static boolean hasSeed(List<Step> steps) {
        return steps.stream().anyMatch(s -> s.getType() == StepType.ADD_C);
    }

    /** Put a working copy's parameters back to the parent's, structure untouched. */
    private static void resetTo(HybridNode work, HybridNode parent) {
        work.getSteps().clear();
        for (Step s : parent.getSteps()) work.getSteps().add(s.copy());
        work.setJuliaCx(parent.getJuliaCx()); work.setJuliaCy(parent.getJuliaCy()); work.setJuliaCz(parent.getJuliaCz());
        work.setMaxIterations(parent.getMaxIterations());
    }

    // ------------------------------------------------------------------
    // A generation
    // ------------------------------------------------------------------

    private static final class Tally {
        final List<Discovery> found = new ArrayList<>();
        final Map<String, int[]> perRecipe = new TreeMap<>();
        int empty, solid, flat, cut, done;
    }

    /** One generation from {@code a} and, when given, {@code b}. */
    public Result breed(Parent a, Parent b, Settings s) {
        Random rnd = new Random(s.seed());
        Map<String, String> library = ChainProspector.librarySignatures();
        ChainProspector.Settings judgeSettings = new ChainProspector.Settings(1, 1, s.samples(), s.seed(), s.width(), s.height());
        Tally t = new Tally();
        int paramChildren = Math.max(0, s.children() - s.structural());
        int fromB = b == null ? 0 : paramChildren / 3;
        int fromA = paramChildren - fromB;

        mutants(a, fromA, 0, "mutant", rnd, s, judgeSettings, library, t);
        if (b != null && fromB > 0) mutants(b, fromB, 1, "mutant of the second parent", rnd, s, judgeSettings, library, t);

        for (int k = 0; k < s.structural() && !cancelled.getAsBoolean(); k++) {
            boolean cross = b != null && rnd.nextInt(5) < 3;
            Parent base = (b != null && !cross && rnd.nextBoolean()) ? b : a;
            HybridNode child = cross ? crossover(a.chain(), b.chain(), rnd) : mutateStructure(base.chain(), rnd);
            String recipe = cross ? "crossover" : "restructured";
            Look look = (cross ? (rnd.nextBoolean() ? a.look() : b.look()) : base.look()).mutate(rnd);
            listener.status(t.done / (double) s.children(),
                    String.format(Locale.ROOT, "Child %d/%d: compiling %s", t.done + 1, s.children(), child.describeChain()));
            renderer.setChain(child);
            renderer.applyLook(look);
            judgeOne(child, look, base.distance(), 2 + k, recipe, library, judgeSettings, s, t);
        }
        listener.status(1.0, t.found.size() + " children, " + t.empty + " empty, " + t.solid + " solid, " + t.flat + " flat, " + t.cut + " cut by the frame");
        int structures = 1 + (b != null && fromB > 0 ? 1 : 0) + s.structural();
        return new Result(t.found, structures, t.empty, t.solid, t.flat, t.cut, 0L, t.perRecipe);
    }

    private void mutants(Parent p, int n, int structureIndex, String recipe, Random rnd, Settings s,
                         ChainProspector.Settings judgeSettings, Map<String, String> library, Tally t) {
        if (n <= 0) return;
        HybridNode work = ChainProspector.snapshot(p.chain());
        renderer.setChain(work);
        for (int i = 0; i < n && !cancelled.getAsBoolean(); i++) {
            resetTo(work, p.chain());
            mutateParams(work, rnd, s.amplitude());
            renderer.chainParamsChanged();
            Look look = p.look().mutate(rnd);
            renderer.applyLook(look);
            listener.status(t.done / (double) s.children(),
                    String.format(Locale.ROOT, "Child %d/%d: %s of %s", t.done + 1, s.children(), recipe, p.label()));
            judgeOne(work, look, p.distance(), structureIndex, recipe, library, judgeSettings, s, t);
        }
    }

    private void judgeOne(HybridNode node, Look look, float startDistance, int structureIndex, String recipe,
                          Map<String, String> library, ChainProspector.Settings judgeSettings, Settings s, Tally t) {
        String known = ChainProspector.knownAs(node.getSteps(), library);
        Verdict v = judge.evaluate(structureIndex, recipe, node, look, startDistance, known, judgeSettings);
        t.done++;
        t.perRecipe.computeIfAbsent(recipe, k -> new int[2])[0]++;
        if (!v.kept()) {
            switch (v.rejection()) {
                case "empty" -> t.empty++;
                case "solid" -> t.solid++;
                case "flat" -> t.flat++;
                default -> t.cut++;
            }
            return;
        }
        t.perRecipe.get(recipe)[1]++;
        t.found.add(v.discovery());
        listener.found(v.discovery());
    }
}
