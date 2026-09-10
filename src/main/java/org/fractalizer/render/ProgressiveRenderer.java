package org.fractalizer.render;

import javafx.application.Platform;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.fractalizer.engine.GLSLEngine;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Progressive renderer using GLSLEngine.
 *
 * Unlike tile-based rendering, this accumulates samples over time.
 * Each frame is fast, so no watchdog issues.
 * Quality improves progressively.
 */
public class ProgressiveRenderer {

    private final GLSLEngine engine;
    private final ScheduledExecutorService scheduler;

    // Rendering state
    private final AtomicBoolean rendering = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile AtomicBoolean runCancel = new AtomicBoolean(true);   // the current run's flag

    // A batch is submitted to the GL thread as one blocking call, and cancellation is only
    // checked between batches — so the batch size is also the worst case latency before
    // navigation can interrupt a full-quality pass. A fixed 8 is harmless on a cheap scene
    // and unusable on an expensive one: at 1080p path-traced a sample costs ~600 ms, so
    // eight of them held the viewport for ~5 s, and ~20 s at 4K. The batch is sized from
    // the measured cost of the previous one instead, aiming at a fixed slice of wall clock.
    // 30 ms: the batch is also how long a camera move waits before its preview can start,
    // on any scene, since the GL thread runs the batch to completion. 120 ms was a visible
    // hitch at the start of every drag after an idle refinement. The readback is rate
    // limited separately (below), so short batches cost only a glFinish each.
    private static final long BATCH_TARGET_NS = 30_000_000L;    // 30 ms
    private static final int MAX_BATCH = 8;
    // A sample dearer than the batch target is drawn in strips with an abort check between
    // them (GLSLEngine.renderSampleBanded), so a 600 ms sample at full size no longer
    // holds the viewport for 600 ms after the user moves.
    private static final boolean DEBUG = Boolean.getBoolean("fractalizer.debugRender");
    private volatile long nsPerSample = 0;   // 0 = not measured yet
    private volatile long lastMeasuredNsPerSample = 0;   // survives start(): what the last run cost per sample
    // Row cost of the last refinement sample, with the size it was measured at: the strip
    // sizing of the next refinement's first sample starts from it instead of a probe.
    private volatile long hintNsPerRow = 0;
    private volatile int hintW = 0, hintH = 0;

    /** Cost of one sample in the most recent run that measured one, in nanoseconds, or 0.
     *  The controller sizes the next preview from it. */
    public long getLastMeasuredNsPerSample() { return lastMeasuredNsPerSample; }

    // Reading the accumulation buffer back and converting it to a JavaFX Image is a fixed
    // cost per tick, unrelated to how many samples the tick rendered. Sizing batches for
    // responsiveness alone multiplied that cost — thirteen readbacks instead of three, and
    // a 40% longer render. The display refresh is therefore rate-limited separately, so
    // short batches buy interruptibility without buying extra readbacks.
    private static final long IMAGE_INTERVAL_NS = 200_000_000L;  // 200 ms
    private long lastImageUpdateNs = 0;
    private final AtomicInteger targetSamples = new AtomicInteger(100);
    private ScheduledFuture<?> renderTask;

    // Update rate for UI (ms between image updates)
    private int updateIntervalMs = 100;

    // Listeners
    private Consumer<WritableImage> onImageUpdate;
    private Consumer<RenderProgress> onProgressUpdate;
    private Runnable onRenderComplete;

    public ProgressiveRenderer(GLSLEngine engine) {
        this.engine = engine;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ProgressiveRenderer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start progressive rendering with the given uniforms.
     *
     * @param uniforms Shader uniforms (camera, fractal params, etc.)
     * @param samples Target number of samples
     */
    public void start(Map<String, Object> uniforms, int samples) {
        start(uniforms, samples, false);
    }

    /**
     * @param abortableFromStart false: the run always completes its first sample and shows
     *        it, so a stream of requests faster than one sample (a drag on a heavy scene)
     *        still produces images instead of cancelling every sample before it lands.
     *        true (the refinement pass): even the first sample may be abandoned between
     *        strips, since the preview image is already on screen.
     */
    public void start(Map<String, Object> uniforms, int samples, boolean abortableFromStart) {
        if (scheduler.isShutdown()) {
            return; // Already shut down
        }

        if (rendering.get()) {
            stop();
        }

        targetSamples.set(samples);
        cancelled.set(false);
        // Each run gets its own cancel flag. A loop that is still finishing a batch when the
        // next start() comes must not see the shared flag drop back to false and carry on
        // with stale uniforms ahead of the new run (the scheduler is single-threaded).
        final AtomicBoolean myCancel = new AtomicBoolean(false);
        runCancel = myCancel;
        rendering.set(true);
        nsPerSample = 0;   // cost is per scene and per resolution; re-measure
        lastImageUpdateNs = 0;

        engine.resetAccumulation();

        // After resetAccumulation(), sampleCount is only cleared on the GL thread
        // (inside the next renderSample). On the first iteration the old count
        // may still be visible, so we skip the completion check once.
        final AtomicBoolean firstIteration = new AtomicBoolean(true);

        // One task that loops until the target or a cancel. (A fixed-rate schedule left the
        // GPU idle for the rest of each period once batches got short.)
        renderTask = scheduler.schedule(() -> {
            try {
                int samplesThisRun = 0;
                while (!myCancel.get()) {
                    int currentSamples = engine.getSampleCount();

                    // Skip completion check on first iteration — sampleCount may not
                    // have been reset yet on the GL thread after resetAccumulation().
                    if (!firstIteration.getAndSet(false) && currentSamples >= targetSamples.get()) {
                        // Done!
                        rendering.set(false);
                        updateImage();
                        notifyProgress(currentSamples, targetSamples.get(), true);
                        if (onRenderComplete != null) {
                            Platform.runLater(onRenderComplete);
                        }
                        return;
                    }

                    // Size the batch so one tick costs about BATCH_TARGET_NS. The first tick
                    // has nothing to go on and renders a single sample, which is also the
                    // smallest interruptible unit there is.
                    int remaining = Math.max(1, targetSamples.get() - currentSamples);
                    int batchSize = 1;
                    if (nsPerSample > 0) {
                        batchSize = (int) Math.max(1, Math.min(MAX_BATCH, BATCH_TARGET_NS / nsPerSample));
                    }
                    batchSize = Math.min(batchSize, remaining);

                    long t0 = System.nanoTime();
                    boolean abortable = abortableFromStart || samplesThisRun > 0;
                    // Strips cost fences and probes: worth it above two slices per sample
                    // (a 60 ms sample drawn whole is a 60 ms wait, which is fine), and for
                    // the first sample of an abortable run, whose cost is unknown.
                    if (abortable && (nsPerSample > 2 * BATCH_TARGET_NS || nsPerSample == 0)) {
                        // One sample already exceeds the slice: draw it in strips and let a
                        // cancel land between two of them. The very first sample has no
                        // estimate and could be anything, so it gets a few strips too.
                        // Strips sized from the last sample at this size when there is one,
                        // else from a probe the engine draws first.
                        long hint = (hintW == engine.getWidth() && hintH == engine.getHeight()) ? hintNsPerRow : 0L;
                        boolean whole = engine.renderSampleBanded(uniforms, hint, BATCH_TARGET_NS, myCancel::get);
                        if (!whole) return;            // cancelled mid-sample; the buffer will be cleared
                        batchSize = 1;
                    } else {
                        engine.renderSamples(uniforms, batchSize);
                        // GL calls return before the GPU is done, so the batch has to be waited on
                        // for the measurement to mean anything. Without the readback below doing it
                        // implicitly, that wait has to be explicit.
                        engine.glSync();
                    }
                    long now = System.nanoTime();
                    long perSample = Math.max(1L, (now - t0) / batchSize);
                    nsPerSample = (nsPerSample == 0) ? perSample : (nsPerSample * 3 + perSample) / 4;
                    samplesThisRun += batchSize;
                    lastMeasuredNsPerSample = nsPerSample;
                    if (abortableFromStart) {   // a refinement sample at this size: remember its row cost
                        hintNsPerRow = Math.max(1L, perSample / Math.max(1, engine.getHeight()));
                        hintW = engine.getWidth(); hintH = engine.getHeight();
                    }
                    if (DEBUG) System.out.printf("[render] %s batch=%d took %d ms (%d ms/sample) run=%d target=%d cancelled=%s%n",
                            abortable && perSample > BATCH_TARGET_NS ? "banded" : "batch", batchSize,
                            (now - t0) / 1_000_000, perSample / 1_000_000, samplesThisRun, targetSamples.get(), myCancel.get());

                    // The run's first image always goes out, cancelled or not: during a drag
                    // the next request arrives before the first sample is done, and a run
                    // that threw its sample away would leave the viewport frozen (measured:
                    // 2.5 images a second on a heavy scene, 25 with this rule).
                    boolean firstImage = samplesThisRun == batchSize;
                    if (myCancel.get() && !firstImage) return;
                    if (firstImage || now - lastImageUpdateNs >= IMAGE_INTERVAL_NS) {
                        updateImage();
                        lastImageUpdateNs = System.nanoTime();
                    }
                    if (myCancel.get()) return;
                    notifyProgress(engine.getSampleCount(), targetSamples.get(), false);
                }
            } catch (Exception e) {
                e.printStackTrace();
                rendering.set(false);
            }
        }, 0, TimeUnit.MILLISECONDS);
    }

    /**
     * Start continuous rendering (doesn't stop until cancelled).
     */
    public void startContinuous(Map<String, Object> uniforms) {
        start(uniforms, Integer.MAX_VALUE);
    }

    /**
     * Stop rendering.
     */
    public void stop() {
        cancelled.set(true);
        runCancel.set(true);
        rendering.set(false);
        if (renderTask != null) {
            renderTask.cancel(false);
        }
    }

    /**
     * Reset and restart with new parameters.
     */
    public void restart(Map<String, Object> uniforms, int samples) {
        stop();
        start(uniforms, samples);
    }

    /**
     * Check if currently rendering.
     */
    public boolean isRendering() {
        return rendering.get();
    }

    /**
     * Get current sample count.
     */
    public int getCurrentSamples() {
        return engine.getSampleCount();
    }

    /**
     * Force a single sample render (useful for preview).
     */
    public void renderSingle(Map<String, Object> uniforms) {
        engine.resetAccumulation();
        engine.renderSample(uniforms);
        updateImage();
    }

    /**
     * Set update interval in milliseconds.
     */
    public void setUpdateInterval(int ms) {
        this.updateIntervalMs = Math.max(16, ms);  // Minimum 60fps
    }

    /**
     * Set image update listener.
     */
    public void setOnImageUpdate(Consumer<WritableImage> listener) {
        this.onImageUpdate = listener;
    }

    /**
     * Set progress update listener.
     */
    public void setOnProgressUpdate(Consumer<RenderProgress> listener) {
        this.onProgressUpdate = listener;
    }

    /**
     * Set render complete listener.
     */
    public void setOnRenderComplete(Runnable listener) {
        this.onRenderComplete = listener;
    }

    /**
     * Get the underlying engine.
     */
    public GLSLEngine getEngine() {
        return engine;
    }

    /**
     * Check if shutdown has been called.
     */
    public boolean isShutdown() {
        return scheduler.isShutdown();
    }

    /**
     * Shutdown the renderer.
     */
    public void shutdown() {
        stop();
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ========================================================================
    // Private helpers
    // ========================================================================

    private void updateImage() {
        if (onImageUpdate == null) {
            return;
        }

        // Get dimensions first, then read pixels atomically
        int width = engine.getWidth();
        int height = engine.getHeight();
        float[] pixels = engine.readImage();

        // Validate that pixel array matches expected size (race condition protection)
        int expectedSize = width * height * 4;
        if (pixels == null || pixels.length != expectedSize) {
            // Size mismatch due to resize - skip this frame
            return;
        }

        // Convert to byte array for JavaFX (BGRA order for getByteBgraInstance)
        byte[] bytePixels = new byte[expectedSize];
        for (int i = 0; i < width * height; i++) {
            int idx = i * 4;
            int r = Math.min(255, Math.max(0, (int) (pixels[idx] * 255)));
            int g = Math.min(255, Math.max(0, (int) (pixels[idx + 1] * 255)));
            int b = Math.min(255, Math.max(0, (int) (pixels[idx + 2] * 255)));
            // BGRA order for JavaFX
            bytePixels[idx] = (byte) b;
            bytePixels[idx + 1] = (byte) g;
            bytePixels[idx + 2] = (byte) r;
            bytePixels[idx + 3] = (byte) 255; // A
        }

        Platform.runLater(() -> {
            WritableImage image = new WritableImage(width, height);
            image.getPixelWriter().setPixels(0, 0, width, height,
                PixelFormat.getByteBgraInstance(), bytePixels, 0, width * 4);
            onImageUpdate.accept(image);
        });
    }

    private void notifyProgress(int current, int target, boolean complete) {
        if (onProgressUpdate == null) return;

        double progress = target > 0 ? (double) current / target : 0;
        RenderProgress rp = new RenderProgress(current, target, progress, complete);

        Platform.runLater(() -> onProgressUpdate.accept(rp));
    }

    /**
     * Progress information.
     */
    public record RenderProgress(
        int currentSamples,
        int targetSamples,
        double progress,
        boolean complete
    ) {}
}
