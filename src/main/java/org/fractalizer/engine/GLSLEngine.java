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

    // Display shader (for tone mapping)
    private ShaderProgram displayProgram;

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
        loadDisplayShader();

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
     * Applies tone mapping and gamma correction.
     */
    public float[] readImage() {
        float[] result = new float[currentWidth * currentHeight * 4];

        runOnGLThread(() -> {
            // Render to display FBO (not default framebuffer - hidden windows may have issues)
            glBindFramebuffer(GL_FRAMEBUFFER, displayFBO);
            glViewport(0, 0, currentWidth, currentHeight);
            glClear(GL_COLOR_BUFFER_BIT);

            displayProgram.use();
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, accumTexture);
            displayProgram.setUniform("accumTexture", 0);
            displayProgram.setUniform("sampleCount", Math.max(1, sampleCount));
            displayProgram.setUniform("renderMode", currentRenderMode);

            glBindVertexArray(quadVAO);
            glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);

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

            glDeleteFramebuffers(accumFBO);
            glDeleteTextures(accumTexture);
            glDeleteFramebuffers(displayFBO);
            glDeleteTextures(displayTexture);
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

        public void delete() {
            glDeleteProgram(programId);
        }
    }
}
