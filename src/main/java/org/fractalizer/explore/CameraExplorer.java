package org.fractalizer.explore;

import org.fractalizer.explore.FrameScorer.FrameScore;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Looks for detailed views of the current scene, starting from where the camera is.
 *
 * This is the {@code test/FractalNavigator} traveller with two changes: it starts from the
 * <em>current</em> view rather than a fractal's default camera, and it keeps several
 * targets instead of diving on the best one only, so the result is a set of framings to
 * choose from rather than one answer.
 *
 * <ol>
 *   <li><b>Pivot.</b> The surface point under the centre of the view. If the centre looks
 *       at nothing, the origin, which every fractal here is built around.</li>
 *   <li><b>Auto-frame.</b> Back off along the pivot-to-eye axis while the view is inside or
 *       overflowing (coverage above 85%, or the centre closer than the depth encoding can
 *       tell), move in while it is sparse (below 25%). The result is the global view.</li>
 *   <li><b>Aim scan.</b> Nine aim points on a 3x3 grid across the view plane; each that hits
 *       a surface gets a quick colour render and a detail score. The most detailed few
 *       become targets — this is what avoids diving into the Menger sponge's hollow core.</li>
 *   <li><b>Dive.</b> For each target, walk the camera toward its surface point in shrinking
 *       steps, scoring every step. The sweet spot is a middle step more often than the
 *       deepest one: too close, a surface goes smooth and dark.</li>
 * </ol>
 *
 * Every step is reported through the {@link Listener} as it is rendered, so a UI can show
 * thumbnails while the search runs, and the whole thing stops at the next render when
 * {@code cancelled} says so.
 */
public final class CameraExplorer {

    /** One framing: where to put the camera, what it looks at, and how it scored. */
    public record Candidate(String label, float[] eye, float[] target, float fov, double camDist,
                            FrameScore score, BufferedImage thumbnail) {
        public double aesthetic() { return score.aesthetic(); }
    }

    public interface Listener {
        void candidate(Candidate c);
        /** Progress in [0, 1] and a one-line description of what is being rendered. */
        void status(double progress, String message);
    }

    /**
     * @param targets aim points kept after the scan (1-5)
     * @param steps   dive steps per target
     * @param shrink  camera-distance factor per step (0.6: each step 40% closer)
     * @param samples samples per colour render; thumbnails need few
     */
    public record Settings(int targets, int steps, float shrink, int samples) {
        public static Settings defaults() { return new Settings(3, 4, 0.6f, 4); }
    }

    private static final float[] ORIGIN = {0, 0, 0};
    private static final float[] WORLD_UP = {0, 1, 0};

    private final ViewRenderer renderer;
    private final Listener listener;
    private final BooleanSupplier cancelled;

    public CameraExplorer(ViewRenderer renderer, Listener listener, BooleanSupplier cancelled) {
        this.renderer = renderer;
        this.listener = listener;
        this.cancelled = cancelled;
    }

    /**
     * @param eye0     current camera position
     * @param forward0 current view direction (unit length not required)
     * @return every candidate rendered, in the order found; sort by {@link Candidate#aesthetic()}
     *         for "best first". Partial when cancelled.
     */
    public List<Candidate> explore(float[] eye0, float[] forward0, float fovDeg, Settings s) {
        List<Candidate> out = new ArrayList<>();
        int total = 1 + 6 + 9 + s.targets() * s.steps();   // upper bound of renders, for progress
        int done = 0;

        // 1) Pivot: what the camera looks at.
        float[] fwd0 = normalize(forward0);
        if (len(fwd0) < 1e-6f) fwd0 = new float[]{0, 0, 1};
        Probe centre = probe(eye0, add(eye0, fwd0), fovDeg);
        float[] pivot;
        if (centre.hit() && !centre.saturated()) pivot = add(eye0, scale(fwd0, (float) centre.depth()));
        else if (centre.saturated())              pivot = add(eye0, scale(fwd0, 0.6f));
        else                                      pivot = ORIGIN;
        listener.status(++done / (double) total, centre.hit() ? "Pivot: surface under the centre" : "Pivot: nothing ahead, using the origin");
        if (cancelled.getAsBoolean()) return out;

        // 2) Auto-frame along the pivot -> eye axis.
        float[] dir = sub(eye0, pivot);
        float R = len(dir);
        if (R < 1e-3f) { dir = scale(fwd0, -1f); R = 0.5f; }
        dir = normalize(dir);
        for (int i = 0; i < 6; i++) {
            if (cancelled.getAsBoolean()) return out;
            Probe p = probe(add(pivot, scale(dir, R)), pivot, fovDeg);
            listener.status(++done / (double) total, String.format(Locale.ROOT, "Auto-frame: distance %.2f, %d%% surface", R, Math.round(p.coverage() * 100)));
            if (p.coverage() > 0.85 || p.saturated()) R *= 1.5f;       // too close / inside
            else if (p.coverage() < 0.25) R *= 0.72f;                  // too far / sparse
            else break;
        }
        float[] eyeG = add(pivot, scale(dir, R));
        Candidate global = render("Global view", eyeG, pivot, fovDeg, R, s.samples());
        if (global == null) return out;
        out.add(global);
        listener.candidate(global);

        // 3) Aim scan: 3x3 grid across the view plane, the most detailed solid patches win.
        float[] fwd = normalize(sub(pivot, eyeG));
        float[] right = normalize(cross(WORLD_UP, fwd));
        if (len(right) < 1e-6f) right = new float[]{1, 0, 0};
        float[] up = cross(fwd, right);
        float extent = (float) (R * Math.tan(Math.toRadians(fovDeg * 0.5)) * 0.5);

        record Aim(float[] target, double depth, double detail) {}
        List<Aim> aims = new ArrayList<>();
        for (int k = -1; k <= 1; k++) for (int m = -1; m <= 1; m++) {
            if (cancelled.getAsBoolean()) return out;
            float[] T = add(pivot, add(scale(right, k * extent), scale(up, m * extent)));
            Probe p = probe(eyeG, T, fovDeg);
            listener.status(++done / (double) total, String.format(Locale.ROOT, "Scanning aim point %d of 9", (k + 1) * 3 + (m + 2)));
            if (!p.hit()) continue;
            BufferedImage img = renderer.colour(eyeG, T, fovDeg, Math.min(s.samples(), 6));
            if (img == null) return out;
            double detail = FrameScorer.score(img, p.depthMap()).detail();
            aims.add(new Aim(T, p.saturated() ? 0.6 : p.depth(), detail));
        }
        aims.sort(Comparator.comparingDouble(Aim::detail).reversed());
        if (aims.isEmpty()) aims.add(new Aim(pivot, R, 0));    // nothing solid: dive at the pivot
        int kept = Math.max(1, Math.min(s.targets(), aims.size()));

        // 4) Dive toward each kept target's surface point.
        for (int j = 0; j < kept; j++) {
            Aim aim = aims.get(j);
            float[] fwdJ = normalize(sub(aim.target(), eyeG));
            float[] S = add(eyeG, scale(fwdJ, (float) aim.depth()));
            double camDist = len(sub(S, eyeG));
            for (int i = 1; i <= s.steps(); i++) {
                if (cancelled.getAsBoolean()) return out;
                camDist *= s.shrink();
                float[] eye = sub(S, scale(fwdJ, (float) camDist));
                listener.status(++done / (double) total, String.format(Locale.ROOT, "Target %d, step %d: distance %.3f", j + 1, i, camDist));
                Candidate c = render(String.format(Locale.ROOT, "Target %d, step %d", j + 1, i), eye, S, fovDeg, camDist, s.samples());
                if (c == null) return out;
                out.add(c);
                listener.candidate(c);
            }
        }
        listener.status(1.0, out.size() + " views scored");
        return out;
    }

    private Candidate render(String label, float[] eye, float[] target, float fovDeg, double camDist, int samples) {
        if (cancelled.getAsBoolean()) return null;
        BufferedImage img = renderer.colour(eye, target, fovDeg, samples);
        if (img == null) return null;
        float[] depth = renderer.depth(eye, target, fovDeg);
        FrameScore fs = FrameScorer.score(img, depth);
        return new Candidate(label, eye, target, fovDeg, camDist, fs, img);
    }

    /** Depth-only look at one camera: centre hit / distance, and surface coverage. */
    record Probe(boolean hit, boolean saturated, double depth, double coverage, float[] depthMap) {}

    private Probe probe(float[] eye, float[] target, float fovDeg) {
        float[] d = renderer.depth(eye, target, fovDeg);
        int w = renderer.width(), h = renderer.height();
        float v = d[(h / 2) * w + w / 2];
        boolean hit = v > FrameScorer.BACKGROUND, sat = v >= ViewRenderer.SATURATED;
        double depth = (hit && !sat) ? ViewRenderer.decode(v) : Double.NaN;
        return new Probe(hit, sat, depth, FrameScorer.coverage(d), d);
    }

    // ---- vectors ---------------------------------------------------------------

    static float[] sub(float[] a, float[] b) { return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static float[] add(float[] a, float[] b) { return new float[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    static float[] scale(float[] a, float s) { return new float[]{a[0] * s, a[1] * s, a[2] * s}; }
    static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
    static float len(float[] a) { return (float) Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); }
    static float[] normalize(float[] a) { float l = len(a); return l < 1e-9f ? a : scale(a, 1f / l); }
}
