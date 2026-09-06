package org.fractalizer.explore;

import org.fractalizer.explore.FrameScorer.FrameScore;
import org.fractalizer.explore.ParamKnobs.Knob;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * Variations: the scene's parameters, nudged at random around their current values,
 * rendered from the current camera and scored like any other view. The Julia
 * prospector's idea — render, score, rank — applied to whatever the scene is, from
 * where the user already stands.
 *
 * The first result is always the unchanged scene, so the ranking says whether any
 * variation beats what is there. Every knob is put back after each render and again at
 * the end; choosing a variant is the caller's explicit act.
 */
public final class ParamExplorer {

    public record Variant(String label, double[] values, FrameScore score, BufferedImage thumbnail) {
        public double aesthetic() { return score.aesthetic(); }
    }

    public interface Listener {
        void variant(Variant v);
        void status(double progress, String message);
    }

    private final ViewRenderer renderer;
    private final Listener listener;
    private final BooleanSupplier cancelled;

    public ParamExplorer(ViewRenderer renderer, Listener listener, BooleanSupplier cancelled) {
        this.renderer = renderer;
        this.listener = listener;
        this.cancelled = cancelled;
    }

    /**
     * @param knobs     the parameters to vary (already chosen)
     * @param count     variants to render, besides the unchanged scene
     * @param amplitude fraction of each knob's scale used as the standard deviation of its nudge
     * @return the unchanged scene first, then the variants in the order rendered; partial when cancelled
     */
    public List<Variant> explore(List<Knob> knobs, int count, double amplitude, long seed,
                                 float[] eye, float[] target, float fovDeg, int samples) {
        double[] base = new double[knobs.size()];
        for (int k = 0; k < knobs.size(); k++) base[k] = knobs.get(k).value();
        List<Variant> out = new ArrayList<>();
        Random rng = new Random(seed);
        try {
            listener.status(0, "Current scene");
            Variant current = render("Current", base, eye, target, fovDeg, samples);
            if (current == null) return out;
            out.add(current);
            listener.variant(current);

            for (int i = 1; i <= count; i++) {
                if (cancelled.getAsBoolean()) return out;
                double[] v = new double[base.length];
                for (int k = 0; k < base.length; k++) {
                    double g = Math.max(-3, Math.min(3, rng.nextGaussian()));
                    v[k] = base[k] + g * amplitude * knobs.get(k).scale();
                }
                apply(knobs, v);
                // Setters may clamp; what the scene actually took is what the variant is.
                for (int k = 0; k < v.length; k++) v[k] = knobs.get(k).value();
                listener.status(i / (double) (count + 1), "Variant " + i + " of " + count);
                Variant var = render(describe(knobs, base, v, i), v, eye, target, fovDeg, samples);
                apply(knobs, base);
                if (var == null) return out;
                out.add(var);
                listener.variant(var);
            }
            listener.status(1.0, count + " variations scored");
            return out;
        } finally {
            apply(knobs, base);
        }
    }

    private Variant render(String label, double[] values, float[] eye, float[] target, float fovDeg, int samples) {
        if (cancelled.getAsBoolean()) return null;
        BufferedImage img = renderer.colour(eye, target, fovDeg, samples);
        if (img == null) return null;
        float[] depth = renderer.depth(eye, target, fovDeg);
        return new Variant(label, values.clone(), FrameScorer.score(img, depth), img);
    }

    public static void apply(List<Knob> knobs, double[] values) {
        for (int k = 0; k < knobs.size(); k++) knobs.get(k).set().accept(values[k]);
    }

    /** "Variant 3: power 8.0→9.3, Julia Cx 0.00→0.12" — the two largest relative changes. */
    static String describe(List<Knob> knobs, double[] base, double[] v, int index) {
        Integer[] order = new Integer[knobs.size()];
        for (int k = 0; k < order.length; k++) order[k] = k;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(
                Math.abs(v[b] - base[b]) / knobs.get(b).scale(), Math.abs(v[a] - base[a]) / knobs.get(a).scale()));
        StringBuilder sb = new StringBuilder("Variant " + index);
        int shown = 0;
        for (int k : order) {
            if (shown == 2 || Math.abs(v[k] - base[k]) < 1e-6) break;
            sb.append(shown == 0 ? ": " : ", ");
            sb.append(shortName(knobs.get(k).name())).append(' ')
              .append(String.format(Locale.ROOT, "%.2f→%.2f", base[k], v[k]));
            shown++;
        }
        return sb.toString();
    }

    private static String shortName(String name) {
        int colon = name.lastIndexOf(": ");
        return colon >= 0 ? name.substring(colon + 2) : name;
    }
}
