package org.fractalizer.engine;

import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * GLSL-based rendering engine for Fractaliz3r.
 *
 * Features:
 * - Offscreen OpenGL context (hidden GLFW window)
 * - Progressive rendering with accumulation buffer
 * - Fragmentarium-style shader architecture
 * - No watchdog issues (each frame is fast)
 * - Easy integration with JavaFX via buffer readback
 */
public class GLSLEngine implements AutoCloseable {

    // GLFW window (hidden, used only for GL context)
    private long window;

    // Framebuffers
    private int accumFBO;
    private int accumTexture;
    private int displayFBO;      // Separate FBO for display output (avoids issues with hidden window)
    private int displayTexture;
    private int currentWidth;
    private int currentHeight;

    // Bloom framebuffers (ping-pong for blur passes)
    private int bloomFBO1, bloomFBO2;
    private int bloomTexture1, bloomTexture2;
    private int bloomWidth, bloomHeight;  // Half resolution for performance

    // Environment map
    private int envMapTexture;
    private boolean envMapLoaded = false;
    private float envRotation = 0.0f;
    private float envLightingMix = 0.5f;  // 0 = directional only, 1 = full HDRI lighting

    // Fullscreen quad VAO
    private int quadVAO;
    private int quadVBO;
    private int quadEBO;

    // Shader programs
    private final Map<String, ShaderProgram> programs = new HashMap<>();
    private String activeProgram;

    // Progressive rendering state
    private int sampleCount = 0;
    private int maxSamples = 10000;
    private boolean needsReset = true;
    private int currentRenderMode = 0;  // Track render mode for display shader

    // Post-processing shaders
    private ShaderProgram displayProgram;      // Legacy (kept for compatibility)
    private ShaderProgram postProcessProgram;  // Main post-processing
    private ShaderProgram bloomExtractProgram; // Bright pixel extraction
    private ShaderProgram bloomBlurProgram;    // Gaussian blur

    // Post-processing parameters (with defaults)
    private PostProcessParams postProcessParams = new PostProcessParams();

    // Thread safety - GL context is single-threaded
    private final ExecutorService glThread;
    private volatile boolean initialized = false;

    // Device info
    private String renderer;
    private String glVersion;
    private String glslVersion;

    public GLSLEngine() {
        this(1280, 720);
    }

    public GLSLEngine(int width, int height) {
        this.currentWidth = width;
        this.currentHeight = height;
        this.glThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "GLSLEngine-Thread");
            t.setDaemon(true);
            return t;
        });

        // Initialize on GL thread
        try {
            glThread.submit(this::initialize).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize GLSLEngine", e);
        }
    }

    private void initialize() {
        // Initialize GLFW
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        // Configure GLFW for offscreen rendering
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);  // Hidden window
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);

        // Create hidden window for GL context
        window = glfwCreateWindow(currentWidth, currentHeight, "Fractaliz3r GL Context", NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        glfwMakeContextCurrent(window);
        GL.createCapabilities();

        // Get device info
        renderer = glGetString(GL_RENDERER);
        glVersion = glGetString(GL_VERSION);
        glslVersion = glGetString(GL_SHADING_LANGUAGE_VERSION);

        System.out.println("=== GLSL Engine Initialized ===");
        System.out.println("Renderer: " + renderer);
        System.out.println("OpenGL Version: " + glVersion);
        System.out.println("GLSL Version: " + glslVersion);
        System.out.println("===============================");

        // Create resources
        createFullscreenQuad();
        createFramebuffer(currentWidth, currentHeight);
        createBloomFramebuffers(currentWidth, currentHeight);
        createDefaultEnvMap();
        loadDisplayShader();
        loadPostProcessShaders();

        initialized = true;
    }

    /**
     * Load a fractal shader program.
     * Combines: common.glsl + fractal.glsl + raytracer.glsl
     */
    public void loadFractalShader(String name, String fractalShaderPath) {
        runOnGLThread(() -> {
            try {
                String vertexSource = loadResource("/shaders/fullscreen.vert");
                String commonSource = loadResource("/shaders/common.glsl");
                String fractalSource = loadResource(fractalShaderPath);
                String raytracerSource = loadResource("/shaders/raytracer.glsl");

                // Combine sources: #version + common + fractal + raytracer
                String fragmentSource = "#version 430 core\n" +
                    commonSource + "\n" +
                    fractalSource + "\n" +
                    raytracerSource;

                ShaderProgram program = new ShaderProgram(vertexSource, fragmentSource);
                programs.put(name, program);

                System.out.println("Loaded shader: " + name);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load shader: " + name, e);
            }
        });
    }

    /**
     * Set the active shader program for rendering.
     */
    public void setActiveProgram(String name) {
        if (!programs.containsKey(name)) {
            throw new IllegalArgumentException("Unknown shader program: " + name);
        }
        if (!name.equals(activeProgram)) {
            activeProgram = name;
            needsReset = true;
        }
    }

    /**
     * Resize the render target.
     */
    public void resize(int width, int height) {
        if (width != currentWidth || height != currentHeight) {
            runOnGLThread(() -> {
                currentWidth = width;
                currentHeight = height;
                recreateFramebuffer();
                needsReset = true;
            });
        }
    }

    /**
     * Reset accumulation (call when camera or parameters change).
     */
    public void resetAccumulation() {
        needsReset = true;
        sampleCount = 0;  // Reset immediately so render loop knows to start fresh
    }

    /**
     * Render one sample (progressive rendering).
     * Call this repeatedly to accumulate samples.
     *
     * @param uniforms Map of uniform name -> value (Float, Integer, float[], int[])
     */
    public void renderSample(Map<String, Object> uniforms) {
        runOnGLThread(() -> {
            if (activeProgram == null) {
                throw new IllegalStateException("No active shader program");
            }

            if (needsReset) {
                clearAccumulation();
                needsReset = false;
            }

            if (sampleCount >= maxSamples) {
                return; // Max samples reached
            }

            ShaderProgram program = programs.get(activeProgram);

            // Bind FBO for accumulation
            glBindFramebuffer(GL_FRAMEBUFFER, accumFBO);
            glViewport(0, 0, currentWidth, currentHeight);

            // Enable additive blending
            glEnable(GL_BLEND);
            glBlendFunc(GL_ONE, GL_ONE);

            // Use shader
            program.use();

            // Set standard uniforms
            program.setUniform("resolution", (float) currentWidth, (float) currentHeight);
            program.setUniform("sampleIndex", sampleCount);
            program.setUniform("time", (float) glfwGetTime());

            // Environment map
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, envMapTexture);
            program.setUniform("envMap", 0);
            program.setUniform("useEnvMap", envMapLoaded ? 1 : 0);
            program.setUniform("envRotation", envRotation);
            program.setUniform("envLightingMix", envLightingMix);

            // Set user uniforms
            for (Map.Entry<String, Object> entry : uniforms.entrySet()) {
                setUniformValue(program, entry.getKey(), entry.getValue());
            }

            // Render fullscreen quad
            glBindVertexArray(quadVAO);
            glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);

            glDisable(GL_BLEND);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);

            sampleCount++;
        });
    }

    /**
     * Render multiple samples at once.
     */
    public void renderSamples(Map<String, Object> uniforms, int count) {
        for (int i = 0; i < count; i++) {
            renderSample(uniforms);
        }
    }

    /**
     * Get current sample count.
     */
    public int getSampleCount() {
        return sampleCount;
    }

    /**
     * Set maximum samples.
     */
    public void setMaxSamples(int max) {
        this.maxSamples = max;
    }

    /**
     * Read the accumulated image as RGBA float array.
     * Applies full post-processing pipeline: bloom, tone mapping, effects.
     */
    public float[] readImage() {
        float[] result = new float[currentWidth * currentHeight * 4];

        runOnGLThread(() -> {
            // Step 1: Render bloom (if enabled)
            renderBloom();

            // Step 2: Final composite with post-processing
            glBindFramebuffer(GL_FRAMEBUFFER, displayFBO);
            glViewport(0, 0, currentWidth, currentHeight);
            glClear(GL_COLOR_BUFFER_BIT);

            postProcessProgram.use();

            // Bind textures
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, accumTexture);
            postProcessProgram.setUniform("accumTexture", 0);

            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, bloomTexture1);
            postProcessProgram.setUniform("bloomTexture", 1);

            // Set uniforms
            postProcessProgram.setUniform("sampleCount", Math.max(1, sampleCount));
            postProcessProgram.setUniform("renderMode", currentRenderMode);
            postProcessProgram.setUniform("resolution", (float) currentWidth, (float) currentHeight);

            // Post-processing parameters
            PostProcessParams pp = postProcessParams;
            postProcessProgram.setUniform("toneMapMode", pp.toneMapMode);
            postProcessProgram.setUniform("exposure", pp.exposure);

            postProcessProgram.setUniform("bloomEnabled", pp.bloomEnabled ? 1 : 0);
            postProcessProgram.setUniform("bloomIntensity", pp.bloomIntensity);
            postProcessProgram.setUniform("bloomThreshold", pp.bloomThreshold);

            postProcessProgram.setUniform("chromaticAberrationEnabled", pp.chromaticAberrationEnabled ? 1 : 0);
            postProcessProgram.setUniform("chromaticAberrationIntensity", pp.chromaticAberrationIntensity);

            postProcessProgram.setUniform("vignetteEnabled", pp.vignetteEnabled ? 1 : 0);
            postProcessProgram.setUniform("vignetteIntensity", pp.vignetteIntensity);
            postProcessProgram.setUniform("vignetteSoftness", pp.vignetteSoftness);

            postProcessProgram.setUniform("filmGrainEnabled", pp.filmGrainEnabled ? 1 : 0);
            postProcessProgram.setUniform("filmGrainIntensity", pp.filmGrainIntensity);
            postProcessProgram.setUniform("filmGrainTime", (float) glfwGetTime());

            postProcessProgram.setUniform("sharpenEnabled", pp.sharpenEnabled ? 1 : 0);
            postProcessProgram.setUniform("sharpenIntensity", pp.sharpenIntensity);
            postProcessProgram.setUniform("saturation", pp.saturation);
            
            postProcessProgram.setUniform("colorGradingMode", pp.colorGradingMode);
            postProcessProgram.setUniform("colorGradingIntensity", pp.colorGradingIntensity);

            glBindVertexArray(quadVAO);
            glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);

            // Force GPU to complete all pending operations before reading
            // This prevents black tiles at high resolutions where the GPU
            // may not have finished processing all regions
            glFinish();

            // Read pixels from display FBO
            FloatBuffer buffer = MemoryUtil.memAllocFloat(result.length);
            glReadPixels(0, 0, currentWidth, currentHeight, GL_RGBA, GL_FLOAT, buffer);
            buffer.get(result);
            MemoryUtil.memFree(buffer);

            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        });

        // Flip Y (OpenGL has origin at bottom-left)
        flipImageY(result, currentWidth, currentHeight);

        return result;
    }

    /**
     * Read raw accumulated image (no tone mapping).
     */
    public float[] readRawImage() {
        float[] result = new float[currentWidth * currentHeight * 4];

        runOnGLThread(() -> {
            glBindFramebuffer(GL_FRAMEBUFFER, accumFBO);
            FloatBuffer buffer = MemoryUtil.memAllocFloat(result.length);
            glReadPixels(0, 0, currentWidth, currentHeight, GL_RGBA, GL_FLOAT, buffer);
            buffer.get(result);
            MemoryUtil.memFree(buffer);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        });

        // Divide by sample count and flip Y
        int samples = Math.max(1, sampleCount);
        for (int i = 0; i < result.length; i++) {
            result[i] /= samples;
        }
        flipImageY(result, currentWidth, currentHeight);

        return result;
    }

    /**
     * Read the depth (from alpha channel) at a specific pixel coordinate.
     * Used for click-to-focus feature.
     *
     * @param x X coordinate (0 = left)
     * @param y Y coordinate (0 = top, will be flipped internally)
     * @return Depth at that pixel, or -1 if out of bounds
     */
    public float readDepthAt(int x, int y) {
        if (x < 0 || x >= currentWidth || y < 0 || y >= currentHeight) {
            return -1.0f;
        }

        // Flip Y coordinate (OpenGL has origin at bottom-left)
        int glY = currentHeight - 1 - y;

        float[] depth = new float[1];

        runOnGLThread(() -> {
            glBindFramebuffer(GL_FRAMEBUFFER, accumFBO);

            // Read just the single pixel (4 floats for RGBA)
            FloatBuffer buffer = MemoryUtil.memAllocFloat(4);
            glReadPixels(x, glY, 1, 1, GL_RGBA, GL_FLOAT, buffer);

            // Depth is in alpha channel (index 3)
            // Divide by sample count since it's accumulated
            float accumDepth = buffer.get(3);
            int samples = Math.max(1, sampleCount);
            depth[0] = accumDepth / samples;

            MemoryUtil.memFree(buffer);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        });

        return depth[0];
    }

    /**
     * Get current dimensions.
     */
    public int getWidth() { return currentWidth; }
    public int getHeight() { return currentHeight; }

    /**
     * Get device info.
     */
    public String getRenderer() { return renderer; }
    public String getGLVersion() { return glVersion; }
    public String getGLSLVersion() { return glslVersion; }

    // ========================================================================
    // Private helpers
    // ========================================================================

    private void createFullscreenQuad() {
        float[] vertices = {
            // pos      // uv
            -1.0f, -1.0f,  0.0f, 0.0f,
             1.0f, -1.0f,  1.0f, 0.0f,
             1.0f,  1.0f,  1.0f, 1.0f,
            -1.0f,  1.0f,  0.0f, 1.0f
        };
        int[] indices = {0, 1, 2, 2, 3, 0};

        quadVAO = glGenVertexArrays();
        quadVBO = glGenBuffers();
        quadEBO = glGenBuffers();

        glBindVertexArray(quadVAO);

        glBindBuffer(GL_ARRAY_BUFFER, quadVBO);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, quadEBO);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8);
        glEnableVertexAttribArray(1);

        glBindVertexArray(0);
    }

    private void createFramebuffer(int width, int height) {
        // Create float32 texture for accumulation
        accumTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, accumTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, width, height, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        // Create accumulation FBO
        accumFBO = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, accumFBO);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, accumTexture, 0);

        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Accumulation framebuffer not complete!");
        }

        // Create display texture (for readback - avoids issues with hidden window's default framebuffer)
        displayTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, displayTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, width, height, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        // Create display FBO
        displayFBO = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, displayFBO);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, displayTexture, 0);

        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Display framebuffer not complete!");
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private void recreateFramebuffer() {
        glDeleteTextures(accumTexture);
        glDeleteFramebuffers(accumFBO);
        glDeleteTextures(displayTexture);
        glDeleteFramebuffers(displayFBO);
        createFramebuffer(currentWidth, currentHeight);

        // Also recreate bloom buffers
        glDeleteTextures(bloomTexture1);
        glDeleteTextures(bloomTexture2);
        glDeleteFramebuffers(bloomFBO1);
        glDeleteFramebuffers(bloomFBO2);
        createBloomFramebuffers(currentWidth, currentHeight);

        sampleCount = 0;
    }

    private void clearAccumulation() {
        glBindFramebuffer(GL_FRAMEBUFFER, accumFBO);
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        sampleCount = 0;
    }

    private void loadDisplayShader() {
        try {
            String vertexSource = loadResource("/shaders/fullscreen.vert");
            String fragmentSource = loadResource("/shaders/display.glsl");
            displayProgram = new ShaderProgram(vertexSource, fragmentSource);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load display shader", e);
        }
    }

    private void loadPostProcessShaders() {
        try {
            String vertexSource = loadResource("/shaders/fullscreen.vert");

            String postProcessSource = loadResource("/shaders/postprocess.glsl");
            postProcessProgram = new ShaderProgram(vertexSource, postProcessSource);

            String bloomExtractSource = loadResource("/shaders/bloom_extract.glsl");
            bloomExtractProgram = new ShaderProgram(vertexSource, bloomExtractSource);

            String bloomBlurSource = loadResource("/shaders/bloom_blur.glsl");
            bloomBlurProgram = new ShaderProgram(vertexSource, bloomBlurSource);

            System.out.println("Post-processing shaders loaded");
        } catch (Exception e) {
            throw new RuntimeException("Failed to load post-processing shaders", e);
        }
    }

    private void createBloomFramebuffers(int width, int height) {
        // Use half resolution for bloom (performance)
        bloomWidth = Math.max(1, width / 2);
        bloomHeight = Math.max(1, height / 2);

        // Bloom texture 1
        bloomTexture1 = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, bloomTexture1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, bloomWidth, bloomHeight, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        bloomFBO1 = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, bloomFBO1);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, bloomTexture1, 0);

        // Bloom texture 2 (for ping-pong blur)
        bloomTexture2 = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, bloomTexture2);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, bloomWidth, bloomHeight, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        bloomFBO2 = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, bloomFBO2);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, bloomTexture2, 0);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Render bloom effect (extract bright pixels + blur).
     */
    private void renderBloom() {
        if (!postProcessParams.bloomEnabled) {
            // Clear bloom texture if disabled
            glBindFramebuffer(GL_FRAMEBUFFER, bloomFBO1);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            return;
        }

        // Step 1: Extract bright pixels
        glBindFramebuffer(GL_FRAMEBUFFER, bloomFBO1);
        glViewport(0, 0, bloomWidth, bloomHeight);
        glClear(GL_COLOR_BUFFER_BIT);

        bloomExtractProgram.use();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, accumTexture);
        bloomExtractProgram.setUniform("accumTexture", 0);
        bloomExtractProgram.setUniform("sampleCount", Math.max(1, sampleCount));
        bloomExtractProgram.setUniform("threshold", postProcessParams.bloomThreshold);
        bloomExtractProgram.setUniform("softThreshold", 0.5f);

        glBindVertexArray(quadVAO);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);

        // Step 2: Blur passes (ping-pong)
        int blurPasses = postProcessParams.bloomRadius;
        for (int i = 0; i < blurPasses; i++) {
            // Horizontal blur: bloomTexture1 -> bloomTexture2
            glBindFramebuffer(GL_FRAMEBUFFER, bloomFBO2);
            glClear(GL_COLOR_BUFFER_BIT);

            bloomBlurProgram.use();
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, bloomTexture1);
            bloomBlurProgram.setUniform("inputTexture", 0);
            bloomBlurProgram.setUniform("direction", 1.0f, 0.0f);
            bloomBlurProgram.setUniform("resolution", (float) bloomWidth, (float) bloomHeight);

            glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);

            // Vertical blur: bloomTexture2 -> bloomTexture1
            glBindFramebuffer(GL_FRAMEBUFFER, bloomFBO1);
            glClear(GL_COLOR_BUFFER_BIT);

            glBindTexture(GL_TEXTURE_2D, bloomTexture2);
            bloomBlurProgram.setUniform("direction", 0.0f, 1.0f);

            glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Set post-processing parameters.
     */
    public void setPostProcessParams(PostProcessParams params) {
        this.postProcessParams = params;
    }

    /**
     * Get current post-processing parameters.
     */
    public PostProcessParams getPostProcessParams() {
        return postProcessParams;
    }

    // ========================================================================
    // Environment Map
    // ========================================================================

    /**
     * Create a default 1x1 black environment texture.
     */
    private void createDefaultEnvMap() {
        envMapTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, envMapTexture);

        // 1x1 black pixel
        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        pixel.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) -1);
        pixel.flip();

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        MemoryUtil.memFree(pixel);
        glBindTexture(GL_TEXTURE_2D, 0);

        envMapLoaded = false;
    }

    /**
     * Load an environment map from file (supports PNG, JPG, HDR).
     * Uses STB image loading via LWJGL.
     */
    public void loadEnvironmentMap(String filePath) {
        runOnGLThread(() -> {
            try {
                IntBuffer width = MemoryUtil.memAllocInt(1);
                IntBuffer height = MemoryUtil.memAllocInt(1);
                IntBuffer channels = MemoryUtil.memAllocInt(1);

                boolean isHDR = filePath.toLowerCase().endsWith(".hdr");

                // Delete old texture and create new one
                glDeleteTextures(envMapTexture);
                envMapTexture = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, envMapTexture);

                if (isHDR) {
                    // Load HDR as float data
                    FloatBuffer imageData = org.lwjgl.stb.STBImage.stbi_loadf(filePath, width, height, channels, 3);

                    if (imageData == null) {
                        System.err.println("Failed to load HDR: " + filePath);
                        System.err.println("STB Error: " + org.lwjgl.stb.STBImage.stbi_failure_reason());
                        createDefaultEnvMap();
                        MemoryUtil.memFree(width);
                        MemoryUtil.memFree(height);
                        MemoryUtil.memFree(channels);
                        return;
                    }

                    int w = width.get(0);
                    int h = height.get(0);

                    // Use RGB16F for HDR data
                    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB16F, w, h, 0, GL_RGB, GL_FLOAT, imageData);

                    org.lwjgl.stb.STBImage.stbi_image_free(imageData);

                    System.out.println("Loaded HDR environment map: " + filePath + " (" + w + "x" + h + ")");
                } else {
                    // Load LDR (PNG, JPG) as byte data
                    ByteBuffer imageData = org.lwjgl.stb.STBImage.stbi_load(filePath, width, height, channels, 4);

                    if (imageData == null) {
                        System.err.println("Failed to load environment map: " + filePath);
                        System.err.println("STB Error: " + org.lwjgl.stb.STBImage.stbi_failure_reason());
                        createDefaultEnvMap();
                        MemoryUtil.memFree(width);
                        MemoryUtil.memFree(height);
                        MemoryUtil.memFree(channels);
                        return;
                    }

                    int w = width.get(0);
                    int h = height.get(0);

                    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, imageData);

                    org.lwjgl.stb.STBImage.stbi_image_free(imageData);

                    System.out.println("Loaded environment map: " + filePath + " (" + w + "x" + h + ")");
                }

                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

                glGenerateMipmap(GL_TEXTURE_2D);

                MemoryUtil.memFree(width);
                MemoryUtil.memFree(height);
                MemoryUtil.memFree(channels);

                glBindTexture(GL_TEXTURE_2D, 0);

                envMapLoaded = true;

            } catch (Exception e) {
                System.err.println("Error loading environment map: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Load an environment map from resources.
     */
    public void loadEnvironmentMapFromResource(String resourcePath) {
        runOnGLThread(() -> {
            try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
                if (is == null) {
                    System.err.println("Environment map resource not found: " + resourcePath);
                    return;
                }

                byte[] bytes = is.readAllBytes();
                ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
                buffer.put(bytes).flip();

                IntBuffer width = MemoryUtil.memAllocInt(1);
                IntBuffer height = MemoryUtil.memAllocInt(1);
                IntBuffer channels = MemoryUtil.memAllocInt(1);

                ByteBuffer imageData = org.lwjgl.stb.STBImage.stbi_load_from_memory(buffer, width, height, channels, 4);

                MemoryUtil.memFree(buffer);

                if (imageData == null) {
                    System.err.println("Failed to decode environment map: " + resourcePath);
                    MemoryUtil.memFree(width);
                    MemoryUtil.memFree(height);
                    MemoryUtil.memFree(channels);
                    return;
                }

                int w = width.get(0);
                int h = height.get(0);

                glDeleteTextures(envMapTexture);
                envMapTexture = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, envMapTexture);

                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, imageData);

                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

                glGenerateMipmap(GL_TEXTURE_2D);

                org.lwjgl.stb.STBImage.stbi_image_free(imageData);
                MemoryUtil.memFree(width);
                MemoryUtil.memFree(height);
                MemoryUtil.memFree(channels);

                glBindTexture(GL_TEXTURE_2D, 0);

                envMapLoaded = true;
                System.out.println("Loaded environment map: " + resourcePath + " (" + w + "x" + h + ")");

            } catch (Exception e) {
                System.err.println("Error loading environment map: " + e.getMessage());
            }
        });
    }

    /**
     * Clear the environment map (use procedural sky).
     */
    public void clearEnvironmentMap() {
        runOnGLThread(() -> {
            glDeleteTextures(envMapTexture);
            createDefaultEnvMap();
        });
    }

    /**
     * Check if an environment map is loaded.
     */
    public boolean isEnvMapLoaded() {
        return envMapLoaded;
    }

    /**
     * Set environment rotation (in radians).
     */
    public void setEnvRotation(float radians) {
        this.envRotation = radians;
    }

    /**
     * Get environment rotation.
     */
    public float getEnvRotation() {
        return envRotation;
    }

    /**
     * Set environment lighting mix (0 = directional only, 1 = full HDRI).
     */
    public void setEnvLightingMix(float mix) {
        this.envLightingMix = Math.max(0, Math.min(1, mix));
    }

    /**
     * Get environment lighting mix.
     */
    public float getEnvLightingMix() {
        return envLightingMix;
    }

    private void setUniformValue(ShaderProgram program, String name, Object value) {
        if (value instanceof Float f) {
            program.setUniform(name, f);
        } else if (value instanceof Integer i) {
            program.setUniform(name, i);
            // Track renderMode for display shader
            if ("renderMode".equals(name)) {
                currentRenderMode = i;
            }
        } else if (value instanceof float[] arr) {
            switch (arr.length) {
                case 2 -> program.setUniform(name, arr[0], arr[1]);
                case 3 -> program.setUniform(name, arr[0], arr[1], arr[2]);
                case 4 -> program.setUniform(name, arr[0], arr[1], arr[2], arr[3]);
                case 9 -> program.setUniformMatrix3(name, arr);
                case 16 -> program.setUniformMatrix4(name, arr);
                default -> throw new IllegalArgumentException("Unsupported float array length: " + arr.length);
            }
        } else if (value instanceof int[] arr) {
            if (arr.length == 1) {
                program.setUniform(name, arr[0]);
            } else {
                throw new IllegalArgumentException("Unsupported int array length: " + arr.length);
            }
        } else {
            throw new IllegalArgumentException("Unsupported uniform type: " + value.getClass());
        }
    }

    private void flipImageY(float[] image, int width, int height) {
        int rowSize = width * 4;
        int expectedSize = width * height * 4;

        // Safety check: verify array size matches expected dimensions
        // This prevents crashes when viewport resizes during rendering
        if (image.length != expectedSize || width <= 0 || height <= 0) {
            return; // Skip flip if size mismatch (viewport was resized)
        }

        float[] tempRow = new float[rowSize];

        for (int y = 0; y < height / 2; y++) {
            int topOffset = y * rowSize;
            int bottomOffset = (height - 1 - y) * rowSize;

            // Swap rows
            System.arraycopy(image, topOffset, tempRow, 0, rowSize);
            System.arraycopy(image, bottomOffset, image, topOffset, rowSize);
            System.arraycopy(tempRow, 0, image, bottomOffset, rowSize);
        }
    }

    private String loadResource(String path) {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new RuntimeException("Resource not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load resource: " + path, e);
        }
    }

    private void runOnGLThread(Runnable task) {
        if (Thread.currentThread().getName().equals("GLSLEngine-Thread")) {
            task.run();
        } else {
            try {
                glThread.submit(() -> {
                    glfwMakeContextCurrent(window);
                    task.run();
                }).get();
            } catch (Exception e) {
                throw new RuntimeException("GL thread execution failed", e);
            }
        }
    }

    @Override
    public void close() {
        runOnGLThread(() -> {
            for (ShaderProgram program : programs.values()) {
                program.delete();
            }
            if (displayProgram != null) {
                displayProgram.delete();
            }
            if (postProcessProgram != null) {
                postProcessProgram.delete();
            }
            if (bloomExtractProgram != null) {
                bloomExtractProgram.delete();
            }
            if (bloomBlurProgram != null) {
                bloomBlurProgram.delete();
            }

            glDeleteFramebuffers(accumFBO);
            glDeleteTextures(accumTexture);
            glDeleteFramebuffers(displayFBO);
            glDeleteTextures(displayTexture);
            glDeleteFramebuffers(bloomFBO1);
            glDeleteFramebuffers(bloomFBO2);
            glDeleteTextures(bloomTexture1);
            glDeleteTextures(bloomTexture2);
            glDeleteTextures(envMapTexture);
            glDeleteVertexArrays(quadVAO);
            glDeleteBuffers(quadVBO);
            glDeleteBuffers(quadEBO);

            glfwDestroyWindow(window);
            glfwTerminate();
        });

        glThread.shutdown();
        try {
            glThread.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ========================================================================
    // Inner class: ShaderProgram
    // ========================================================================

    public static class ShaderProgram {
        private final int programId;
        private final Map<String, Integer> uniformLocations = new HashMap<>();

        public ShaderProgram(String vertexSource, String fragmentSource) {
            int vertexShader = compileShader(GL_VERTEX_SHADER, vertexSource, "Vertex");
            int fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentSource, "Fragment");

            programId = glCreateProgram();
            glAttachShader(programId, vertexShader);
            glAttachShader(programId, fragmentShader);
            glLinkProgram(programId);

            if (glGetProgrami(programId, GL_LINK_STATUS) == GL_FALSE) {
                String log = glGetProgramInfoLog(programId);
                throw new RuntimeException("Shader program linking failed:\n" + log);
            }

            glDeleteShader(vertexShader);
            glDeleteShader(fragmentShader);
        }

        private int compileShader(int type, String source, String typeName) {
            int shader = glCreateShader(type);
            glShaderSource(shader, source);
            glCompileShader(shader);

            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
                String log = glGetShaderInfoLog(shader);
                throw new RuntimeException(typeName + " shader compilation failed:\n" + log);
            }

            return shader;
        }

        public void use() {
            glUseProgram(programId);
        }

        public int getUniformLocation(String name) {
            return uniformLocations.computeIfAbsent(name, n -> glGetUniformLocation(programId, n));
        }

        public void setUniform(String name, int value) {
            glUniform1i(getUniformLocation(name), value);
        }

        public void setUniform(String name, float value) {
            glUniform1f(getUniformLocation(name), value);
        }

        public void setUniform(String name, float x, float y) {
            glUniform2f(getUniformLocation(name), x, y);
        }

        public void setUniform(String name, float x, float y, float z) {
            glUniform3f(getUniformLocation(name), x, y, z);
        }

        public void setUniform(String name, float x, float y, float z, float w) {
            glUniform4f(getUniformLocation(name), x, y, z, w);
        }

        public void setUniformMatrix3(String name, float[] values) {
            glUniformMatrix3fv(getUniformLocation(name), false, values);
        }

        public void setUniformMatrix4(String name, float[] values) {
            glUniformMatrix4fv(getUniformLocation(name), false, values);
        }

        public void delete() {
            glDeleteProgram(programId);
        }
    }

    // ========================================================================
    // Inner class: PostProcessParams
    // ========================================================================

    /**
     * Post-processing parameters.
     * Controls all visual effects applied after rendering.
     */
    public static class PostProcessParams {
        // Tone mapping
        public int toneMapMode = 0;    // 0=ACES, 1=Reinhard, 2=Filmic, 3=None
        public float exposure = 1.0f;

        // Bloom
        public boolean bloomEnabled = false;
        public float bloomIntensity = 0.5f;
        public float bloomThreshold = 1.0f;
        public int bloomRadius = 3;      // Number of blur passes

        // Chromatic Aberration
        public boolean chromaticAberrationEnabled = false;
        public float chromaticAberrationIntensity = 0.005f;

        // Vignette
        public boolean vignetteEnabled = false;
        public float vignetteIntensity = 0.3f;
        public float vignetteSoftness = 0.5f;

        // Film Grain
        public boolean filmGrainEnabled = false;
        public float filmGrainIntensity = 0.03f;

        // Sharpening
        public boolean sharpenEnabled = false;
        public float sharpenIntensity = 0.3f;

        public float saturation = 1.0f;    // 0.0 - 2.0 (default 1.0)
        
        // Color Grading
        public int colorGradingMode = 0; // 0=None, 1=Cinema, 2=Vintage, 3=Matrix, 4=Neon, 5=B&W
        public float colorGradingIntensity = 1.0f;

        /**
         * Create default parameters (no effects).
         */
        public PostProcessParams() {}

        /**
         * Create a copy of these parameters.
         */
        public PostProcessParams copy() {
            PostProcessParams copy = new PostProcessParams();
            copy.toneMapMode = this.toneMapMode;
            copy.exposure = this.exposure;
            copy.bloomEnabled = this.bloomEnabled;
            copy.bloomIntensity = this.bloomIntensity;
            copy.bloomThreshold = this.bloomThreshold;
            copy.bloomRadius = this.bloomRadius;
            copy.chromaticAberrationEnabled = this.chromaticAberrationEnabled;
            copy.chromaticAberrationIntensity = this.chromaticAberrationIntensity;
            copy.vignetteEnabled = this.vignetteEnabled;
            copy.vignetteIntensity = this.vignetteIntensity;
            copy.vignetteSoftness = this.vignetteSoftness;
            copy.filmGrainEnabled = this.filmGrainEnabled;
            copy.filmGrainIntensity = this.filmGrainIntensity;
            copy.sharpenEnabled = this.sharpenEnabled;
            copy.sharpenIntensity = this.sharpenIntensity;
            copy.saturation = this.saturation;
            copy.colorGradingMode = this.colorGradingMode;
            copy.colorGradingIntensity = this.colorGradingIntensity;
            return copy;
        }

        /**
         * Apply "Cinematic" preset.
         */
        public void applyCinematicPreset() {
            toneMapMode = 2;  // Filmic
            exposure = 1.1f;
            bloomEnabled = true;
            bloomIntensity = 0.4f;
            bloomThreshold = 0.8f;
            bloomRadius = 4;
            chromaticAberrationEnabled = true;
            chromaticAberrationIntensity = 0.003f;
            vignetteEnabled = true;
            vignetteIntensity = 0.4f;
            vignetteSoftness = 0.6f;
            filmGrainEnabled = true;
            filmGrainIntensity = 0.02f;
            sharpenEnabled = false;
            saturation = 1.1f;
            colorGradingMode = 1; // Cinema
            colorGradingIntensity = 0.8f;
        }

        /**
         * Apply "Clean" preset.
         */
        public void applyCleanPreset() {
            toneMapMode = 0;  // ACES
            exposure = 1.0f;
            bloomEnabled = true;
            bloomIntensity = 0.2f;
            bloomThreshold = 1.2f;
            bloomRadius = 2;
            chromaticAberrationEnabled = false;
            vignetteEnabled = false;
            filmGrainEnabled = false;
            sharpenEnabled = true;
            sharpenIntensity = 0.2f;
            saturation = 1.0f;
            colorGradingMode = 0; // None
        }

        /**
         * Apply "Vibrant" preset.
         */
        public void applyVibrantPreset() {
            toneMapMode = 0;  // ACES
            exposure = 1.2f;
            bloomEnabled = true;
            bloomIntensity = 0.6f;
            bloomThreshold = 0.7f;
            bloomRadius = 5;
            chromaticAberrationEnabled = true;
            chromaticAberrationIntensity = 0.008f;
            vignetteEnabled = true;
            vignetteIntensity = 0.2f;
            vignetteSoftness = 0.4f;
            filmGrainEnabled = false;
            sharpenEnabled = false;
            saturation = 1.4f;
            colorGradingMode = 4; // Neon
            colorGradingIntensity = 0.6f;
        }

        /**
         * Reset to defaults (no effects).
         */
        public void reset() {
            toneMapMode = 0;
            exposure = 1.0f;
            bloomEnabled = false;
            bloomIntensity = 0.5f;
            bloomThreshold = 1.0f;
            bloomRadius = 3;
            chromaticAberrationEnabled = false;
            chromaticAberrationIntensity = 0.005f;
            vignetteEnabled = false;
            vignetteIntensity = 0.3f;
            vignetteSoftness = 0.5f;
            filmGrainEnabled = false;
            filmGrainIntensity = 0.03f;
            sharpenEnabled = false;
            sharpenIntensity = 0.3f;
            saturation = 1.0f;
            colorGradingMode = 0;
            colorGradingIntensity = 1.0f;
        }
    }
}
