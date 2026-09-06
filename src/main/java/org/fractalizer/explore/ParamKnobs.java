package org.fractalizer.explore;

import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.AnimatableParameter;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.FractalNode;
import org.fractalizer.graph.GraphCompiler;
import org.fractalizer.graph.GraphNode;
import org.fractalizer.graph.HybridNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * The numeric parameters of a scene that are worth varying, found by walking the node
 * graph: every fractal leaf contributes its {@code @Animatable} floats (power, Julia
 * constant, radiolaria...), every hybrid leaf its Julia constant and the parameters each
 * of its steps actually reads. Integer counts (iterations) are left out: a variation
 * is a different shape, not a different quality setting.
 */
public final class ParamKnobs {

    /**
     * One parameter: how to read and write it, and the scale of a "noticeable" change,
     * which is what the variation amplitude is a fraction of.
     */
    public record Knob(String name, DoubleSupplier get, DoubleConsumer set, double scale) {
        public double value() { return get.getAsDouble(); }
        /** Whether the default selection includes it: bailouts are not shapes. */
        public boolean interestingByDefault() {
            String n = name.toLowerCase(Locale.ROOT);
            return !n.contains("bailout") && !n.contains("iteration");
        }
    }

    private ParamKnobs() {}

    public static List<Knob> of(AbstractFractalParams params) {
        List<Knob> out = new ArrayList<>();
        if (params instanceof NodeGraphParams ngp && ngp.getGraphRoot() != null) {
            walk(ngp.getGraphRoot(), out, 0);
        } else if (params != null) {
            addAnimatables("", params, out);
        }
        return out;
    }

    private static void walk(GraphNode node, List<Knob> out, int depth) {
        if (node instanceof FractalNode fn && fn.getFractalParams() != null) {
            String prefix = depth == 0 ? "" : label(node) + ".";
            addAnimatables(prefix, fn.getFractalParams(), out);
        } else if (node instanceof HybridNode hn) {
            String prefix = depth == 0 ? "" : label(node) + ".";
            addHybrid(prefix, hn, out);
        }
        for (GraphNode c : node.getChildren()) walk(c, out, depth + 1);
    }

    private static String label(GraphNode node) {
        String n = node.getName();
        return (n == null || n.isBlank()) ? node.getClass().getSimpleName() : n;
    }

    private static void addAnimatables(String prefix, AbstractFractalParams p, List<Knob> out) {
        for (AnimatableParameter ap : p.getAnimatableParameters()) {
            if (ap.valueType() != Float.class && ap.valueType() != Double.class) continue;
            Object v0 = ap.getter().get();
            if (!(v0 instanceof Number n0)) continue;
            out.add(new Knob(prefix + ap.displayName(),
                    () -> ((Number) ap.getter().get()).doubleValue(),
                    v -> ap.setter().accept((float) v),
                    scaleFor(n0.doubleValue())));
        }
    }

    private static void addHybrid(String prefix, HybridNode hn, List<Knob> out) {
        out.add(new Knob(prefix + "Julia Cx", hn::getJuliaCx, v -> hn.setJuliaCx((float) v), 0.5));
        out.add(new Knob(prefix + "Julia Cy", hn::getJuliaCy, v -> hn.setJuliaCy((float) v), 0.5));
        out.add(new Knob(prefix + "Julia Cz", hn::getJuliaCz, v -> hn.setJuliaCz((float) v), 0.5));
        List<HybridNode.Step> steps = hn.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            HybridNode.Step st = steps.get(i);
            String sp = prefix + "step " + (i + 1) + " " + st.getType().getDisplayName() + ": ";
            for (String decl : GraphCompiler.stepUniforms(st.getType())) {
                String u = decl.substring(decl.indexOf('$') + 1);
                switch (u) {
                    case "power" -> out.add(knob(sp + "power", st::getPower, st::setPower));
                    case "scale" -> out.add(knob(sp + "scale", st::getScale, st::setScale));
                    case "foldLimit" -> out.add(knob(sp + "fold limit", st::getFoldLimit, st::setFoldLimit));
                    case "minRadius" -> out.add(knob(sp + "min radius", st::getMinRadius, st::setMinRadius));
                    case "fixedRadius" -> out.add(knob(sp + "fixed radius", st::getFixedRadius, st::setFixedRadius));
                    case "radius" -> out.add(knob(sp + "radius", st::getRadius, st::setRadius));
                    case "dist" -> out.add(knob(sp + "distance", st::getDist, st::setDist));
                    case "offset", "normal" -> {
                        out.add(knob(sp + "offset X", st::getOffsetX, st::setOffsetX));
                        out.add(knob(sp + "offset Y", st::getOffsetY, st::setOffsetY));
                        out.add(knob(sp + "offset Z", st::getOffsetZ, st::setOffsetZ));
                    }
                    case "rot", "rotIter" -> {
                        out.add(new Knob(sp + "rotate X", st::getRotX, v -> st.setRotX((float) v), 20));
                        out.add(new Knob(sp + "rotate Y", st::getRotY, v -> st.setRotY((float) v), 20));
                        out.add(new Knob(sp + "rotate Z", st::getRotZ, v -> st.setRotZ((float) v), 20));
                    }
                    case "twist" -> out.add(new Knob(sp + "twist", st::getRotX, v -> st.setRotX((float) v), 20));
                    default -> { }   // count: an integer symmetry order, not a knob
                }
            }
        }
    }

    private interface FloatGetter { float get(); }
    private interface FloatSetter { void set(float v); }

    private static Knob knob(String name, FloatGetter g, FloatSetter s) {
        return new Knob(name, () -> g.get(), v -> s.set((float) v), scaleFor(g.get()));
    }

    /** A change is noticeable at a fraction of the value; near zero, of half a unit. */
    static double scaleFor(double value) {
        return Math.max(Math.abs(value), 0.5);
    }
}
