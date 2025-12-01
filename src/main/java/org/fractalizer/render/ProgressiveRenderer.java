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

        engine.resetAccumulation();

        // Schedule render loop
        renderTask = scheduler.scheduleAtFixedRate(() -> {
            if (cancelled.get()) {
                return;
            }

            try {
                int currentSamples = engine.getSampleCount();

                if (currentSamples >= targetSamples.get()) {
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

                // Render batch of samples
                int batchSize = Math.min(8, targetSamples.get() - currentSamples);
                engine.renderSamples(uniforms, batchSize);

                // Update image and progress
                updateImage();
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
