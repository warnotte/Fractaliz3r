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

    // Rendering. The interactive viewport is driven through the RenderCallback the app hands
    // to the panels; exports use the export size and pause the viewport while they run.
    CompletableFuture<Void> exportToPNG(File file, Consumer<Double> onProgress);
    CompletableFuture<Void> exportToPNG(File file, int samples, Consumer<Double> onProgress);
    CompletableFuture<Void> exportToPNG(File file, int samples, Consumer<Double> onProgress, Supplier<Boolean> cancelCheck);
    void exportAOV(File file, int renderMode);

    /** Stop the viewport while the caller uses the engine directly (a mesh export); nestable. */
    void pauseViewport();
    void resumeViewport();

    // GPU Evaluation for Marching Cubes / Point Cloud
    void prepareGPUEvaluator();
    float[] evaluateGPUSlice(float zPos, float boundsHalf, int resolution);

    String getDeviceName();
    String getDeviceType();

    /**
     * Compile a custom user-written fractal shader.
     * @return null on success, error message on failure
     */
    String compileCustomShader(String source);

    /**
     * Compile a node graph composite shader.
     * @return null on success, error message on failure
     */
    String compileNodeGraph(String source);

    void close();
}
