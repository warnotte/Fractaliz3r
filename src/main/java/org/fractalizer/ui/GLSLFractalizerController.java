package org.fractalizer.ui;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.fractalizer.engine.Camera;
import org.fractalizer.engine.GLSLEngine;
import org.fractalizer.fractals.*;
import org.fractalizer.render.ProgressiveRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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

        loadAllShaders();
        setFractalType(FractalType.MANDELBULB);
    }

    /**
     * Load all fractal shaders.
     */
    private void loadAllShaders() {
        engine.loadFractalShader("mandelbulb", "/shaders/fractals/mandelbulb.glsl");
        engine.loadFractalShader("mandelbox", "/shaders/fractals/mandelbox.glsl");
        engine.loadFractalShader("menger", "/shaders/fractals/menger.glsl");
        engine.loadFractalShader("kaleidoscopic", "/shaders/fractals/kaleidoscopic.glsl");
        engine.loadFractalShader("julia3d", "/shaders/fractals/julia3d.glsl");
        engine.loadFractalShader("pseudokleinian", "/shaders/fractals/pseudokleinian.glsl");
    }

    /**
     * Switch to a different fractal type.
     */
    @Override
    public void setFractalType(FractalType type) {
        if (type == currentFractalType && currentParams != null) return;

        this.currentFractalType = type;
        engine.setActiveProgram(type.getKernelName());

        Camera oldCamera = (currentParams instanceof AbstractFractalParams afp) ? afp.getCamera() : null;

        switch (type) {
            case MANDELBULB -> this.currentParams = new MandelbulbParams();
            case MANDELBOX -> this.currentParams = new MandelboxParams();
            case MENGER_SPONGE -> this.currentParams = new MengerSpongeParams();
            case KALEIDOSCOPIC_IFS -> this.currentParams = new KaleidoscopicIFSParams();
            case JULIA_3D -> this.currentParams = new Julia3DParams();
            case PSEUDO_KLEINIAN -> this.currentParams = new PseudoKleinianParams();
        }

        // Preserve camera if switching fractals
        if (oldCamera != null && currentParams instanceof AbstractFractalParams newParams) {
            newParams.setCamera(oldCamera);
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
     * Export the current render to a PNG file (uses export size).
     */
    @Override
    public CompletableFuture<Void> exportToPNG(File file, Consumer<Double> onProgress) {
        return CompletableFuture.runAsync(() -> {
            try {
                cancelRender();
                engine.resize(exportWidth, exportHeight);
                engine.setActiveProgram(currentFractalType.getKernelName());
                engine.resetAccumulation();

                Map<String, Object> uniforms = buildUniforms();

                // Render with more samples for export
                int exportSamples = fullSamples * 2;
                for (int i = 0; i < exportSamples; i++) {
                    engine.renderSample(uniforms);
                    if (onProgress != null) {
                        double progress = (double) (i + 1) / exportSamples;
                        Platform.runLater(() -> onProgress.accept(progress));
                    }
                }

                // Read and save image
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

                ImageIO.write(image, "PNG", file);

            } catch (Exception e) {
                throw new RuntimeException("Failed to export image", e);
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
        try {
            // Resize engine to export dimensions
            engine.resize(width, height);
            engine.setActiveProgram(currentFractalType.getKernelName());
            engine.resetAccumulation();

            Map<String, Object> uniforms = buildUniforms();

            // Render with specified samples
            for (int i = 0; i < samples; i++) {
                engine.renderSample(uniforms);
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

            // Write to file
            ImageIO.write(bufferedImage, "PNG", file);

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
        try {
            // Resize engine to export dimensions
            engine.resize(width, height);
            engine.setActiveProgram(currentFractalType.getKernelName());
            engine.resetAccumulation();

            // Calculate shutter window
            // Shutter angle 180° = shutter open for half the frame duration
            double frameDuration = 1.0 / fps;
            double shutterDuration = frameDuration * (shutterAngle / 360.0);

            Random random = new Random();

            // Render samples with time jittering for motion blur
            for (int i = 0; i < samples; i++) {
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
            ImageIO.write(bufferedImage, "PNG", file);

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

            // Material System
            uniforms.put("materialType", params.getMaterialType());
            uniforms.put("metalness", params.getMetalness());
            uniforms.put("ior", params.getIor());

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
            case PSEUDO_KLEINIAN -> {
                PseudoKleinianParams p = (PseudoKleinianParams) currentParams;
                uniforms.put("maxIterations", p.getMaxIterations());
                uniforms.put("size", p.getSize());
                uniforms.put("cSize", new float[]{
                    p.getCSizeX(), p.getCSizeY(), p.getCSizeZ()
                });
                uniforms.put("juliaC", new float[]{
                    p.getJuliaX(), p.getJuliaY(), p.getJuliaZ()
                });
                uniforms.put("deOffset", p.getDeOffset());
                uniforms.put("zOffset", p.getZOffset());
            }
        }

        return uniforms;
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
            System.out.println("[Focus] Picked focal distance: " + depth);
            return depth;
        }

        return -1;
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
