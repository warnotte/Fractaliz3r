package org.fractalizer.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import org.fractalizer.engine.OpenCLEngine;
import org.fractalizer.fractals.*;
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
    private FractalType currentFractalType = FractalType.MANDELBULB;
    private int outputWidth = 1920;
    private int outputHeight = 1080;
    private int previewScale = 4;

    private CompletableFuture<?> currentRender;
    private boolean isRendering = false;

    public FractalizerController() throws IOException {
        this.engine = new OpenCLEngine();

        // Load all fractal kernels
        loadAllKernels();

        this.renderer = new TileRenderer(engine, TileRenderer.DEFAULT_TILE_SIZE);
        this.previewRenderer = new TileRenderer(engine, TileRenderer.PREVIEW_TILE_SIZE);

        // Default to Mandelbulb
        this.currentParams = new MandelbulbParams();
    }

    /**
     * Load all available fractal kernels.
     *
     * Architecture: common.cl provides shared utilities and rendering pipeline helpers:
     * - DoF setup (initDofSetup, getDofSampleRay)
     * - Shading (renderByMode, calculateShading)
     * - Background (renderBackground)
     * - Tone mapping (toneMapAndGamma)
     *
     * Each fractal kernel in /kernels/fractals/ uses these helpers
     * but defines its own DE functions and orbit trap structure.
     */
    private void loadAllKernels() throws IOException {
        // All fractals use the refactored modular architecture
        engine.loadKernelFromSources("mandelbulb", "renderMandelbulb",
            "/kernels/common.cl",
            "/kernels/fractals/mandelbulb.cl"
        );

        engine.loadKernelFromSources("mandelbox", "renderMandelbox",
            "/kernels/common.cl",
            "/kernels/fractals/mandelbox.cl"
        );

        engine.loadKernelFromSources("menger", "renderMenger",
            "/kernels/common.cl",
            "/kernels/fractals/menger.cl"
        );

        engine.loadKernelFromSources("kaleidoscopic", "renderKaleidoscopic",
            "/kernels/common.cl",
            "/kernels/fractals/kaleidoscopic.cl"
        );
    }

    /**
     * Get the kernel name for the current fractal type.
     */
    private String getCurrentKernelName() {
        return currentFractalType.getKernelName();
    }

    /**
     * Switch to a different fractal type.
     */
    public void setFractalType(FractalType type) {
        if (type == currentFractalType) return;

        this.currentFractalType = type;

        // Create new params for the fractal type
        switch (type) {
            case MANDELBULB:
                this.currentParams = new MandelbulbParams();
                break;
            case MANDELBOX:
                this.currentParams = new MandelboxParams();
                break;
            case MENGER_SPONGE:
                this.currentParams = new MengerSpongeParams();
                break;
            case KALEIDOSCOPIC_IFS:
                this.currentParams = new KaleidoscopicIFSParams();
                break;
        }
    }

    /**
     * Get the current fractal type.
     */
    public FractalType getFractalType() {
        return currentFractalType;
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
            getCurrentKernelName(),
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
            getCurrentKernelName(),
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
            getCurrentKernelName(),
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