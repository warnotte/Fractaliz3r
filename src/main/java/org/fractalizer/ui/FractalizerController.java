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

    private OpenCLEngine engine;
    private TileRenderer renderer;
    private TileRenderer previewRenderer;

    private FractalParams currentParams;
    private FractalType currentFractalType = FractalType.MANDELBULB;
    private int outputWidth = 1920;
    private int outputHeight = 1080;
    private int previewScale = 4;

    private CompletableFuture<?> currentRender;
    private boolean isRendering = false;

    public FractalizerController() throws IOException {
        OpenCLEngine.DevicePreference preference = resolveDevicePreference();
        int deviceIndex = resolveDeviceIndex();
        this.engine = new OpenCLEngine(preference, deviceIndex);

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
        if (renderer != null) renderer.cancel();
        if (previewRenderer != null) previewRenderer.cancel();
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

    public String getDeviceType() {
        return engine.getDeviceType();
    }

    public java.util.List<String> getAvailableDevices() {
        return engine.getAvailableDeviceSummaries();
    }

    public java.util.List<OpenCLEngine.DeviceDescriptor> getAvailableDeviceDescriptors() {
        return engine.getAvailableDeviceDescriptors();
    }

    @Override
    public void close() {
        cancelRender();
        if (renderer != null) renderer.shutdown();
        if (previewRenderer != null) previewRenderer.shutdown();
        if (engine != null) engine.close();
    }

    private OpenCLEngine.DevicePreference resolveDevicePreference() {
        String value = System.getProperty("fractalizer.device", System.getenv("FRACTALIZER_DEVICE"));
        if (value == null) {
            return OpenCLEngine.DevicePreference.AUTO;
        }
        return switch (value.trim().toLowerCase()) {
            case "cpu" -> OpenCLEngine.DevicePreference.CPU_ONLY;
            case "gpu" -> OpenCLEngine.DevicePreference.GPU_ONLY;
            default -> OpenCLEngine.DevicePreference.AUTO;
        };
    }

    private int resolveDeviceIndex() {
        String value = System.getProperty("fractalizer.device.index", System.getenv("FRACTALIZER_DEVICE_INDEX"));
        if (value == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Switch the underlying OpenCL device (GPU/CPU) at runtime.
     * Cancels current renders, rebuilds kernels and renderers, and keeps the current fractal params.
     */
    public synchronized void switchDevice(OpenCLEngine.DevicePreference preference, int deviceIndex) throws IOException {
        cancelRender();

        // Keep old references to close after successful switch
        TileRenderer oldRenderer = renderer;
        TileRenderer oldPreview = previewRenderer;
        OpenCLEngine oldEngine = engine;

        // Build new engine/renderers first
        OpenCLEngine newEngine = new OpenCLEngine(preference, deviceIndex);
        TileRenderer newRenderer = new TileRenderer(newEngine, TileRenderer.DEFAULT_TILE_SIZE);
        TileRenderer newPreview = new TileRenderer(newEngine, TileRenderer.PREVIEW_TILE_SIZE);

        // Swap in new engine and reload kernels
        this.engine = newEngine;
        this.renderer = newRenderer;
        this.previewRenderer = newPreview;
        loadAllKernels();

        // Close old resources
        if (oldRenderer != null) oldRenderer.shutdown();
        if (oldPreview != null) oldPreview.shutdown();
        if (oldEngine != null) oldEngine.close();
    }
}
