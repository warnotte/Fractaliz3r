package org.fractalizer.render;

import java.util.HashMap;
import java.util.Map;

/**
 * What a pixel costs, per program, learned from every completed step. The scheduler asks
 * it three things: how large a preview fits the step budget, how many whole samples fit
 * it, and how many rows of one sample fit it when a sample alone does not.
 *
 * Two estimates per program: preview shading (path tracing off, fewer steps) and full
 * quality, since they differ by an order of magnitude and a preview measurement says
 * little about the refinement. Every GPU submission also costs a fixed round trip
 * ({@link #STEP_OVERHEAD_NS}, measured ~2.5 ms: 107 strips of a 60 ms sample took
 * 345 ms), so planning aims at the budget minus that, and a strip is not cut below
 * {@link #MIN_STRIP_WORK_NS} unless one row alone is dearer.
 *
 * Used on the GL thread only.
 */
public final class CostModel {

    /** One step of preview work: what a camera move waits for at most. */
    public static final long STEP_BUDGET_NS = 30_000_000L;
    /**
     * One step of refinement work, whole samples or a strip. Every sync between two draws
     * costs the tail of the draw before it, about 5-10 ms on a heavy scene (StripCostProbe
     * on Albedo 0.39 at 1080p: a 190 ms sample as 36 fenced strips took 380 ms, with one
     * or two strips in flight and with or without a flush alike), so the refinement pays
     * that once per slice: 60 ms keeps it near 10 %, 30 ms cost up to 60 %.
     */
    public static final long REFINE_SLICE_NS = 60_000_000L;
    /** Fixed cost of a submission and its fence, whatever its size. */
    public static final long STEP_OVERHEAD_NS = 2_500_000L;
    /** A strip is not made cheaper than this so the overhead stays a fraction of it. */
    public static final long MIN_STRIP_WORK_NS = 20_000_000L;
    /** Whole samples per step at most; beyond this the readback cadence is what matters. */
    public static final int MAX_SAMPLES_PER_STEP = 8;
    /** The refinement's first strip assumes full quality costs this much more than preview. */
    private static final double FULL_OVER_PREVIEW_GUESS = 8.0;
    /** A preview never goes below this fraction of the viewport. */
    public static final float MIN_PREVIEW_SCALE = 0.12f;
    private static final int SCALE_STEPS = 32;

    private static final class Estimate {
        double previewNsPerPixel = 0;   // 0 = unknown
        double fullNsPerPixel = 0;
    }

    private final Map<String, Estimate> byProgram = new HashMap<>();

    /**
     * The key a scene's estimates live under: the program name plus a hash of its source.
     * Every node graph is compiled under the same name ("nodegraph"), so keying by name
     * alone made a heavy scene inherit the estimate of the light one it replaced for its
     * first steps. The hash of a String is cached by the String, and an unchanged scene
     * hands the same String instance to every snapshot.
     */
    public static String keyFor(SceneSnapshot scene) {
        return scene.programKey() + "#" + Integer.toHexString((scene.programDefines() + scene.programSource()).hashCode());
    }

    private Estimate of(String programKey) {
        return byProgram.computeIfAbsent(programKey, k -> new Estimate());
    }

    /** A completed step: {@code pixels} pixels drawn (whole samples of a frame, or one
     *  strip) in {@code gpuNs} of GPU time (a timer query: the wait for earlier work and
     *  the driver round trip are not in it). */
    public void record(String programKey, boolean preview, long pixels, long gpuNs) {
        if (pixels <= 0 || gpuNs <= 0) return;
        double perPixel = (double) gpuNs / pixels;
        Estimate e = of(programKey);
        if (preview) e.previewNsPerPixel = e.previewNsPerPixel == 0 ? perPixel : e.previewNsPerPixel * 0.7 + perPixel * 0.3;
        else e.fullNsPerPixel = e.fullNsPerPixel == 0 ? perPixel : e.fullNsPerPixel * 0.7 + perPixel * 0.3;
    }

    /** Nanoseconds per pixel, or 0 when unknown. */
    public double nsPerPixel(String programKey, boolean preview) {
        Estimate e = byProgram.get(programKey);
        if (e == null) return 0;
        if (preview) return e.previewNsPerPixel;
        if (e.fullNsPerPixel > 0) return e.fullNsPerPixel;
        return e.previewNsPerPixel > 0 ? e.previewNsPerPixel * FULL_OVER_PREVIEW_GUESS : 0;
    }

    /**
     * The preview scale for a viewport: the largest, at most {@code ceiling}, whose one
     * sample fits the budget; {@code ceiling} itself when the cost is unknown. Quantised
     * to 1/32 of the viewport, and kept at {@code current} when the wanted size is within
     * a dead band of it, so consecutive frames share a framebuffer.
     */
    public float previewScale(String programKey, int viewportW, int viewportH, float ceiling, float current) {
        double ns = nsPerPixel(programKey, true);
        ceiling = Math.max(MIN_PREVIEW_SCALE, Math.min(1f, ceiling));
        if (ns <= 0) return quantise(ceiling);
        double pixelsThatFit = (STEP_BUDGET_NS - STEP_OVERHEAD_NS) / ns;
        double wanted = Math.sqrt(pixelsThatFit / ((double) viewportW * viewportH));
        wanted = Math.max(MIN_PREVIEW_SCALE, Math.min(ceiling, wanted));
        if (current > 0 && current <= ceiling && wanted > current * 0.85 && wanted < current * 1.35) return current;
        return quantise((float) wanted);
    }

    private static float quantise(float scale) {
        return Math.max(MIN_PREVIEW_SCALE, Math.round(scale * SCALE_STEPS) / (float) SCALE_STEPS);
    }

    /** Whole samples of a {@code w x h} frame that fit {@code budgetNs}, 1 when even one does not. */
    public int samplesPerStep(String programKey, boolean preview, int w, int h, long budgetNs) {
        double ns = nsPerPixel(programKey, preview);
        if (ns <= 0) return 1;
        double perSample = ns * w * h;
        return (int) Math.max(1, Math.min(MAX_SAMPLES_PER_STEP, (budgetNs - STEP_OVERHEAD_NS) / perSample));
    }

    /** True when one sample of a {@code w x h} frame is dearer than {@code budgetNs}. */
    public boolean sampleExceedsStep(String programKey, boolean preview, int w, int h, long budgetNs) {
        double ns = nsPerPixel(programKey, preview);
        return ns > 0 && ns * w * h > budgetNs - STEP_OVERHEAD_NS;
    }

    /**
     * Rows of a {@code w}-wide sample that fit one step: never fewer than one, never so few
     * that the strip is cheaper than {@link #MIN_STRIP_WORK_NS} unless a row is, and never
     * more than twice {@code previousRows} when there was a previous strip. The cap is what
     * keeps a cheap strip from licensing a huge one: the rows at the top of an image are
     * sky and say nothing about the fractal below, and a first strip of 16 sky rows once
     * sized the next one at the whole remaining frame.
     */
    public int rowsPerStrip(String programKey, boolean preview, int w, int h, int previousRows, long budgetNs) {
        double ns = nsPerPixel(programKey, preview);
        int rows;
        if (ns <= 0) rows = Math.max(1, h / 64);
        else {
            double perRow = ns * w;
            long budget = Math.max(MIN_STRIP_WORK_NS, budgetNs - STEP_OVERHEAD_NS);
            rows = (int) Math.max(1, Math.min(h, budget / perRow));
        }
        if (previousRows > 0) rows = Math.min(rows, 2 * previousRows);
        return rows;
    }
}
