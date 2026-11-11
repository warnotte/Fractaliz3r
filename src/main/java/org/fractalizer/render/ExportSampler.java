package org.fractalizer.render;

import org.fractalizer.engine.GLSLEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.IntFunction;

/**
 * The samples of an export or an in-memory render, drawn so that the GPU is never handed a
 * long piece of work at once.
 *
 * Windows resets the display driver when the GPU does not answer within two seconds (TDR,
 * nvlddmkm event 153), and an application drawing on the GPU at that moment may not survive
 * it. Exports used to hand the GPU eight whole samples in one task: harmless at a few
 * milliseconds a sample, but a path-traced view in double at depth costs a third of a second
 * a sample at 320x180 and seconds at full size, and PrecisionLab reset the driver nine times
 * in three minutes that way. Here every step is either whole samples that together fit
 * {@link #STEP_NS}, or one strip of rows of a sample that alone does not (the viewport's
 * slicing, GLSLEngine.beginSample / drawRows / commitSample: the same sample to the bit), and
 * every step is waited for before the next is sent. The progress reported is therefore the
 * work done, rows included, not the work submitted.
 *
 * What a sample costs is learned as the render goes:
 * <ul>
 * <li>The first sample is cut into strips that start at 1/64 of the image and double, never
 *     planned past what the dearest row seen so far would make of the step: the rows at the
 *     top are often sky and say nothing about the fractal below.</li>
 * <li>Each strip leaves its cost per row behind, so the next sample is cut where its rows
 *     add up to the step, the heavy band in thin strips and the sky in one.</li>
 * <li>Once a whole sample fits the step, samples go whole, as many per step as fit.</li>
 * <li>A render of the same program at the same size may start from the last one's cost per
 *     sample, but only when that was under a sixteenth of the step, and only for one sample
 *     before measuring again: the uniforms may have changed everything (path tracing on, a
 *     camera gone deep), and a cost sixteen times the step would still be under the two
 *     seconds.</li>
 * </ul>
 * Each step pays a sync, a few milliseconds against 300: ~1-3 %.
 */
public final class ExportSampler {

    /** GPU time one step aims at: a seventh of the driver's two seconds, and long enough
     *  that the wait between steps (2.5 ms plus the tail of the draw before) stays small. */
    public static final long STEP_NS = 300_000_000L;
    /** A previous render's cost per sample is used to start only when it is below this. */
    static final long TRUST_PRIOR_NS = STEP_NS / 16;

    private final GLSLEngine engine;
    private final Map<String, Long> sampleNsByScene = new ConcurrentHashMap<>();

    /** What the last render did, for the probes. */
    public record Stats(int steps, int strips, int samples, long maxStepNs, long totalNs) {}
    private volatile Stats lastStats = new Stats(0, 0, 0, 0, 0);

    public ExportSampler(GLSLEngine engine) { this.engine = engine; }

    public Stats lastStats() { return lastStats; }

    /**
     * Draw {@code count} samples of a {@code w x h} frame into the accumulation with the
     * active program. Call from any thread but the GL thread; each step runs on it.
     *
     * @param finishOnCancel on a cancel inside a sample, finish that sample (the caller keeps
     *                       the partial image) rather than drop it
     * @param onProgress     samples done so far, a fraction while a sample is in strips
     * @return the whole samples drawn
     */
    public int render(Map<String, Object> uniforms, int w, int h, int count, boolean finishOnCancel,
                      DoubleConsumer onProgress, BooleanSupplier cancel) {
        return render(i -> uniforms, w, h, count, finishOnCancel, onProgress, cancel);
    }

    /**
     * {@link #render(Map, int, int, int, boolean, DoubleConsumer, BooleanSupplier)} with the
     * uniforms of each sample asked for before it is drawn, on the calling thread (motion
     * blur: each sample at its own instant of the shutter). One render, so what the first
     * samples cost plans the rest.
     */
    public int render(IntFunction<Map<String, Object>> uniformsFor, int w, int h, int count, boolean finishOnCancel,
                      DoubleConsumer onProgress, BooleanSupplier cancel) {
        Map<String, Object> first = uniformsFor.apply(0);
        String key = engine.activeProgramKey() + "@" + w + "x" + h + "/" + first.get("renderMode") + "/" + first.get("pathTracingEnabled");
        Long prior = sampleNsByScene.get(key);
        long sampleNs = prior != null && prior <= TRUST_PRIOR_NS ? prior : -1;   // a whole sample, -1 unknown
        boolean measured = false;
        double[] rowNs = new double[h];       // cost of each row in the last strip that drew it, -1 unknown
        Arrays.fill(rowNs, -1);
        double worstRowNs = 0;
        long openNs = 0;                      // the strips of the open sample so far
        int done = 0, stripY = -1, lastRows = 0;
        int steps = 0, strips = 0;
        long maxStepNs = 0, totalNs = 0;
        boolean cancelled = false;
        Map<String, Object> next = first;    // the uniforms of sample `done`, asked for once

        while (done < count) {
            if (!cancelled && cancel != null && cancel.getAsBoolean()) cancelled = true;
            if (cancelled) {
                if (stripY < 0) break;
                if (!finishOnCancel) { engine.postAndWait(engine::discardSample); stripY = -1; break; }
            }
            long ns;
            if (stripY < 0 && sampleNs >= 0 && sampleNs <= STEP_NS) {
                int n = measured ? (int) Math.min(count - done, Math.max(1, STEP_NS / Math.max(1, sampleNs))) : 1;
                List<Map<String, Object>> maps = new ArrayList<>(n);
                maps.add(next);
                for (int k = 1; k < n; k++) maps.add(uniformsFor.apply(done + k));
                ns = step(() -> {
                    int k = 0;
                    while (k < n) {   // runs of one map in one call: the pass is bound once per run
                        int run = 1;
                        while (k + run < n && maps.get(k + run) == maps.get(k)) run++;
                        engine.renderSamples(maps.get(k), run);
                        k += run;
                    }
                });
                sampleNs = ns / n;
                measured = true;
                done += n;
                if (done < count) next = uniformsFor.apply(done);
            } else {
                int y = Math.max(0, stripY);
                int rows = rowsFrom(y, h, rowNs, worstRowNs, lastRows);
                boolean open = stripY >= 0, last = y + rows >= h;
                Map<String, Object> u = next;
                ns = step(() -> {
                    if (!open) engine.beginSample(u);
                    engine.drawRows(y, rows);
                    if (last) engine.commitSample();
                });
                double perRow = (double) ns / rows;
                for (int r = y; r < y + rows; r++) rowNs[r] = perRow;
                worstRowNs = Math.max(worstRowNs, perRow);
                lastRows = rows;
                strips++;
                openNs += ns;
                if (last) {
                    stripY = -1;
                    sampleNs = openNs;
                    measured = true;
                    openNs = 0;
                    lastRows = 0;
                    done++;
                    if (done < count) next = uniformsFor.apply(done);
                } else {
                    stripY = y + rows;
                }
            }
            steps++;
            totalNs += ns;
            maxStepNs = Math.max(maxStepNs, ns);
            if (onProgress != null) onProgress.accept(done + (stripY > 0 ? (double) stripY / h : 0));
        }
        if (measured) sampleNsByScene.put(key, sampleNs);
        lastStats = new Stats(steps, strips, done, maxStepNs, totalNs);
        return done;
    }

    /** One step on the GL thread, waited for; its GPU time. */
    private long step(Runnable draw) {
        long[] ns = new long[1];
        engine.postAndWait(() -> {
            GLSLEngine.GpuTimer timer = engine.timer();
            timer.begin();
            draw.run();
            timer.end();
            engine.fence().await();
            ns[0] = Math.max(1, timer.elapsedNs());
        });
        return ns[0];
    }

    /**
     * The rows of the next strip from row {@code y}: as many as the last measured cost of
     * each row lets into the step; where the rows ahead were never measured (the first
     * sample), 1/64 of the image doubling from the last strip, and no more than the dearest
     * row seen so far allows. At least one row.
     */
    static int rowsFrom(int y, int h, double[] rowNs, double worstRowNs, int lastRows) {
        int rows = 0;
        double sum = 0;
        while (y + rows < h && rowNs[y + rows] >= 0 && sum + rowNs[y + rows] <= STEP_NS) {
            sum += rowNs[y + rows];
            rows++;
        }
        if (y + rows < h && rowNs[y + rows] < 0) {
            int grow = lastRows == 0 ? Math.max(1, h / 64) : 2 * lastRows;
            int fit = worstRowNs > 0 ? (int) ((STEP_NS - sum) / worstRowNs) : grow;
            rows += Math.min(grow, fit);
        }
        return Math.max(1, Math.min(rows, h - y));
    }
}
