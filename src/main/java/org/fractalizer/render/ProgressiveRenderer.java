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

    // A batch is submitted to the GL thread as one blocking call, and cancellation is only
    // checked between batches — so the batch size is also the worst case latency before
    // navigation can interrupt a full-quality pass. A fixed 8 is harmless on a cheap scene
    // and unusable on an expensive one: at 1080p path-traced a sample costs ~600 ms, so
    // eight of them held the viewport for ~5 s, and ~20 s at 4K. The batch is sized from
    // the measured cost of the previous one instead, aiming at a fixed slice of wall clock.
    private static final long BATCH_TARGET_NS = 120_000_000L;   // 120 ms
    private static final int MAX_BATCH = 8;
    private volatile long nsPerSample = 0;   // 0 = not measured yet

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
        if (scheduler.isShutdown()) {
            return; // Already shut down
        }

        if (rendering.get()) {
            stop();
        }

        targetSamples.set(samples);
        cancelled.set(false);
        rendering.set(true);
        nsPerSample = 0;   // cost is per scene and per resolution; re-measure
        lastImageUpdateNs = 0;

        engine.resetAccumulation();

        // After resetAccumulation(), sampleCount is only cleared on the GL thread
        // (inside the next renderSample). On the first iteration the old count
        // may still be visible, so we skip the completion check once.
        final AtomicBoolean firstIteration = new AtomicBoolean(true);

        // Schedule render loop
        renderTask = scheduler.scheduleAtFixedRate(() -> {
            if (cancelled.get()) {
                return;
            }

            try {
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
                    renderTask.cancel(false);
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
                engine.renderSamples(uniforms, batchSize);
                // GL calls return before the GPU is done, so the batch has to be waited on
                // for the measurement to mean anything. Without the readback below doing it
                // implicitly, that wait has to be explicit.
                engine.glSync();
                long now = System.nanoTime();
                long perSample = Math.max(1L, (now - t0) / batchSize);
                nsPerSample = (nsPerSample == 0) ? perSample : (nsPerSample * 3 + perSample) / 4;

                if (lastImageUpdateNs == 0 || now - lastImageUpdateNs >= IMAGE_INTERVAL_NS) {
                    updateImage();
                    lastImageUpdateNs = System.nanoTime();
                }
                notifyProgress(engine.getSampleCount(), targetSamples.get(), false);

            } catch (Exception e) {
                e.printStackTrace();
                rendering.set(false);
                renderTask.cancel(false);
            }
        }, 0, updateIntervalMs, TimeUnit.MILLISECONDS);
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
