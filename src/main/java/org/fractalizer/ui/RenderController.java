package org.fractalizer.ui;

import javafx.scene.image.Image;
import org.fractalizer.fractals.FractalParams;
import org.fractalizer.fractals.FractalType;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Interface for render controllers.
 * Abstracts the rendering backend (GLSL, OpenCL, etc.)
 *
 * Supports two separate sizes:
 * - Viewport size: Used for preview rendering (matches the UI viewport)
 * - Export size: Used for final renders and PNG export (user-specified resolution)
 */
public interface RenderController {

    void setFractalType(FractalType type);
    FractalType getFractalType();

    void setParams(FractalParams params);
    FractalParams getParams();

    // Viewport size (for preview rendering)
    void setViewportSize(int width, int height);
    int getViewportWidth();
    int getViewportHeight();

    // Export size (for final render and export)
    void setExportSize(int width, int height);
    int getExportWidth();
    int getExportHeight();

    // Rendering (preview uses viewport size, export uses export size)
    void renderPreview(Consumer<Image> onComplete, Consumer<Double> onProgress);
    void renderFull(Consumer<Image> onComplete, Consumer<Double> onProgress, Consumer<Object> onTileComplete);

    CompletableFuture<Void> exportToPNG(File file, Consumer<Double> onProgress);
    CompletableFuture<Void> exportToPNG(File file, int samples, Consumer<Double> onProgress);
    CompletableFuture<Void> exportToPNG(File file, int samples, Consumer<Double> onProgress, Supplier<Boolean> cancelCheck);

    /**
     * Export an AOV pass (depth or normals) to a PNG file.
     * @param file       Output PNG file
     * @param renderMode 1 = Normals, 2 = Depth
     */
    void exportAOV(File file, int renderMode);

    void cancelRender();
    boolean isRendering();

    String getDeviceName();
    String getDeviceType();

    void close();
}
