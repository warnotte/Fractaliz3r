package org.fractalizer.ui;

import javafx.scene.image.Image;
import org.fractalizer.fractals.FractalParams;
import org.fractalizer.fractals.FractalType;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Interface for fractal render controllers.
 * Both OpenCL-based and GLSL-based controllers implement this interface,
 * allowing UI panels to work with either renderer.
 */
public interface RenderController extends AutoCloseable {

    /**
     * Switch to a different fractal type.
     */
    void setFractalType(FractalType type);

    /**
     * Get the current fractal type.
     */
    FractalType getFractalType();

    /**
     * Start a preview render (fast, lower quality).
     */
    void renderPreview(Consumer<Image> onComplete, Consumer<Double> onProgress);

    /**
     * Start a full quality render.
     */
    void renderFull(Consumer<Image> onComplete, Consumer<Double> onProgress,
                    Consumer<Object> onTileComplete);

    /**
     * Export the current render to a PNG file.
     */
    CompletableFuture<Void> exportToPNG(File file, Consumer<Double> onProgress);

    /**
     * Cancel current rendering.
     */
    void cancelRender();

    /**
     * Check if currently rendering.
     */
    boolean isRendering();

    /**
     * Set current fractal parameters.
     */
    void setParams(FractalParams params);

    /**
     * Get current fractal parameters.
     */
    FractalParams getParams();

    /**
     * Set output resolution.
     */
    void setOutputSize(int width, int height);

    /**
     * Get output width.
     */
    int getOutputWidth();

    /**
     * Get output height.
     */
    int getOutputHeight();

    /**
     * Get device name (GPU/renderer name).
     */
    String getDeviceName();

    /**
     * Get device type description.
     */
    String getDeviceType();
}
