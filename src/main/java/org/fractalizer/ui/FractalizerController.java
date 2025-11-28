package org.fractalizer.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import org.fractalizer.engine.OpenCLEngine;
import org.fractalizer.fractals.FractalParams;
import org.fractalizer.fractals.MandelbulbParams;
import org.fractalizer.render.ImageExporter;
import org.fractalizer.render.TileRenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Controller for fractal rendering operations.
 * Bridges UI with rendering engine.
 */
public class FractalizerController implements AutoCloseable {

    private final OpenCLEngine engine;
    private final TileRenderer renderer;
    private final TileRenderer previewRenderer;

    private FractalParams currentParams;
    private int outputWidth = 1920;
    private int outputHeight = 1080;
    private int previewScale = 4;

    private CompletableFuture<?> currentRender;
    private boolean isRendering = false;

    public FractalizerController() throws IOException {
        this.engine = new OpenCLEngine();

        // Load the Mandelbulb kernel
        engine.loadKernel("mandelbulb", "/kernels/mandelbulb.cl", "renderMandelbulb");

        this.renderer = new TileRenderer(engine, TileRenderer.DEFAULT_TILE_SIZE);
        this.previewRenderer = new TileRenderer(engine, TileRenderer.PREVIEW_TILE_SIZE);

        // Default to Mandelbulb
        this.currentParams = new MandelbulbParams();
    }

    /**
     * Start a preview render.
     */
    public void renderPreview(Consumer<Image> onComplete, Consumer<Double> onProgress) {
        if (isRendering) {
            cancelRender();
        }

        isRendering = true;

        currentRender = previewRenderer.renderPreview(
            "mandelbulb",
            currentParams,
            outputWidth,
            outputHeight,
            previewScale,
            progress -> Platform.runLater(() -> onProgress.accept(progress))
        ).thenAccept(data -> {
            int previewWidth = outputWidth / previewScale;
            int previewHeight = outputHeight / previewScale;

            BufferedImage preview = ImageExporter.toBufferedImage(data, previewWidth, previewHeight);
            BufferedImage scaled = ImageExporter.scaleUp(preview, outputWidth, outputHeight);
            Image fxImage = SwingFXUtils.toFXImage(scaled, null);

            Platform.runLater(() -> {
                onComplete.accept(fxImage);
                isRendering = false;
            });
        }).exceptionally(e -> {
            e.printStackTrace();
            Platform.runLater(() -> isRendering = false);
            return null;
        });
    }

    /**
     * Start a full quality render.
     */
    public void renderFull(Consumer<Image> onComplete, Consumer<Double> onProgress,
                          Consumer<TileRenderer.TileResult> onTileComplete) {
        if (isRendering) {
            cancelRender();
        }

        isRendering = true;

        currentRender = renderer.renderAsync(
            "mandelbulb",
            currentParams,
            outputWidth,
            outputHeight,
            tile -> {
                if (onTileComplete != null) {
                    Platform.runLater(() -> onTileComplete.accept(tile));
                }
            },
            progress -> Platform.runLater(() -> onProgress.accept(progress))
        ).thenAccept(data -> {
            BufferedImage image = ImageExporter.toBufferedImage(data, outputWidth, outputHeight);
            Image fxImage = SwingFXUtils.toFXImage(image, null);

            Platform.runLater(() -> {
                onComplete.accept(fxImage);
                isRendering = false;
            });
        }).exceptionally(e -> {
            e.printStackTrace();
            Platform.runLater(() -> isRendering = false);
            return null;
        });
    }

    /**
     * Export the current render to a PNG file.
     */
    public CompletableFuture<Void> exportToPNG(File file, Consumer<Double> onProgress) {
        if (isRendering) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Render in progress"));
        }

        isRendering = true;

        return renderer.renderAsync(
            "mandelbulb",
            currentParams,
            outputWidth,
            outputHeight,
            null,
            progress -> Platform.runLater(() -> onProgress.accept(progress))
        ).thenAccept(data -> {
            try {
                ImageExporter.exportToPNG(data, outputWidth, outputHeight, file);
            } catch (IOException e) {
                throw new RuntimeException("Failed to export image", e);
            }
        }).whenComplete((result, error) -> {
            Platform.runLater(() -> isRendering = false);
        });
    }

    /**
     * Cancel current rendering.
     */
    public void cancelRender() {
        renderer.cancel();
        previewRenderer.cancel();
        if (currentRender != null) {
            currentRender.cancel(true);
        }
        isRendering = false;
    }

    public boolean isRendering() {
        return isRendering;
    }

    // Parameter setters
    public void setParams(FractalParams params) {
        this.currentParams = params;
    }

    public FractalParams getParams() {
        return currentParams;
    }

    public void setOutputSize(int width, int height) {
        this.outputWidth = width;
        this.outputHeight = height;
    }

    public int getOutputWidth() {
        return outputWidth;
    }

    public int getOutputHeight() {
        return outputHeight;
    }

    public void setPreviewScale(int scale) {
        this.previewScale = scale;
    }

    public String getDeviceName() {
        return engine.getDeviceName();
    }

    @Override
    public void close() {
        cancelRender();
        renderer.shutdown();
        previewRenderer.shutdown();
        engine.close();
    }
}