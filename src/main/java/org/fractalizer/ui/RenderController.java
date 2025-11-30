package org.fractalizer.ui;

import javafx.scene.image.Image;
import org.fractalizer.fractals.FractalParams;
import org.fractalizer.fractals.FractalType;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Interface for render controllers.
 * Abstracts the rendering backend (GLSL, OpenCL, etc.)
 */
public interface RenderController {

    void setFractalType(FractalType type);
    FractalType getFractalType();

    void setParams(FractalParams params);
    FractalParams getParams();

    void setOutputSize(int width, int height);
    int getOutputWidth();
    int getOutputHeight();

    void renderPreview(Consumer<Image> onComplete, Consumer<Double> onProgress);
    void renderFull(Consumer<Image> onComplete, Consumer<Double> onProgress, Consumer<Object> onTileComplete);

    CompletableFuture<Void> exportToPNG(File file, Consumer<Double> onProgress);

    void cancelRender();
    boolean isRendering();

    String getDeviceName();
    String getDeviceType();

    void close();
}
