package org.fractalizer.ui;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.fractalizer.engine.Camera;
import org.fractalizer.engine.GLSLEngine;
import org.fractalizer.engine.ShaderPreprocessor;
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

    // Audio panel reference (optional, for audio-reactive uniforms)
    private org.fractalizer.ui.panels.AudioPanel audioPanel;

    // Boolean operations shader state
    private String currentBooleanProgramKey = null;
    private final Map<String, String> preprocessedCache = new HashMap<>();

    // Listeners
    private Consumer<WritableImage> imageListener;
    private Consumer<Double> progressListener;
    private Runnable completeListener;

    public GLSLFractalizerController() throws IOException {
        this.engine = new GLSLEngine(viewportWidth, viewportHeight);
        this.progressiveRenderer = new ProgressiveRenderer(engine);
    }

    public void setAudioPanel(org.fractalizer.ui.panels.AudioPanel panel) {
        this.audioPanel = panel;
    }

    /**
     * Load all fractal shaders with progress reporting.
     */
    public void loadAllShaders(java.util.function.BiConsumer<String, Double> progressCallback) {
        FractalType[] types = FractalType.values();
        for (int i = 0; i < types.length; i++) {
            FractalType type = types[i];
            if (type == FractalType.CUSTOM_SHADER) continue; // no resource file
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
        activateCurrentProgram();

        // Try to get from cache
        currentParams = paramsCache.get(type);

        if (currentParams == null) {
            switch (type) {
                case MANDELBULB -> this.currentParams = new MandelbulbParams();
                case MANDELBOX -> this.currentParams = new MandelboxParams();
                case MENGER_SPONGE -> this.currentParams = new MengerSpongeParams();
                case KALEIDOSCOPIC_IFS -> this.currentParams = new KaleidoscopicIFSParams();
                case POLYHEDRAL_IFS -> this.currentParams = new PolyhedralIFSParams();
                case SIERPINSKI -> this.currentParams = new SierpinskiParams();
                case PSEUDO_KLEINIAN -> this.currentParams = new PseudoKleinianParams();
                case APOLLONIAN -> this.currentParams = new ApollonianParams();
                case BRISTORBROT -> this.currentParams = new BristorbrotParams();
                case QUATERNION_JULIA_4D -> this.currentParams = new QuaternionJulia4DParams();
                case CUSTOM_SHADER -> this.currentParams = new CustomShaderParams();
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
     * Activate the correct shader program (boolean or normal).
     */
    private void activateCurrentProgram() {
        if (currentParams instanceof AbstractFractalParams afp
                && afp.isBooleanEnabled()
                && afp.getBoolSecondaryType() != null) {
            ensureBooleanShader(afp.getBoolSecondaryType());
            String key = currentFractalType.getKernelName() + "+" + afp.getBoolSecondaryType();
            if (engine.hasProgram(key)) {
                engine.setActiveProgram(key);
                currentBooleanProgramKey = key;
                return;
            }
        }
        currentBooleanProgramKey = null;
        String key = currentFractalType.getKernelName();
        if (engine.hasProgram(key)) {
            engine.setActiveProgram(key);
        }
    }

    private void ensureBooleanShader(String secondaryKernelName) {
        String key = currentFractalType.getKernelName() + "+" + secondaryKernelName;
        if (engine.hasProgram(key)) return;

        // Get preprocessed secondary source (cached)
        String preprocessed = preprocessedCache.computeIfAbsent(secondaryKernelName, k -> {
            String rawSource = engine.loadShaderSource("/shaders/fractals/" + k + ".glsl");
            String stripped = rawSource.replaceAll("#version\\s+\\d+\\s+\\w+", "").trim();
            return ShaderPreprocessor.renameLocalSymbols(stripped, "b_");
        });

        String primaryPath = "/shaders/fractals/" + currentFractalType.getKernelName() + ".glsl";
        engine.loadBooleanFractalShader(key, primaryPath, preprocessed);
    }

    @Override
    public String compileCustomShader(String source) {
        String error = engine.loadCustomFractalShader("customshader", source);
        if (error == null) {
            engine.setActiveProgram("customshader");
            engine.resetAccumulation();
        }
        return error;
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
        activateCurrentProgram();

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
        activateCurrentProgram();

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
                activateCurrentProgram();
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
                        activateCurrentProgram();
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
            activateCurrentProgram();
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
                    activateCurrentProgram();
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
            activateCurrentProgram();
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
                    activateCurrentProgram();
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

    // ========================================================================
    // AOV Export (Depth / Normal passes)
    // ========================================================================

    /**
     * Export an AOV pass (depth or normals) to a PNG file.
     * AOV data is deterministic — only 1 sample is needed.
     *
     * @param file       Output PNG file
     * @param renderMode 1 = Normals (RGB), 2 = Depth (16-bit grayscale)
     */
    public void exportAOV(File file, int renderMode) {
        if (exportWidth > MAX_TILE_SIZE || exportHeight > MAX_TILE_SIZE) {
            exportTiledAOV(file, renderMode);
        } else {
            exportSingleAOV(file, renderMode);
        }
    }

    private void exportSingleAOV(File file, int renderMode) {
        try {
            engine.resize(exportWidth, exportHeight);
            activateCurrentProgram();
            engine.resetAccumulation();

            Map<String, Object> uniforms = buildUniforms();
            uniforms.put("renderMode", renderMode);

            // 1 sample is sufficient — AOV data is deterministic
            engine.renderSample(uniforms);
            engine.glSync();

            float[] pixels = engine.readImage();

            if (renderMode == 2) {
                // Depth: 16-bit grayscale
                BufferedImage image = new BufferedImage(exportWidth, exportHeight, BufferedImage.TYPE_USHORT_GRAY);
                short[] raster = ((java.awt.image.DataBufferUShort) image.getRaster().getDataBuffer()).getData();
                for (int y = 0; y < exportHeight; y++) {
                    for (int x = 0; x < exportWidth; x++) {
                        int idx = (y * exportWidth + x) * 4;
                        float lum = pixels[idx]; // Depth is in R channel
                        raster[y * exportWidth + x] = (short) Math.max(0, Math.min(65535, (int) (lum * 65535)));
                    }
                }
                ImageIO.write(image, "png", file);
            } else {
                // Normals: 8-bit RGB
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
                ImageIO.write(image, "png", file);
            }
        } catch (Exception e) {
            System.err.println("AOV export failed: " + e.getMessage());
        } finally {
            engine.resize(viewportWidth, viewportHeight);
        }
    }

    private void exportTiledAOV(File file, int renderMode) {
        try {
            int fullW = exportWidth;
            int fullH = exportHeight;
            float fullWf = (float) fullW;
            float fullHf = (float) fullH;

            int tilesX = (fullW + MAX_TILE_SIZE - 1) / MAX_TILE_SIZE;
            int tilesY = (fullH + MAX_TILE_SIZE - 1) / MAX_TILE_SIZE;

            BufferedImage fullImage;
            short[] depthRaster = null;
            if (renderMode == 2) {
                fullImage = new BufferedImage(fullW, fullH, BufferedImage.TYPE_USHORT_GRAY);
                depthRaster = ((java.awt.image.DataBufferUShort) fullImage.getRaster().getDataBuffer()).getData();
            } else {
                fullImage = new BufferedImage(fullW, fullH, BufferedImage.TYPE_INT_RGB);
            }

            for (int ty = 0; ty < tilesY; ty++) {
                for (int tx = 0; tx < tilesX; tx++) {
                    int tileX = tx * MAX_TILE_SIZE;
                    int tileY = ty * MAX_TILE_SIZE;
                    int tileW = Math.min(MAX_TILE_SIZE, fullW - tileX);
                    int tileH = Math.min(MAX_TILE_SIZE, fullH - tileY);

                    engine.resize(tileW, tileH);
                    activateCurrentProgram();
                    engine.resetAccumulation();

                    Map<String, Object> uniforms = buildUniforms();
                    uniforms.put("renderMode", renderMode);
                    uniforms.put("tileOffset", new float[]{tileX / fullWf, (fullH - tileY - tileH) / fullHf});
                    uniforms.put("tileScale", new float[]{tileW / fullWf, tileH / fullHf});
                    uniforms.put("fullResolution", new float[]{fullWf, fullHf});

                    engine.renderSample(uniforms);
                    engine.glSync();

                    float[] pixels = engine.readImage();
                    for (int py = 0; py < tileH; py++) {
                        for (int px = 0; px < tileW; px++) {
                            int idx = (py * tileW + px) * 4;
                            if (renderMode == 2) {
                                float lum = pixels[idx];
                                depthRaster[(tileY + py) * fullW + (tileX + px)] =
                                    (short) Math.max(0, Math.min(65535, (int) (lum * 65535)));
                            } else {
                                int r = Math.max(0, Math.min(255, (int) (pixels[idx] * 255)));
                                int g = Math.max(0, Math.min(255, (int) (pixels[idx + 1] * 255)));
                                int b = Math.max(0, Math.min(255, (int) (pixels[idx + 2] * 255)));
                                fullImage.setRGB(tileX + px, tileY + py, (r << 16) | (g << 8) | b);
                            }
                        }
                    }
                }
            }

            ImageIO.write(fullImage, "png", file);
        } catch (Exception e) {
            System.err.println("AOV tiled export failed: " + e.getMessage());
        } finally {
            engine.resize(viewportWidth, viewportHeight);
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
            uniforms.put("extraLightType", params.getExtraLightType());
            uniforms.put("extraLightAttachToCamera", params.isExtraLightAttachToCamera() ? 1 : 0);
            uniforms.put("extraLightPos", new float[]{
                params.getExtraLightX(), params.getExtraLightY(), params.getExtraLightZ()
            });
            uniforms.put("extraLightDir", new float[]{
                params.getExtraLightDirX(), params.getExtraLightDirY(), params.getExtraLightDirZ()
            });
            uniforms.put("extraLightColor", new float[]{
                params.getExtraLightR(), params.getExtraLightG(), params.getExtraLightB()
            });
            uniforms.put("extraLightIntensity", params.getExtraLightIntensity());
            uniforms.put("extraLightRange", params.getExtraLightRange());
            uniforms.put("extraLightAreaRadius", params.getExtraLightAreaRadius());
            uniforms.put("extraLightConeAngle", params.getExtraLightConeAngle());
            uniforms.put("extraLightConeSoftness", params.getExtraLightConeSoftness());

            // Material
            uniforms.put("baseHue", new float[]{
                params.getHueR(), params.getHueG(), params.getHueB()
            });
            uniforms.put("paletteIndex", params.getPaletteIndex());
            uniforms.put("colorStrength", params.getColorStrength());
            uniforms.put("paletteOffset", params.getPaletteOffset());
            uniforms.put("coloringMode", params.getColoringMode());

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
            uniforms.put("anamorphicRatio", params.getAnamorphicRatio());
            uniforms.put("bokehBlades", params.getBokehBlades());
            uniforms.put("bokehRotation", params.getBokehRotation());
            uniforms.put("opticalVignettingStrength", params.getOpticalVignettingStrength());
            uniforms.put("tiltShiftEnabled", params.isTiltShiftEnabled() ? 1 : 0);
            uniforms.put("tiltAngleX", params.getTiltAngleX());
            uniforms.put("tiltAngleY", params.getTiltAngleY());
            uniforms.put("dofChromaticStrength", params.getDofChromaticStrength());

            // Path Tracing
            uniforms.put("pathTracingEnabled", params.isPathTracingEnabled() ? 1 : 0);
            uniforms.put("neeEnabled", params.isNeeEnabled() ? 1 : 0);
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
            case SIERPINSKI -> {
                SierpinskiParams p = (SierpinskiParams) currentParams;
                uniforms.put("maxIterations", p.getMaxIterations());
                uniforms.put("scale", p.getScale());
            }
            case PSEUDO_KLEINIAN -> {
                PseudoKleinianParams p = (PseudoKleinianParams) currentParams;
                uniforms.put("maxIterations", p.getMaxIterations());
                uniforms.put("CSize", new float[]{p.getCSizeX(), p.getCSizeY(), p.getCSizeZ()});
                uniforms.put("Size", p.getSize());
                uniforms.put("DEoffset", p.getDEOffset());
                uniforms.put("foldC", new float[]{p.getFoldCx(), p.getFoldCy(), p.getFoldCz()});
            }
            case APOLLONIAN -> {
                ApollonianParams p = (ApollonianParams) currentParams;
                uniforms.put("maxIterations", p.getMaxIterations());
                uniforms.put("scale", p.getScale());
                uniforms.put("foldRadius", p.getFoldRadius());
            }
            case BRISTORBROT -> {
                BristorbrotParams p = (BristorbrotParams) currentParams;
                uniforms.put("maxIterations", p.getMaxIterations());
                uniforms.put("bailout", p.getBailout());
                uniforms.put("juliaC", new float[]{p.getJuliaCx(), p.getJuliaCy(), p.getJuliaCz()});
            }
            case QUATERNION_JULIA_4D -> {
                QuaternionJulia4DParams p = (QuaternionJulia4DParams) currentParams;
                uniforms.put("maxIterations", p.getMaxIterations());
                uniforms.put("bailout", p.getBailout());
                uniforms.put("juliaC", new float[]{p.getJuliaCx(), p.getJuliaCy(), p.getJuliaCz(), p.getJuliaCw()});
                uniforms.put("sliceW", p.getSliceW());
                uniforms.put("rotXW", (float) Math.toRadians(p.getRotXW()));
                uniforms.put("rotYW", (float) Math.toRadians(p.getRotYW()));
                uniforms.put("rotZW", (float) Math.toRadians(p.getRotZW()));
            }
            case CUSTOM_SHADER -> {
                if (currentParams instanceof CustomShaderParams csp) {
                    uniforms.putAll(csp.getUniformValues());
                }
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

        // Adaptive sampling
        if (currentParams instanceof AbstractFractalParams afp) {
            boolean adaptive = afp.isAdaptiveSampling();
            uniforms.put("adaptiveSampling", adaptive ? 1 : 0);
            uniforms.put("varianceThreshold", afp.getVarianceThreshold());
            uniforms.put("minAdaptiveSamples", afp.getMinAdaptiveSamples());
            engine.setAdaptiveSamplingEnabled(adaptive);

            // Erosion
            uniforms.put("erosionEnabled", afp.isErosionEnabled() ? 1 : 0);
            uniforms.put("erosionStrength", afp.getErosionStrength());
            uniforms.put("erosionTime", afp.getErosionTime());
            uniforms.put("erosionScale", afp.getErosionScale());
            uniforms.put("erosionType", afp.getErosionType());

            // Crystallization
            uniforms.put("crystalEnabled", afp.isCrystalEnabled() ? 1 : 0);
            uniforms.put("crystalStrength", afp.getCrystalStrength());
            uniforms.put("crystalTime", afp.getCrystalTime());
            uniforms.put("crystalScale", afp.getCrystalScale());
            uniforms.put("crystalSharpness", afp.getCrystalSharpness());

            // Moss/Lichen
            uniforms.put("mossEnabled", afp.isMossEnabled() ? 1 : 0);
            uniforms.put("mossStrength", afp.getMossStrength());
            uniforms.put("mossTime", afp.getMossTime());
            uniforms.put("mossScale", afp.getMossScale());
            uniforms.put("mossColor", new float[]{afp.getMossColorR(), afp.getMossColorG(), afp.getMossColorB()});
            uniforms.put("mossNormalThreshold", afp.getMossNormalThreshold());

            // Boolean Operations
            if (afp.isBooleanEnabled() && afp.getBoolSecondaryType() != null && currentBooleanProgramKey != null) {
                uniforms.put("boolOp", afp.getBooleanOp());
                uniforms.put("boolOffset", new float[]{afp.getBoolOffsetX(), afp.getBoolOffsetY(), afp.getBoolOffsetZ()});
                uniforms.put("boolScale", afp.getBoolScale());
                uniforms.put("boolBlend", afp.getBoolBlend());
                uniforms.put("boolRotation", new float[]{
                    (float) Math.toRadians(afp.getBoolRotX()),
                    (float) Math.toRadians(afp.getBoolRotY()),
                    (float) Math.toRadians(afp.getBoolRotZ())
                });
                uniforms.put("nestThreshold", afp.getNestThreshold());
                uniforms.put("nestRepeatScale", afp.getNestRepeatScale());
                uniforms.put("nestRotation", (float) Math.toRadians(afp.getNestRotation()));
                uniforms.put("nestMix", afp.getNestMix());
                buildSecondaryUniforms(uniforms, afp.getBoolSecondaryType());
            }
        }

        // Audio-reactive uniforms + fractal parameter modulation
        if (audioPanel != null && audioPanel.isAudioPlaying()) {
            var data = audioPanel.getAudioData();
            float morph = audioPanel.getReactMorph();
            float[] bands = data.bands();

            // Bass energy for fractal morphing (sub-bass + bass bands)
            float bassEnergy = (bands[0] + bands[1]) * 0.5f;
            // Mid energy for secondary modulations
            float midEnergy = (bands[2] + bands[3]) * 0.5f;

            uniforms.put("audioEnabled", 1);
            uniforms.put("audioLevel", data.level());
            uniforms.put("audioBeat", data.beat());
            uniforms.put("audioOnset", data.onset());
            uniforms.put("audioBands", bands);
            uniforms.put("audioReactPower", morph);
            uniforms.put("audioReactColor", audioPanel.getReactColor());
            uniforms.put("audioReactGlow", audioPanel.getReactGlow());
            uniforms.put("audioReactFOV", audioPanel.getReactFOV());
            uniforms.put("audioReactOnset", audioPanel.getReactOnset());
            uniforms.put("audioReactFog", audioPanel.getReactFog());

            // New dynamic visual reaction uniforms
            uniforms.put("audioReactShake", audioPanel.getReactShake());
            uniforms.put("audioReactWarp", audioPanel.getReactWarp());
            uniforms.put("audioReactPaletteJump", audioPanel.getReactPaletteJump());
            uniforms.put("audioFrameIndex", audioPanel.getAudioFrameIndex());

            // Post-process pump: modulate exposure/vignette/CA/saturation
            float pump = audioPanel.getReactPump();
            GLSLEngine.PostProcessParams pp = engine.getPostProcessParams();
            if (pump > 0.01f) {
                float beat = data.beat();
                float level = data.level();
                pp.audioDeltaExposure = beat * pump * 0.8f;
                pp.audioDeltaSaturation = level * pump * 0.5f;
                if (beat * pump > 0.1f) {
                    pp.audioForceCA = true;
                    pp.audioDeltaCA = beat * pump * 0.015f;
                    pp.audioForceVignette = true;
                    pp.audioDeltaVignette = beat * pump * 0.4f;
                } else {
                    pp.audioForceCA = false;
                    pp.audioDeltaCA = 0f;
                    pp.audioForceVignette = false;
                    pp.audioDeltaVignette = 0f;
                }
            } else {
                pp.audioDeltaExposure = 0f;
                pp.audioDeltaSaturation = 0f;
                pp.audioDeltaCA = 0f;
                pp.audioDeltaVignette = 0f;
                pp.audioForceCA = false;
                pp.audioForceVignette = false;
            }

            // ============================================================
            // Java-side fractal parameter modulation (overwrites uniforms)
            // This is where the geometry actually transforms with the music
            // ============================================================
            if (morph > 0.01f) {
                applyAudioMorphing(uniforms, bassEnergy, midEnergy, data.beat(), morph);
            }
        } else {
            uniforms.put("audioEnabled", 0);
            uniforms.put("audioLevel", 0.0f);
            uniforms.put("audioBeat", 0.0f);
            uniforms.put("audioOnset", 0.0f);
            uniforms.put("audioBands", new float[8]);
            uniforms.put("audioReactPower", 0.0f);
            uniforms.put("audioReactColor", 0.0f);
            uniforms.put("audioReactGlow", 0.0f);
            uniforms.put("audioReactFOV", 0.0f);
            uniforms.put("audioReactOnset", 0.0f);
            uniforms.put("audioReactFog", 0.0f);
            uniforms.put("audioReactShake", 0.0f);
            uniforms.put("audioReactWarp", 0.0f);
            uniforms.put("audioReactPaletteJump", 0.0f);
            uniforms.put("audioFrameIndex", 0);

            // Reset post-process pump deltas
            GLSLEngine.PostProcessParams pp = engine.getPostProcessParams();
            pp.audioDeltaExposure = 0f;
            pp.audioDeltaSaturation = 0f;
            pp.audioDeltaCA = 0f;
            pp.audioDeltaVignette = 0f;
            pp.audioForceCA = false;
            pp.audioForceVignette = false;
        }

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

    /**
     * Emit uniforms for the secondary (boolean) fractal with b_ prefix.
     * Uses default params for the secondary type.
     */
    private void buildSecondaryUniforms(Map<String, Object> uniforms, String kernelName) {
        FractalType secType = null;
        for (FractalType ft : FractalType.values()) {
            if (ft.getKernelName().equals(kernelName)) { secType = ft; break; }
        }
        if (secType == null) return;

        // Create default params for this type (just for uniform values)
        AbstractFractalParams p;
        switch (secType) {
            case MANDELBULB -> p = new MandelbulbParams();
            case MANDELBOX -> p = new MandelboxParams();
            case MENGER_SPONGE -> p = new MengerSpongeParams();
            case KALEIDOSCOPIC_IFS -> p = new KaleidoscopicIFSParams();
            case POLYHEDRAL_IFS -> p = new PolyhedralIFSParams();
            case SIERPINSKI -> p = new SierpinskiParams();
            case PSEUDO_KLEINIAN -> p = new PseudoKleinianParams();
            case APOLLONIAN -> p = new ApollonianParams();
            case BRISTORBROT -> p = new BristorbrotParams();
            case QUATERNION_JULIA_4D -> p = new QuaternionJulia4DParams();
            default -> { return; }
        };

        // Emit all fractal-specific uniforms with b_ prefix
        switch (secType) {
            case MANDELBULB -> {
                MandelbulbParams mb = (MandelbulbParams) p;
                uniforms.put("b_power", mb.getPower());
                uniforms.put("b_maxIterations", mb.getMaxIterations());
                uniforms.put("b_bailout", mb.getBailout());
            }
            case MANDELBOX -> {
                MandelboxParams mb = (MandelboxParams) p;
                uniforms.put("b_scale", mb.getScale());
                uniforms.put("b_minRadius", mb.getMinRadius());
                uniforms.put("b_fixedRadius", mb.getFixedRadius());
                uniforms.put("b_foldingLimit", mb.getFoldingLimit());
                uniforms.put("b_maxIterations", mb.getMaxIterations());
            }
            case MENGER_SPONGE -> {
                MengerSpongeParams ms = (MengerSpongeParams) p;
                uniforms.put("b_maxIterations", ms.getMaxIterations());
                uniforms.put("b_scale", ms.getScale());
                uniforms.put("b_offset", new float[]{ms.getOffsetX(), ms.getOffsetY(), ms.getOffsetZ()});
            }
            case KALEIDOSCOPIC_IFS -> {
                KaleidoscopicIFSParams k = (KaleidoscopicIFSParams) p;
                uniforms.put("b_maxIterations", k.getMaxIterations());
                uniforms.put("b_scale", k.getScale());
                uniforms.put("b_foldAngleX", (float) Math.toRadians(k.getFoldAngleX()));
                uniforms.put("b_foldAngleY", (float) Math.toRadians(k.getFoldAngleY()));
                uniforms.put("b_ifsOffset", k.getOffsetX());
            }
            case POLYHEDRAL_IFS -> {
                PolyhedralIFSParams pi = (PolyhedralIFSParams) p;
                uniforms.put("b_polyType", pi.getPolyType().ordinal());
                uniforms.put("b_maxIterations", pi.getMaxIterations());
                uniforms.put("b_scale", pi.getScale());
                uniforms.put("b_offset", new float[]{pi.getOffsetX(), pi.getOffsetY(), pi.getOffsetZ()});
                uniforms.put("b_shift", new float[]{pi.getShiftX(), pi.getShiftY(), pi.getShiftZ()});
                uniforms.put("b_fractalRotation1", createRotationMatrix(pi.getRot1X(), pi.getRot1Y(), pi.getRot1Z()));
                uniforms.put("b_fractalRotation2", createRotationMatrix(pi.getRot2X(), pi.getRot2Y(), pi.getRot2Z()));
            }
            case SIERPINSKI -> {
                SierpinskiParams si = (SierpinskiParams) p;
                uniforms.put("b_maxIterations", si.getMaxIterations());
                uniforms.put("b_scale", si.getScale());
            }
            case PSEUDO_KLEINIAN -> {
                PseudoKleinianParams pk = (PseudoKleinianParams) p;
                uniforms.put("b_maxIterations", pk.getMaxIterations());
                uniforms.put("b_CSize", new float[]{pk.getCSizeX(), pk.getCSizeY(), pk.getCSizeZ()});
                uniforms.put("b_Size", pk.getSize());
                uniforms.put("b_DEoffset", pk.getDEOffset());
                uniforms.put("b_foldC", new float[]{pk.getFoldCx(), pk.getFoldCy(), pk.getFoldCz()});
            }
            case APOLLONIAN -> {
                ApollonianParams ap = (ApollonianParams) p;
                uniforms.put("b_maxIterations", ap.getMaxIterations());
                uniforms.put("b_scale", ap.getScale());
                uniforms.put("b_foldRadius", ap.getFoldRadius());
            }
            case BRISTORBROT -> {
                BristorbrotParams br = (BristorbrotParams) p;
                uniforms.put("b_maxIterations", br.getMaxIterations());
                uniforms.put("b_bailout", br.getBailout());
                uniforms.put("b_juliaC", new float[]{br.getJuliaCx(), br.getJuliaCy(), br.getJuliaCz()});
            }
            case QUATERNION_JULIA_4D -> {
                QuaternionJulia4DParams qj = (QuaternionJulia4DParams) p;
                uniforms.put("b_maxIterations", qj.getMaxIterations());
                uniforms.put("b_bailout", qj.getBailout());
                uniforms.put("b_juliaC", new float[]{qj.getJuliaCx(), qj.getJuliaCy(), qj.getJuliaCz(), qj.getJuliaCw()});
                uniforms.put("b_sliceW", qj.getSliceW());
                uniforms.put("b_rotXW", (float) Math.toRadians(qj.getRotXW()));
                uniforms.put("b_rotYW", (float) Math.toRadians(qj.getRotYW()));
                uniforms.put("b_rotZW", (float) Math.toRadians(qj.getRotZW()));
            }
            default -> {}
        }
    }

    /**
     * Apply audio-reactive morphing to fractal uniforms.
     * Modulates the actual geometry parameters (power, scale, folds, juliaC...)
     * so the fractal visibly transforms with the music.
     *
     * @param uniforms  The uniform map to modify in-place
     * @param bass      Smoothed bass energy [0..~1]
     * @param mid       Smoothed mid energy [0..~1]
     * @param beat      Beat pulse [0..1]
     * @param morph     User morph amount slider [0..1]
     */
    private void applyAudioMorphing(Map<String, Object> uniforms, float bass, float mid, float beat, float morph) {
        switch (currentFractalType) {
            case MANDELBULB -> {
                MandelbulbParams p = (MandelbulbParams) currentParams;
                // Power: base ± up to 4.0 driven by bass (e.g. 8 → 4..12+)
                float powerDelta = bass * morph * 4.0f;
                // Beat adds sharp spikes
                powerDelta += beat * morph * 2.0f;
                // Mid adds gentle modulation
                powerDelta += mid * morph * 1.0f;
                uniforms.put("power", p.getPower() + powerDelta);
                // Bailout modulation — makes bulbs grow/shrink
                uniforms.put("bailout", p.getBailout() + bass * morph * 1.5f);
            }
            case MANDELBOX -> {
                MandelboxParams p = (MandelboxParams) currentParams;
                // Scale: base ± 0.8 driven by bass (very sensitive parameter)
                float scaleDelta = bass * morph * 0.8f;
                scaleDelta += beat * morph * 0.3f;
                uniforms.put("scale", p.getScale() + scaleDelta);
                // FoldingLimit modulated by mid
                float foldDelta = mid * morph * 0.5f;
                uniforms.put("foldingLimit", p.getFoldingLimit() + foldDelta);
            }
            case MENGER_SPONGE -> {
                MengerSpongeParams p = (MengerSpongeParams) currentParams;
                // Scale modulation
                float scaleDelta = bass * morph * 0.5f;
                uniforms.put("scale", p.getScale() + scaleDelta);
                // Offset wobble driven by mid
                float wobble = mid * morph * 0.3f;
                uniforms.put("offset", new float[]{
                    p.getOffsetX() + wobble,
                    p.getOffsetY() + wobble * 0.7f,
                    p.getOffsetZ() + beat * morph * 0.2f
                });
            }
            case KALEIDOSCOPIC_IFS -> {
                KaleidoscopicIFSParams p = (KaleidoscopicIFSParams) currentParams;
                // Scale modulation ±0.6
                float scaleDelta = bass * morph * 0.6f + beat * morph * 0.2f;
                uniforms.put("scale", p.getScale() + scaleDelta);
                // Fold angles: VERY visible on KIFS — kaleidoscope rotation
                float angleModX = bass * morph * 30.0f + beat * morph * 15.0f;
                float angleModY = mid * morph * 25.0f;
                uniforms.put("foldAngleX", (float) Math.toRadians(p.getFoldAngleX() + angleModX));
                uniforms.put("foldAngleY", (float) Math.toRadians(p.getFoldAngleY() + angleModY));
                // Offset modulation
                uniforms.put("ifsOffset", p.getOffsetX() + mid * morph * 0.5f);
            }
            case POLYHEDRAL_IFS -> {
                PolyhedralIFSParams p = (PolyhedralIFSParams) currentParams;

                // Scale: ±0.6 — makes structure expand/contract visibly
                float scaleDelta = bass * morph * 0.6f + beat * morph * 0.25f;
                uniforms.put("scale", p.getScale() + scaleDelta);

                // Offset: the fold center — bass moves it, very geometric effect
                float offMod = bass * morph * 0.4f;
                uniforms.put("offset", new float[]{
                    p.getOffsetX() + offMod,
                    p.getOffsetY() + mid * morph * 0.3f,
                    p.getOffsetZ() + beat * morph * 0.2f
                });

                // Shift: displacement — mid-frequency driven
                float shiftMod = mid * morph * 0.35f;
                uniforms.put("shift", new float[]{
                    p.getShiftX() + shiftMod,
                    p.getShiftY() + bass * morph * 0.25f,
                    p.getShiftZ() + beat * morph * 0.2f
                });

                // Rotation 1: DRAMATIC — bass rotates the fractal (kaleidoscopic twisting)
                float rot1Mod = bass * morph * 25.0f; // up to 25 degrees
                float rot1Beat = beat * morph * 12.0f;
                uniforms.put("fractalRotation1", createRotationMatrix(
                    p.getRot1X() + rot1Mod,
                    p.getRot1Y() + mid * morph * 15.0f,
                    p.getRot1Z() + rot1Beat
                ));

                // Rotation 2: secondary twist — mid frequencies
                float rot2Mod = mid * morph * 20.0f;
                uniforms.put("fractalRotation2", createRotationMatrix(
                    p.getRot2X() + rot2Mod * 0.7f,
                    p.getRot2Y() + bass * morph * 10.0f,
                    p.getRot2Z() + beat * morph * 8.0f
                ));
            }
            case SIERPINSKI -> {
                SierpinskiParams p = (SierpinskiParams) currentParams;
                float scaleDelta = bass * morph * 0.3f;
                uniforms.put("scale", p.getScale() + scaleDelta);
            }
            case PSEUDO_KLEINIAN -> {
                PseudoKleinianParams p = (PseudoKleinianParams) currentParams;
                // CSize modulation — very responsive to changes
                float mod = bass * morph * 0.15f;
                uniforms.put("CSize", new float[]{
                    p.getCSizeX() + mod,
                    p.getCSizeY() + mid * morph * 0.1f,
                    p.getCSizeZ() + beat * morph * 0.08f
                });
                uniforms.put("Size", p.getSize() + bass * morph * 0.1f);
            }
            case APOLLONIAN -> {
                ApollonianParams p = (ApollonianParams) currentParams;
                float scaleDelta = bass * morph * 0.3f;
                uniforms.put("scale", p.getScale() + scaleDelta);
                float foldDelta = mid * morph * 0.2f;
                uniforms.put("foldRadius", p.getFoldRadius() + foldDelta);
            }
            case BRISTORBROT -> {
                BristorbrotParams p = (BristorbrotParams) currentParams;
                float cx = bass * morph * 0.2f;
                float cy = mid * morph * 0.15f;
                uniforms.put("juliaC", new float[]{
                    p.getJuliaCx() + cx,
                    p.getJuliaCy() + cy,
                    p.getJuliaCz()
                });
            }
            case QUATERNION_JULIA_4D -> {
                QuaternionJulia4DParams p = (QuaternionJulia4DParams) currentParams;
                float cxMod = bass * morph * 0.2f;
                float cyMod = mid * morph * 0.15f;
                uniforms.put("juliaC", new float[]{
                    p.getJuliaCx() + cxMod,
                    p.getJuliaCy() + cyMod,
                    p.getJuliaCz(),
                    p.getJuliaCw()
                });
                uniforms.put("sliceW", p.getSliceW() + mid * morph * 0.3f);
                uniforms.put("rotXW", (float) Math.toRadians(p.getRotXW() + beat * morph * 15.0f));
                uniforms.put("rotYW", (float) Math.toRadians(p.getRotYW() + bass * morph * 10.0f));
                uniforms.put("rotZW", (float) Math.toRadians(p.getRotZW() + mid * morph * 8.0f));
            }
            default -> {
                // TEST_SCENE, CORNELL_BOX: no morphing
            }
        }
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
    public void prepareGPUEvaluator() {
        if (currentParams instanceof AbstractFractalParams afp) {
            String shaderPath = "/shaders/fractals/" + afp.getType().getKernelName() + ".glsl";
            engine.loadEvaluatorShader(shaderPath);
        }
    }

    @Override
    public float[] evaluateGPUSlice(float zPos, float boundsHalf, int resolution) {
        Map<String, Object> uniforms = buildUniforms();
        return engine.evaluateSlice(uniforms, zPos, boundsHalf, resolution);
    }

    @Override
    public void close() {
        progressiveRenderer.shutdown();
        engine.close();
    }
}
