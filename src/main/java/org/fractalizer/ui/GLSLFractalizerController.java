package org.fractalizer.ui;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.fractalizer.engine.Camera;
import org.fractalizer.engine.GLSLEngine;
import org.fractalizer.fractals.*;
import org.fractalizer.render.ProgressiveRenderer;
import org.fractalizer.util.ImageWriterHelper;

import org.fractalizer.fractals.GradientPalette;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * GLSL-based controller for fractal rendering.
 * Uses progressive rendering with accumulation for high quality output.
 *
 * This controller implements RenderController for UI compatibility
 * and uses GLSLEngine instead of OpenCL for rendering.
 */
public class GLSLFractalizerController implements RenderController {

    private final GLSLEngine engine;
    private final ProgressiveRenderer progressiveRenderer;

    private final Map<FractalType, FractalParams> paramsCache = new HashMap<>();
    private FractalParams currentParams;
    private FractalType currentFractalType = FractalType.MANDELBULB;

    // Viewport size (for preview rendering - matches UI)
    private int viewportWidth = 960;
    private int viewportHeight = 540;

    // Export size (for final render and PNG export)
    private int exportWidth = 1920;
    private int exportHeight = 1080;

    private int previewSamples = 4;
    private int fullSamples = 64;

    // Listeners
    private Consumer<WritableImage> imageListener;
    private Consumer<Double> progressListener;
    private Runnable completeListener;

    public GLSLFractalizerController() throws IOException {
        this.engine = new GLSLEngine(viewportWidth, viewportHeight);
        this.progressiveRenderer = new ProgressiveRenderer(engine);
    }

    /**
     * Load all fractal shaders with progress reporting.
     */
    public void loadAllShaders(java.util.function.BiConsumer<String, Double> progressCallback) {
        FractalType[] types = FractalType.values();
        for (int i = 0; i < types.length; i++) {
            FractalType type = types[i];
            String name = type.getKernelName();
            String path = "/shaders/fractals/" + name + ".glsl";
            
            if (progressCallback != null) {
                progressCallback.accept("Compiling " + type.getDisplayName() + "...", (double) i / types.length);
            }
            
            engine.loadFractalShader(name, path);
        }
        
        if (progressCallback != null) {
            progressCallback.accept("Ready", 1.0);
        }
        
        setFractalType(FractalType.MANDELBULB);
    }

    private void loadAllShaders() {
        loadAllShaders(null);
    }

    /**
     * Switch to a different fractal type.
     */
    @Override
    public void setFractalType(FractalType type) {
        if (type == currentFractalType && currentParams != null) return;

        AbstractFractalParams oldParams = (currentParams instanceof AbstractFractalParams afp) ? afp : null;
        
        this.currentFractalType = type;
        engine.setActiveProgram(type.getKernelName());

        // Try to get from cache
        currentParams = paramsCache.get(type);

        if (currentParams == null) {
            switch (type) {
                case MANDELBULB -> this.currentParams = new MandelbulbParams();
                case MANDELBOX -> this.currentParams = new MandelboxParams();
                case MENGER_SPONGE -> this.currentParams = new MengerSpongeParams();
                case KALEIDOSCOPIC_IFS -> this.currentParams = new KaleidoscopicIFSParams();
                case JULIA_3D -> this.currentParams = new Julia3DParams();
                case POLYHEDRAL_IFS -> this.currentParams = new PolyhedralIFSParams();
                case TEST_SCENE -> this.currentParams = new TestSceneParams();
                case CORNELL_BOX -> this.currentParams = new CornellBoxParams();
            }
            paramsCache.put(type, currentParams);
        }

        // Sync common settings (Path Tracing, Camera, Lighting, Palette) from old state
        if (oldParams != null && currentParams instanceof AbstractFractalParams newParams) {
            oldParams.copyCommonParams(newParams);
        }
    }

    /**
     * Get the current fractal type.
     */
    @Override
    public FractalType getFractalType() {
        return currentFractalType;
    }

    /**
     * Start a preview render (uses viewport size for responsiveness).
     */
    @Override
    public void renderPreview(Consumer<Image> onComplete, Consumer<Double> onProgress) {
        cancelRender();
        engine.resize(viewportWidth, viewportHeight);
        engine.setActiveProgram(currentFractalType.getKernelName());

        Map<String, Object> uniforms = buildUniforms();

        // ProgressiveRenderer already calls Platform.runLater, so callbacks run on FX thread
        progressiveRenderer.setOnImageUpdate(image -> {
            if (onComplete != null) onComplete.accept(image);
        });
        progressiveRenderer.setOnProgressUpdate(p -> {
            if (onProgress != null) onProgress.accept(p.progress());
        });
        progressiveRenderer.setOnRenderComplete(null);

        progressiveRenderer.start(uniforms, previewSamples);
    }

    /**
     * Start a full quality render (uses viewport size for interactive display).
     */
    @Override
    public void renderFull(Consumer<Image> onComplete, Consumer<Double> onProgress,
                           Consumer<Object> onTileComplete) {
        cancelRender();
        engine.resize(viewportWidth, viewportHeight);
        engine.setActiveProgram(currentFractalType.getKernelName());

        Map<String, Object> uniforms = buildUniforms();

        // ProgressiveRenderer already calls Platform.runLater, so callbacks run on FX thread
        progressiveRenderer.setOnImageUpdate(image -> {
            if (onComplete != null) onComplete.accept(image);
        });
        progressiveRenderer.setOnProgressUpdate(p -> {
            if (onProgress != null) onProgress.accept(p.progress());
        });
        progressiveRenderer.setOnRenderComplete(() -> {
            if (completeListener != null) completeListener.run();
        });

        progressiveRenderer.start(uniforms, fullSamples);
    }

    /**
     * Export the current render to a file (PNG or JPG).
     * Automatically injects 360 metadata if projection mode is Equirectangular.
     */
    @Override
    public CompletableFuture<Void> exportToPNG(File file, Consumer<Double> onProgress) {
        return exportToPNG(file, fullSamples * 2, onProgress);
    }

    @Override
    public CompletableFuture<Void> exportToPNG(File file, int samples, Consumer<Double> onProgress) {
        return exportToPNG(file, samples, onProgress, () -> false);
    }

    @Override
    public CompletableFuture<Void> exportToPNG(File file, int samples, Consumer<Double> onProgress, Supplier<Boolean> cancelCheck) {
        if (exportWidth > MAX_TILE_SIZE || exportHeight > MAX_TILE_SIZE) {
            return exportTiledToPNG(file, samples, onProgress, cancelCheck);
        }
        return exportSingleToPNG(file, samples, onProgress, cancelCheck);
    }

    private static final int MAX_TILE_SIZE = 4096;

    private CompletableFuture<Void> exportSingleToPNG(File file, int samples, Consumer<Double> onProgress, Supplier<Boolean> cancelCheck) {
        return CompletableFuture.runAsync(() -> {
            try {
                cancelRender();
                engine.resize(exportWidth, exportHeight);
                engine.setActiveProgram(currentFractalType.getKernelName());
                engine.resetAccumulation();

                Map<String, Object> uniforms = buildUniforms();

                int exportSamples = Math.max(1, samples);
                for (int i = 0; i < exportSamples; i++) {
                    if (cancelCheck.get()) {
                        engine.resize(viewportWidth, viewportHeight);
                        return;
                    }
                    engine.renderSample(uniforms);
                    engine.glSync();
                    if (onProgress != null) {
                        double progress = (double) (i + 1) / exportSamples;
                        Platform.runLater(() -> onProgress.accept(progress));
                    }
                }

                // Cancelled after loop
                if (cancelCheck.get()) {
                    engine.resize(viewportWidth, viewportHeight);
                    return;
                }

                // Read pixels
                float[] pixels = engine.readImage();
                BufferedImage image = new BufferedImage(exportWidth, exportHeight, BufferedImage.TYPE_INT_RGB);

                for (int y = 0; y < exportHeight; y++) {
                    for (int x = 0; x < exportWidth; x++) {
                        int idx = (y * exportWidth + x) * 4;
                        int r = Math.max(0, Math.min(255, (int) (pixels[idx] * 255)));
                        int g = Math.max(0, Math.min(255, (int) (pixels[idx + 1] * 255)));
                        int b = Math.max(0, Math.min(255, (int) (pixels[idx + 2] * 255)));
                        image.setRGB(x, y, (r << 16) | (g << 8) | b);
                    }
                }

                // Check for 360 mode
                boolean is360 = false;
                if (currentParams instanceof AbstractFractalParams afp) {
                    is360 = (afp.getProjectionMode() == AbstractFractalParams.PROJECTION_360_EQUIRECTANGULAR);
                }

                // Use helper to write with optional metadata
                ImageWriterHelper.writeImage(image, file, is360);

            } catch (Exception e) {
                throw new RuntimeException("Failed to export image", e);
            }
        });
    }

    private CompletableFuture<Void> exportTiledToPNG(File file, int samples, Consumer<Double> onProgress, Supplier<Boolean> cancelCheck) {
        return CompletableFuture.runAsync(() -> {
            try {
                cancelRender();

                int fullW = exportWidth;
                int fullH = exportHeight;
                float fullWf = (float) fullW;
                float fullHf = (float) fullH;

                int tilesX = (fullW + MAX_TILE_SIZE - 1) / MAX_TILE_SIZE;
                int tilesY = (fullH + MAX_TILE_SIZE - 1) / MAX_TILE_SIZE;
                int totalTiles = tilesX * tilesY;
                int exportSamples = Math.max(1, samples);

                BufferedImage fullImage = new BufferedImage(fullW, fullH, BufferedImage.TYPE_INT_RGB);

                for (int ty = 0; ty < tilesY; ty++) {
                    for (int tx = 0; tx < tilesX; tx++) {
                        int tileIndex = ty * tilesX + tx;

                        int tileX = tx * MAX_TILE_SIZE;
                        int tileY = ty * MAX_TILE_SIZE;
                        int tileW = Math.min(MAX_TILE_SIZE, fullW - tileX);
                        int tileH = Math.min(MAX_TILE_SIZE, fullH - tileY);

                        engine.resize(tileW, tileH);
                        engine.setActiveProgram(currentFractalType.getKernelName());
                        engine.resetAccumulation();

                        Map<String, Object> uniforms = buildUniforms();
                        uniforms.put("tileOffset", new float[]{tileX / fullWf, (fullH - tileY - tileH) / fullHf});
                        uniforms.put("tileScale", new float[]{tileW / fullWf, tileH / fullHf});
                        uniforms.put("fullResolution", new float[]{fullWf, fullHf});

                        for (int i = 0; i < exportSamples; i++) {
                            if (cancelCheck.get()) {
                                engine.resize(viewportWidth, viewportHeight);
                                return;
                            }
                            engine.renderSample(uniforms);
                            engine.glSync();
                            if (onProgress != null) {
                                double progress = (double) (tileIndex * exportSamples + i + 1) / (totalTiles * exportSamples);
                                Platform.runLater(() -> onProgress.accept(progress));
                            }
                        }

                        if (cancelCheck.get()) {
                            engine.resize(viewportWidth, viewportHeight);
                            return;
                        }

                        // Read tile pixels and copy into full image
                        float[] pixels = engine.readImage();
                        for (int py = 0; py < tileH; py++) {
                            for (int px = 0; px < tileW; px++) {
                                int idx = (py * tileW + px) * 4;
                                int r = Math.max(0, Math.min(255, (int) (pixels[idx] * 255)));
                                int g = Math.max(0, Math.min(255, (int) (pixels[idx + 1] * 255)));
                                int b = Math.max(0, Math.min(255, (int) (pixels[idx + 2] * 255)));
                                fullImage.setRGB(tileX + px, tileY + py, (r << 16) | (g << 8) | b);
                            }
                        }
                    }
                }

                // Check for 360 mode
                boolean is360 = false;
                if (currentParams instanceof AbstractFractalParams afp) {
                    is360 = (afp.getProjectionMode() == AbstractFractalParams.PROJECTION_360_EQUIRECTANGULAR);
                }

                ImageWriterHelper.writeImage(fullImage, file, is360);

            } catch (Exception e) {
                throw new RuntimeException("Failed to export tiled image", e);
            } finally {
                engine.resize(viewportWidth, viewportHeight);
            }
        });
    }

    /**
     * Export a single animation frame to a file (synchronous).
     * Uses viewport size for faster export.
     *
     * @param file The file to save the frame to
     * @param samples Number of samples per frame (lower = faster)
     */
    public void exportAnimationFrame(File file, int samples) {
        exportAnimationFrame(file, viewportWidth, viewportHeight, samples);
    }

    /**
     * Export a single animation frame to a file at a specific resolution (synchronous).
     * Returns the rendered image for visual feedback.
     *
     * @param file The file to save the frame to
     * @param width Export width
     * @param height Export height
     * @param samples Number of samples per frame (lower = faster)
     * @return The rendered image for display
     */
    public WritableImage exportAnimationFrame(File file, int width, int height, int samples) {
        return exportAnimationFrame(file, width, height, samples, null, null);
    }

    /**
     * Export a single animation frame with per-sample progress and cancel support.
     *
     * @param file The file to save the frame to
     * @param width Export width
     * @param height Export height
     * @param samples Number of samples per frame
     * @param onProgress Called after each sample with progress 0.0-1.0
     * @param cancelCheck Checked each sample; if true, stops early and writes partial result
     * @return The rendered image for display
     */
    public WritableImage exportAnimationFrame(File file, int width, int height, int samples,
                                               Consumer<Double> onProgress, Supplier<Boolean> cancelCheck) {
        if (width > MAX_TILE_SIZE || height > MAX_TILE_SIZE) {
            return exportAnimationFrameTiled(file, width, height, samples, onProgress, cancelCheck);
        }
        return exportAnimationFrameSingle(file, width, height, samples, onProgress, cancelCheck);
    }

    private WritableImage exportAnimationFrameSingle(File file, int width, int height, int samples,
                                                      Consumer<Double> onProgress, Supplier<Boolean> cancelCheck) {
        try {
            // Resize engine to export dimensions
            engine.resize(width, height);
            engine.setActiveProgram(currentFractalType.getKernelName());
            engine.resetAccumulation();

            Map<String, Object> uniforms = buildUniforms();

            // Render with specified samples
            for (int i = 0; i < samples; i++) {
                if (cancelCheck != null && cancelCheck.get()) break;
                engine.renderSample(uniforms);
                engine.glSync();
                if (onProgress != null) onProgress.accept((double) (i + 1) / samples);
            }

            // Read pixels
            float[] pixels = engine.readImage();

            // Create BufferedImage for file export
            BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

            // Create WritableImage for display
            WritableImage fxImage = new WritableImage(width, height);
            int[] intPixels = new int[width * height];

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int idx = (y * width + x) * 4;
                    int r = Math.max(0, Math.min(255, (int) (pixels[idx] * 255)));
                    int g = Math.max(0, Math.min(255, (int) (pixels[idx + 1] * 255)));
                    int b = Math.max(0, Math.min(255, (int) (pixels[idx + 2] * 255)));
                    int rgb = (r << 16) | (g << 8) | b;
                    bufferedImage.setRGB(x, y, rgb);
                    intPixels[y * width + x] = 0xFF000000 | rgb; // Add alpha for JavaFX
                }
            }

            // Write to file (detect format from extension)
            boolean is360 = (currentParams instanceof AbstractFractalParams afp) &&
                            (afp.getProjectionMode() == AbstractFractalParams.PROJECTION_360_EQUIRECTANGULAR);
            ImageWriterHelper.writeImage(bufferedImage, file, is360);

            // Write to JavaFX image
            fxImage.getPixelWriter().setPixels(0, 0, width, height,
                PixelFormat.getIntArgbInstance(), intPixels, 0, width);

            // Restore viewport size
            engine.resize(viewportWidth, viewportHeight);

            return fxImage;

        } catch (Exception e) {
            // Restore viewport size on error
            engine.resize(viewportWidth, viewportHeight);
            throw new RuntimeException("Failed to export animation frame", e);
        }
    }

    private WritableImage exportAnimationFrameTiled(File file, int width, int height, int samples,
                                                     Consumer<Double> onProgress, Supplier<Boolean> cancelCheck) {
        try {
            float fullWf = (float) width;
            float fullHf = (float) height;

            int tilesX = (width + MAX_TILE_SIZE - 1) / MAX_TILE_SIZE;
            int tilesY = (height + MAX_TILE_SIZE - 1) / MAX_TILE_SIZE;
            int totalTiles = tilesX * tilesY;

            BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            int[] intPixels = new int[width * height];

            for (int ty = 0; ty < tilesY; ty++) {
                for (int tx = 0; tx < tilesX; tx++) {
                    int tileIndex = ty * tilesX + tx;

                    int tileX = tx * MAX_TILE_SIZE;
                    int tileY = ty * MAX_TILE_SIZE;
                    int tileW = Math.min(MAX_TILE_SIZE, width - tileX);
                    int tileH = Math.min(MAX_TILE_SIZE, height - tileY);

                    engine.resize(tileW, tileH);
                    engine.setActiveProgram(currentFractalType.getKernelName());
                    engine.resetAccumulation();

                    Map<String, Object> uniforms = buildUniforms();
                    uniforms.put("tileOffset", new float[]{tileX / fullWf, (height - tileY - tileH) / fullHf});
                    uniforms.put("tileScale", new float[]{tileW / fullWf, tileH / fullHf});
                    uniforms.put("fullResolution", new float[]{fullWf, fullHf});

                    for (int i = 0; i < samples; i++) {
                        if (cancelCheck != null && cancelCheck.get()) {
                            engine.resize(viewportWidth, viewportHeight);
                            return null;
                        }
                        engine.renderSample(uniforms);
                        engine.glSync();
                        if (onProgress != null) {
                            double progress = (double) (tileIndex * samples + i + 1) / (totalTiles * samples);
                            onProgress.accept(progress);
                        }
                    }

                    // Read tile pixels and copy into full image
                    float[] pixels = engine.readImage();
                    for (int py = 0; py < tileH; py++) {
                        for (int px = 0; px < tileW; px++) {
                            int idx = (py * tileW + px) * 4;
                            int r = Math.max(0, Math.min(255, (int) (pixels[idx] * 255)));
                            int g = Math.max(0, Math.min(255, (int) (pixels[idx + 1] * 255)));
                            int b = Math.max(0, Math.min(255, (int) (pixels[idx + 2] * 255)));
                            int rgb = (r << 16) | (g << 8) | b;
                            bufferedImage.setRGB(tileX + px, tileY + py, rgb);
                            intPixels[(tileY + py) * width + (tileX + px)] = 0xFF000000 | rgb;
                        }
                    }
                }
            }

            // Write to file
            boolean is360 = (currentParams instanceof AbstractFractalParams afp) &&
                            (afp.getProjectionMode() == AbstractFractalParams.PROJECTION_360_EQUIRECTANGULAR);
            ImageWriterHelper.writeImage(bufferedImage, file, is360);

            // Create WritableImage for display
            WritableImage fxImage = new WritableImage(width, height);
            fxImage.getPixelWriter().setPixels(0, 0, width, height,
                PixelFormat.getIntArgbInstance(), intPixels, 0, width);

            engine.resize(viewportWidth, viewportHeight);
            return fxImage;

        } catch (Exception e) {
            engine.resize(viewportWidth, viewportHeight);
            throw new RuntimeException("Failed to export tiled animation frame", e);
        }
    }

    /**
     * Export a single animation frame with motion blur support.
     * Renders samples at different time offsets to simulate camera shutter blur.
     *
     * @param file The file to save the frame to
     * @param width Export width
     * @param height Export height
     * @param samples Number of samples per frame
     * @param frameTime Current frame time in seconds
     * @param fps Frames per second (for shutter duration calculation)
     * @param shutterAngle Shutter angle in degrees (0 = no blur, 180 = half frame, 360 = full frame)
     * @param timeApplier Callback to apply animation parameters at a given time
     * @return The rendered image for display
     */
    public WritableImage exportAnimationFrameWithMotionBlur(
            File file, int width, int height, int samples,
            double frameTime, double fps, float shutterAngle,
            Consumer<Double> timeApplier) {
        return exportAnimationFrameWithMotionBlur(file, width, height, samples,
                frameTime, fps, shutterAngle, timeApplier, null, null);
    }

    /**
     * Export a single animation frame with motion blur, per-sample progress and cancel support.
     */
    public WritableImage exportAnimationFrameWithMotionBlur(
            File file, int width, int height, int samples,
            double frameTime, double fps, float shutterAngle,
            Consumer<Double> timeApplier,
            Consumer<Double> onProgress, Supplier<Boolean> cancelCheck) {
        if (width > MAX_TILE_SIZE || height > MAX_TILE_SIZE) {
            return exportAnimationFrameWithMotionBlurTiled(file, width, height, samples,
                    frameTime, fps, shutterAngle, timeApplier, onProgress, cancelCheck);
        }
        return exportAnimationFrameWithMotionBlurSingle(file, width, height, samples,
                frameTime, fps, shutterAngle, timeApplier, onProgress, cancelCheck);
    }

    private WritableImage exportAnimationFrameWithMotionBlurSingle(
            File file, int width, int height, int samples,
            double frameTime, double fps, float shutterAngle,
            Consumer<Double> timeApplier,
            Consumer<Double> onProgress, Supplier<Boolean> cancelCheck) {
        try {
            // Resize engine to export dimensions
            engine.resize(width, height);
            engine.setActiveProgram(currentFractalType.getKernelName());
            engine.resetAccumulation();

            // Calculate shutter window
            // Shutter angle 180 = shutter open for half the frame duration
            double frameDuration = 1.0 / fps;
            double shutterDuration = frameDuration * (shutterAngle / 360.0);

            Random random = new Random();

            // Render samples with time jittering for motion blur
            for (int i = 0; i < samples; i++) {
                if (cancelCheck != null && cancelCheck.get()) break;

                // Jitter time within shutter window (centered on frame time)
                double jitteredTime;
                if (shutterAngle > 0) {
                    jitteredTime = frameTime + (random.nextDouble() - 0.5) * shutterDuration;
                } else {
                    jitteredTime = frameTime;
                }

                // Apply animation parameters at the jittered time
                timeApplier.accept(jitteredTime);

                // Rebuild uniforms with the new parameters
                Map<String, Object> uniforms = buildUniforms();

                // Render this sample
                engine.renderSample(uniforms);
                engine.glSync();
                if (onProgress != null) onProgress.accept((double) (i + 1) / samples);
            }

            // Read pixels
            float[] pixels = engine.readImage();

            // Create BufferedImage for file export
            BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

            // Create WritableImage for display
            WritableImage fxImage = new WritableImage(width, height);
            int[] intPixels = new int[width * height];

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int idx = (y * width + x) * 4;
                    int r = Math.max(0, Math.min(255, (int) (pixels[idx] * 255)));
                    int g = Math.max(0, Math.min(255, (int) (pixels[idx + 1] * 255)));
                    int b = Math.max(0, Math.min(255, (int) (pixels[idx + 2] * 255)));
                    int rgb = (r << 16) | (g << 8) | b;
                    bufferedImage.setRGB(x, y, rgb);
                    intPixels[y * width + x] = 0xFF000000 | rgb;
                }
            }

            // Write to file
            boolean is360 = (currentParams instanceof AbstractFractalParams afp) &&
                            (afp.getProjectionMode() == AbstractFractalParams.PROJECTION_360_EQUIRECTANGULAR);
            ImageWriterHelper.writeImage(bufferedImage, file, is360);

            // Write to JavaFX image
            fxImage.getPixelWriter().setPixels(0, 0, width, height,
                PixelFormat.getIntArgbInstance(), intPixels, 0, width);

            // Restore viewport size
            engine.resize(viewportWidth, viewportHeight);

            return fxImage;

        } catch (Exception e) {
            engine.resize(viewportWidth, viewportHeight);
            throw new RuntimeException("Failed to export animation frame with motion blur", e);
        }
    }

    private WritableImage exportAnimationFrameWithMotionBlurTiled(
            File file, int width, int height, int samples,
            double frameTime, double fps, float shutterAngle,
            Consumer<Double> timeApplier,
            Consumer<Double> onProgress, Supplier<Boolean> cancelCheck) {
        try {
            float fullWf = (float) width;
            float fullHf = (float) height;

            int tilesX = (width + MAX_TILE_SIZE - 1) / MAX_TILE_SIZE;
            int tilesY = (height + MAX_TILE_SIZE - 1) / MAX_TILE_SIZE;
            int totalTiles = tilesX * tilesY;

            double frameDuration = 1.0 / fps;
            double shutterDuration = frameDuration * (shutterAngle / 360.0);

            BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            int[] intPixels = new int[width * height];

            for (int ty = 0; ty < tilesY; ty++) {
                for (int tx = 0; tx < tilesX; tx++) {
                    int tileIndex = ty * tilesX + tx;

                    int tileX = tx * MAX_TILE_SIZE;
                    int tileY = ty * MAX_TILE_SIZE;
                    int tileW = Math.min(MAX_TILE_SIZE, width - tileX);
                    int tileH = Math.min(MAX_TILE_SIZE, height - tileY);

                    engine.resize(tileW, tileH);
                    engine.setActiveProgram(currentFractalType.getKernelName());
                    engine.resetAccumulation();

                    Random random = new Random(tileIndex); // Consistent per-tile seed

                    for (int i = 0; i < samples; i++) {
                        if (cancelCheck != null && cancelCheck.get()) {
                            engine.resize(viewportWidth, viewportHeight);
                            return null;
                        }

                        double jitteredTime;
                        if (shutterAngle > 0) {
                            jitteredTime = frameTime + (random.nextDouble() - 0.5) * shutterDuration;
                        } else {
                            jitteredTime = frameTime;
                        }

                        timeApplier.accept(jitteredTime);

                        Map<String, Object> uniforms = buildUniforms();
                        uniforms.put("tileOffset", new float[]{tileX / fullWf, (height - tileY - tileH) / fullHf});
                        uniforms.put("tileScale", new float[]{tileW / fullWf, tileH / fullHf});
                        uniforms.put("fullResolution", new float[]{fullWf, fullHf});

                        engine.renderSample(uniforms);
                        engine.glSync();
                        if (onProgress != null) {
                            double progress = (double) (tileIndex * samples + i + 1) / (totalTiles * samples);
                            onProgress.accept(progress);
                        }
                    }

                    // Read tile pixels and copy into full image
                    float[] pixels = engine.readImage();
                    for (int py = 0; py < tileH; py++) {
                        for (int px = 0; px < tileW; px++) {
                            int idx = (py * tileW + px) * 4;
                            int r = Math.max(0, Math.min(255, (int) (pixels[idx] * 255)));
                            int g = Math.max(0, Math.min(255, (int) (pixels[idx + 1] * 255)));
                            int b = Math.max(0, Math.min(255, (int) (pixels[idx + 2] * 255)));
                            int rgb = (r << 16) | (g << 8) | b;
                            bufferedImage.setRGB(tileX + px, tileY + py, rgb);
                            intPixels[(tileY + py) * width + (tileX + px)] = 0xFF000000 | rgb;
                        }
                    }
                }
            }

            // Write to file
            boolean is360 = (currentParams instanceof AbstractFractalParams afp) &&
                            (afp.getProjectionMode() == AbstractFractalParams.PROJECTION_360_EQUIRECTANGULAR);
            ImageWriterHelper.writeImage(bufferedImage, file, is360);

            // Create WritableImage for display
            WritableImage fxImage = new WritableImage(width, height);
            fxImage.getPixelWriter().setPixels(0, 0, width, height,
                PixelFormat.getIntArgbInstance(), intPixels, 0, width);

            engine.resize(viewportWidth, viewportHeight);
            return fxImage;

        } catch (Exception e) {
            engine.resize(viewportWidth, viewportHeight);
            throw new RuntimeException("Failed to export tiled animation frame with motion blur", e);
        }
    }

    /**
     * Cancel current rendering.
     */
    @Override
    public void cancelRender() {
        progressiveRenderer.stop();
    }

    /**
     * Check if currently rendering.
     */
    @Override
    public boolean isRendering() {
        return progressiveRenderer.isRendering();
    }

    // ========================================================================
    // Uniform building from FractalParams
    // ========================================================================

    /**
     * Build uniform map from current parameters.
     */
    private Map<String, Object> buildUniforms() {
        Map<String, Object> uniforms = new HashMap<>();

        if (currentParams instanceof AbstractFractalParams params) {
            // Camera
            Camera camera = params.getCamera();
            float[] pos = camera.getPosition();
            float[] quat = camera.getQuaternion(); // Returns [w, x, y, z]
            uniforms.put("camPos", new float[]{pos[0], pos[1], pos[2]});
            // GLSL expects vec4(x, y, z, w), so reorder from Java's [w, x, y, z]
            uniforms.put("camQuat", new float[]{quat[1], quat[2], quat[3], quat[0]});
            uniforms.put("fov", (float) Math.toDegrees(params.getFov()));
            uniforms.put("projectionMode", params.getProjectionMode());

            // Quality
            uniforms.put("qualityMultiplier", params.getQualityMultiplier());
            uniforms.put("maxRaySteps", params.getMaxRaySteps());
            uniforms.put("baseEpsilon", params.getEpsilon());

            // Lighting
            uniforms.put("lightDir", new float[]{
                params.getLightX(), params.getLightY(), params.getLightZ()
            });
            uniforms.put("lightColor", new float[]{
                params.getLightR(), params.getLightG(), params.getLightB()
            });
            uniforms.put("lightIntensity", params.getLightIntensity());
            uniforms.put("ambientColor", new float[]{
                params.getAmbientR(), params.getAmbientG(), params.getAmbientB()
            });
            uniforms.put("ambientIntensity", params.getAmbientIntensity());

            // Material
            uniforms.put("baseHue", new float[]{
                params.getHueR(), params.getHueG(), params.getHueB()
            });
            uniforms.put("paletteIndex", params.getPaletteIndex());
            uniforms.put("colorStrength", params.getColorStrength());
            uniforms.put("paletteOffset", params.getPaletteOffset());

            // Effects
            uniforms.put("shadowSoftness", params.getShadowSoftness());
            uniforms.put("shadowSteps", params.getShadowSteps());
            uniforms.put("aoSteps", params.getAoSteps());
            uniforms.put("aoIntensity", params.getAoIntensity());
            uniforms.put("glowIntensity", params.getGlowIntensity());
            uniforms.put("specularIntensity", params.getSpecularIntensity());
            uniforms.put("specularPower", params.getSpecularPower());

            // DoF
            uniforms.put("dofEnabled", params.isDofEnabled() ? 1 : 0);
            uniforms.put("focalDistance", params.getFocalDistance());
            uniforms.put("aperture", params.getAperture());
            uniforms.put("dofSamples", params.getDofSamples());

            // Path Tracing
            uniforms.put("pathTracingEnabled", params.isPathTracingEnabled() ? 1 : 0);
            uniforms.put("maxBounces", params.getMaxBounces());
            uniforms.put("roughness", params.getRoughness());
            uniforms.put("skyIntensity", params.getSkyIntensity());
            uniforms.put("indirectMultiplier", params.getIndirectMultiplier());

            // Sky
            uniforms.put("skyType", params.getSkyType());
            uniforms.put("cloudDensity", params.getCloudDensity());
            uniforms.put("skySpeed", params.getSkySpeed());
            uniforms.put("skyTime", params.getSkyTime());
            uniforms.put("skyParallax", params.getSkyParallax());

            // Volumetric Fog
            uniforms.put("volumetricFogEnabled", params.isVolumetricFogEnabled() ? 1 : 0);
            uniforms.put("fogDensity", params.getFogDensity());
            uniforms.put("fogColor", params.getFogColor());
            uniforms.put("fogScattering", params.getFogScattering());
            uniforms.put("fogSteps", params.getFogSteps());

            // Material System
            uniforms.put("materialType", params.getMaterialType());
            uniforms.put("metalness", params.getMetalness());
            uniforms.put("ior", params.getIor());

            // Advanced Effects
            uniforms.put("reflectionIntensity", params.getReflectionIntensity());
            uniforms.put("emissiveIntensity", params.getEmissiveIntensity());
            uniforms.put("sssIntensity", params.getSssIntensity());
            uniforms.put("sssRadius", params.getSssRadius());
            uniforms.put("sssColor", params.getSssColor());

            // Render mode
            uniforms.put("renderMode", params.getRenderMode());
        }

        // Fractal-specific parameters
        switch (currentFractalType) {
            case MANDELBULB -> {
                MandelbulbParams p = (MandelbulbParams) currentParams;
                uniforms.put("power", p.getPower());
                uniforms.put("maxIterations", p.getMaxIterations());
                uniforms.put("bailout", p.getBailout());
            }
            case MANDELBOX -> {
                MandelboxParams p = (MandelboxParams) currentParams;
                uniforms.put("scale", p.getScale());
                uniforms.put("minRadius", p.getMinRadius());
                uniforms.put("fixedRadius", p.getFixedRadius());
                uniforms.put("foldingLimit", p.getFoldingLimit());
                uniforms.put("maxIterations", p.getMaxIterations());
            }
            case MENGER_SPONGE -> {
                MengerSpongeParams p = (MengerSpongeParams) currentParams;
                uniforms.put("maxIterations", p.getMaxIterations());
                uniforms.put("scale", p.getScale());
                uniforms.put("offset", new float[]{
                    p.getOffsetX(), p.getOffsetY(), p.getOffsetZ()
                });
            }
            case KALEIDOSCOPIC_IFS -> {
                KaleidoscopicIFSParams p = (KaleidoscopicIFSParams) currentParams;
                uniforms.put("maxIterations", p.getMaxIterations());
                uniforms.put("scale", p.getScale());
                uniforms.put("foldAngleX", (float) Math.toRadians(p.getFoldAngleX()));
                uniforms.put("foldAngleY", (float) Math.toRadians(p.getFoldAngleY()));
                // ifsOffset is a scalar in the shader (classic KIFS uses offsetX only)
                uniforms.put("ifsOffset", p.getOffsetX());
            }
            case JULIA_3D -> {
                Julia3DParams p = (Julia3DParams) currentParams;
                uniforms.put("maxIterations", p.getMaxIterations());
                uniforms.put("bailout", p.getBailout());
                uniforms.put("juliaC", new float[]{
                    p.getJuliaCx(), p.getJuliaCy(), p.getJuliaCz(), p.getJuliaCw()
                });
            }
            case POLYHEDRAL_IFS -> {
                PolyhedralIFSParams p = (PolyhedralIFSParams) currentParams;
                uniforms.put("polyType", p.getPolyType().ordinal());
                uniforms.put("maxIterations", p.getMaxIterations());
                uniforms.put("scale", p.getScale());
                uniforms.put("offset", new float[]{p.getOffsetX(), p.getOffsetY(), p.getOffsetZ()});
                uniforms.put("shift", new float[]{p.getShiftX(), p.getShiftY(), p.getShiftZ()});

                // Convert Euler angles to 3x3 matrices
                uniforms.put("fractalRotation1", createRotationMatrix(p.getRot1X(), p.getRot1Y(), p.getRot1Z()));
                uniforms.put("fractalRotation2", createRotationMatrix(p.getRot2X(), p.getRot2Y(), p.getRot2Z()));
            }
            case TEST_SCENE -> {
                TestSceneParams p = (TestSceneParams) currentParams;
                uniforms.put("sceneScale", p.getSceneScale());
            }
            case CORNELL_BOX -> {
                CornellBoxParams p = (CornellBoxParams) currentParams;
                uniforms.put("sceneScale", p.getSceneScale());
                uniforms.put("glassSphereX", p.getGlassSphereX());
                uniforms.put("glassSphereY", p.getGlassSphereY());
                uniforms.put("glassSphereZ", p.getGlassSphereZ());
                uniforms.put("glassSphereRadius", p.getGlassSphereRadius());
                uniforms.put("metalSphereX", p.getMetalSphereX());
                uniforms.put("metalSphereY", p.getMetalSphereY());
                uniforms.put("metalSphereZ", p.getMetalSphereZ());
                uniforms.put("metalSphereRadius", p.getMetalSphereRadius());
                uniforms.put("lightPanelX", p.getLightPanelX());
                uniforms.put("lightPanelY", p.getLightPanelY());
                uniforms.put("lightPanelZ", p.getLightPanelZ());
                uniforms.put("lightPanelW", p.getLightPanelW());
                uniforms.put("lightPanelD", p.getLightPanelD());
            }
        }

        // Tiled rendering defaults (identity: full image = single tile)
        uniforms.put("tileOffset", new float[]{0.0f, 0.0f});
        uniforms.put("tileScale", new float[]{1.0f, 1.0f});
        uniforms.put("fullResolution", new float[]{
            (float) engine.getWidth(), (float) engine.getHeight()});

        return uniforms;
    }

    /**
     * Helper to create a 3x3 rotation matrix from Euler angles (XYZ order).
     */
    private float[] createRotationMatrix(float x, float y, float z) {
        float cx = (float) Math.cos(Math.toRadians(x));
        float sx = (float) Math.sin(Math.toRadians(x));
        float cy = (float) Math.cos(Math.toRadians(y));
        float sy = (float) Math.sin(Math.toRadians(y));
        float cz = (float) Math.cos(Math.toRadians(z));
        float sz = (float) Math.sin(Math.toRadians(z));

        // Combined rotation matrix R = Rz * Ry * Rx
        return new float[] {
            cy*cz, -cy*sz, sy,
            sx*sy*cz + cx*sz, -sx*sy*sz + cx*cz, -sx*cy,
            -cx*sy*cz + sx*sz, cx*sy*sz + sx*cz, cx*cy
        };
    }

    // ========================================================================
    // API compatibility with FractalizerController
    // ========================================================================

    @Override
    public void setParams(FractalParams params) {
        this.currentParams = params;
        if (params instanceof AbstractFractalParams afp) {
            setFractalType(afp.getType());
        }
    }

    @Override
    public FractalParams getParams() {
        return currentParams;
    }

    @Override
    public void setViewportSize(int width, int height) {
        this.viewportWidth = Math.max(1, width);
        this.viewportHeight = Math.max(1, height);
    }

    @Override
    public int getViewportWidth() {
        return viewportWidth;
    }

    @Override
    public int getViewportHeight() {
        return viewportHeight;
    }

    @Override
    public void setExportSize(int width, int height) {
        this.exportWidth = Math.max(1, width);
        this.exportHeight = Math.max(1, height);
    }

    @Override
    public int getExportWidth() {
        return exportWidth;
    }

    @Override
    public int getExportHeight() {
        return exportHeight;
    }

    public void setPreviewSamples(int samples) {
        this.previewSamples = Math.max(1, samples);
    }

    public void setFullSamples(int samples) {
        this.fullSamples = Math.max(1, samples);
    }

    public int getFullSamples() {
        return fullSamples;
    }

    @Override
    public String getDeviceName() {
        return engine.getRenderer();
    }

    @Override
    public String getDeviceType() {
        return "GPU (OpenGL)";
    }

    public String getGLVersion() {
        return engine.getGLVersion();
    }

    public String getGLSLVersion() {
        return engine.getGLSLVersion();
    }

    /**
     * Pick focal distance at a specific pixel coordinate.
     * Reads the depth from the accumulation buffer and sets it as the focal distance.
     *
     * @param x X coordinate in viewport space (0 = left)
     * @param y Y coordinate in viewport space (0 = top)
     * @return The picked depth, or -1 if invalid
     */
    public float pickFocalDistance(int x, int y) {
        // Scale coordinates from viewport to render size
        int renderX = (int) ((float) x / viewportWidth * engine.getWidth());
        int renderY = (int) ((float) y / viewportHeight * engine.getHeight());

        float depth = engine.readDepthAt(renderX, renderY);

        if (depth > 0 && depth < 100.0f && currentParams instanceof AbstractFractalParams params) {
            params.setFocalDistance(depth);
            return depth;
        }

        return -1;
    }

    /**
     * Upload a custom gradient palette to the GPU texture.
     */
    public void updatePaletteTexture(GradientPalette gradient) {
        int resolution = 256;
        float[] rgbData = gradient.toTextureData(resolution);
        engine.updatePaletteTexture(rgbData, resolution);
    }

    /**
     * Get underlying engine for advanced use.
     */
    public GLSLEngine getEngine() {
        return engine;
    }

    /**
     * Get progressive renderer for advanced use.
     */
    public ProgressiveRenderer getProgressiveRenderer() {
        return progressiveRenderer;
    }

    @Override
    public void close() {
        progressiveRenderer.shutdown();
        engine.close();
    }
}
