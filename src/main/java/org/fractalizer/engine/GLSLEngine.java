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
 */
public class GLSLEngine implements AutoCloseable {

    private long window;
    private int accumFBO, accumTexture;
    private int displayFBO, displayTexture;
    private int currentWidth, currentHeight;
    private int bloomFBO1, bloomFBO2, bloomTexture1, bloomTexture2, bloomWidth, bloomHeight;
    private int lensDirtTexture, paletteTexture;
    private int varianceTexture, varianceFBO;
    private boolean adaptiveSamplingEnabled = false;
    private int envMapTexture;
    private boolean envMapLoaded = false;
    private float envRotation = 0.0f, envLightingMix = 0.5f;
    private int envMarginalCDFTexture, envConditionalCDFTexture, envMapWidth, envMapHeight;
    private float envTotalLuminance;
    private boolean envCDFReady = false;
    private int quadVAO, quadVBO, quadEBO;
    private final Map<String, ShaderProgram> programs = new HashMap<>();
    private String activeProgram;
    private int sampleCount = 0, maxSamples = 10000;
    private boolean needsReset = true;
    private int currentRenderMode = 0;
    private ShaderProgram displayProgram, postProcessProgram, bloomExtractProgram, bloomBlurProgram, evaluatorProgram;
    private PostProcessParams postProcessParams = new PostProcessParams();
    private final ExecutorService glThread;
    private volatile boolean initialized = false;
    private String renderer, glVersion, glslVersion;

    public GLSLEngine() { this(1280, 720); }

    public GLSLEngine(int width, int height) {
        this.currentWidth = width; this.currentHeight = height;
        this.glThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "GLSLEngine-Thread");
            t.setDaemon(true);
            return t;
        });
        try { glThread.submit(this::initialize).get(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void initialize() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException();
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(currentWidth, currentHeight, "Fractaliz3r GL Context", NULL, NULL);
        if (window == NULL) throw new RuntimeException();
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        renderer = glGetString(GL_RENDERER);
        glVersion = glGetString(GL_VERSION);
        glslVersion = glGetString(GL_SHADING_LANGUAGE_VERSION);
        createFullscreenQuad();
        createFramebuffer(currentWidth, currentHeight);
        createBloomFramebuffers(currentWidth, currentHeight);
        createLensDirtTexture();
        createDefaultEnvMap();
        createDefaultCDFTextures();
        createDefaultPaletteTexture();
        loadDisplayShader();
        loadPostProcessShaders();
        initialized = true;
    }

    private String stripVersion(String source) {
        return source.replaceAll("#version\\s+\\d+\\s+\\w+", "").trim();
    }

    public void loadEvaluatorShader(String fractalShaderPath) {
        runOnGLThread(() -> {
            try {
                String vertexSource = loadResource("/shaders/fullscreen.vert");
                String commonSource = stripVersion(loadResource("/shaders/common.glsl"));
                String fractalSource = stripVersion(loadResource(fractalShaderPath));
                String evaluatorSource = stripVersion(loadResource("/shaders/evaluator.glsl"));
                String fragmentSource = "#version 430 core\n" + commonSource + "\n" + fractalSource + "\n" + evaluatorSource;
                if (evaluatorProgram != null) evaluatorProgram.delete();
                evaluatorProgram = new ShaderProgram(vertexSource, fragmentSource);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    public float[] evaluateSlice(Map<String, Object> uniforms, float zPos, float boundsHalf, int res) {
        float[] result = new float[res * res * 4];
        runOnGLThread(() -> {
            if (evaluatorProgram == null) throw new IllegalStateException();
            if (res != currentWidth || res != currentHeight) {
                glBindTexture(GL_TEXTURE_2D, displayTexture);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, res, res, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
            }
            glBindFramebuffer(GL_FRAMEBUFFER, displayFBO);
            glViewport(0, 0, res, res);
            glClear(GL_COLOR_BUFFER_BIT);
            evaluatorProgram.use();
            evaluatorProgram.setUniform("zPos", zPos);
            evaluatorProgram.setUniform("boundsHalf", boundsHalf);
            for (Map.Entry<String, Object> entry : uniforms.entrySet()) { setUniformValue(evaluatorProgram, entry.getKey(), entry.getValue()); }
            glBindVertexArray(quadVAO);
            glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
            glFinish();
            
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            FloatBuffer buffer = MemoryUtil.memAllocFloat(result.length);
            glReadPixels(0, 0, res, res, GL_RGBA, GL_FLOAT, buffer);
            buffer.get(result);
            MemoryUtil.memFree(buffer);
            glPixelStorei(GL_PACK_ALIGNMENT, 4);

            if (res != currentWidth || res != currentHeight) {
                glBindTexture(GL_TEXTURE_2D, displayTexture);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, currentWidth, currentHeight, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        });
        return result;
    }

    public void loadFractalShader(String name, String fractalShaderPath) {
        runOnGLThread(() -> {
            try {
                String vertexSource = loadResource("/shaders/fullscreen.vert");
                String commonSource = stripVersion(loadResource("/shaders/common.glsl"));
                String fractalSource = stripVersion(loadResource(fractalShaderPath));
                String raytracerSource = stripVersion(loadResource("/shaders/raytracer.glsl"));
                String fragmentSource = "#version 430 core\n" + commonSource + "\n" + fractalSource + "\n" + raytracerSource;
                ShaderProgram program = new ShaderProgram(vertexSource, fragmentSource);
                programs.put(name, program);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    public void setActiveProgram(String name) {
        if (!programs.containsKey(name)) throw new IllegalArgumentException();
        if (!name.equals(activeProgram)) { activeProgram = name; needsReset = true; }
    }

    public void resize(int width, int height) {
        if (width != currentWidth || height != currentHeight) {
            runOnGLThread(() -> { currentWidth = width; currentHeight = height; recreateFramebuffer(); needsReset = true; });
        }
    }

    public void resetAccumulation() { needsReset = true; }

    public void renderSample(Map<String, Object> uniforms) {
        runOnGLThread(() -> {
            if (activeProgram == null) throw new IllegalStateException();
            if (needsReset) { clearAccumulation(); needsReset = false; }
            if (sampleCount >= maxSamples) return;
            ShaderProgram program = programs.get(activeProgram);
            glBindFramebuffer(GL_FRAMEBUFFER, accumFBO);
            glViewport(0, 0, currentWidth, currentHeight);
            glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE);
            program.use();
            program.setUniform("resolution", (float) currentWidth, (float) currentHeight);
            program.setUniform("sampleIndex", sampleCount);
            program.setUniform("time", (float) glfwGetTime());
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, envMapTexture);
            program.setUniform("envMap", 0); program.setUniform("useEnvMap", envMapLoaded ? 1 : 0);
            program.setUniform("envRotation", envRotation); program.setUniform("envLightingMix", envLightingMix);
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, paletteTexture);
            program.setUniform("paletteTexture", 1);
            if (envCDFReady) {
                glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, envMarginalCDFTexture); program.setUniform("envMarginalCDF", 3);
                glActiveTexture(GL_TEXTURE4); glBindTexture(GL_TEXTURE_2D, envConditionalCDFTexture); program.setUniform("envConditionalCDF", 4);
                program.setUniform("envTotalLuminance", envTotalLuminance);
                program.setUniform("envMapWidth", envMapWidth); program.setUniform("envMapHeight", envMapHeight);
            } else {
                program.setUniform("envMapWidth", 0); program.setUniform("envMapHeight", 0); program.setUniform("envTotalLuminance", 0.0f);
            }
            if (adaptiveSamplingEnabled) glBindImageTexture(5, varianceTexture, 0, false, 0, GL_READ_WRITE, GL_RGBA32F);
            for (Map.Entry<String, Object> entry : uniforms.entrySet()) { setUniformValue(program, entry.getKey(), entry.getValue()); }
            glBindVertexArray(quadVAO); glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
            if (adaptiveSamplingEnabled) glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            glDisable(GL_BLEND); glBindFramebuffer(GL_FRAMEBUFFER, 0);
            sampleCount++;
        });
    }

    public void glSync() { runOnGLThread(GL43::glFinish); }

    public void renderSamples(Map<String, Object> uniforms, int count) { for (int i = 0; i < count; i++) renderSample(uniforms); }

    public int getSampleCount() { return sampleCount; }
    public void setMaxSamples(int max) { this.maxSamples = max; }

    public float[] readImage() {
        float[] result = new float[currentWidth * currentHeight * 4];
        runOnGLThread(() -> {
            if (adaptiveSamplingEnabled) glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT);
            renderBloom();
            glBindFramebuffer(GL_FRAMEBUFFER, displayFBO); glViewport(0, 0, currentWidth, currentHeight); glClear(GL_COLOR_BUFFER_BIT);
            postProcessProgram.use();
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, accumTexture); postProcessProgram.setUniform("accumTexture", 0);
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, bloomTexture1); postProcessProgram.setUniform("bloomTexture", 1);
            glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, lensDirtTexture); postProcessProgram.setUniform("lensDirtTexture", 2);
            glActiveTexture(GL_TEXTURE5); glBindTexture(GL_TEXTURE_2D, varianceTexture); postProcessProgram.setUniform("varianceTex", 5);
            postProcessProgram.setUniform("adaptiveSampling", adaptiveSamplingEnabled ? 1 : 0);
            postProcessProgram.setUniform("sampleCount", Math.max(1, sampleCount));
            postProcessProgram.setUniform("renderMode", currentRenderMode);
            postProcessProgram.setUniform("resolution", (float) currentWidth, (float) currentHeight);
            PostProcessParams pp = postProcessParams;
            postProcessProgram.setUniform("toneMapMode", pp.toneMapMode);
            postProcessProgram.setUniform("exposure", pp.exposure + pp.audioDeltaExposure);
            postProcessProgram.setUniform("bloomEnabled", pp.bloomEnabled ? 1 : 0);
            postProcessProgram.setUniform("bloomIntensity", pp.bloomIntensity);
            postProcessProgram.setUniform("bloomThreshold", pp.bloomThreshold);
            postProcessProgram.setUniform("chromaticAberrationEnabled", (pp.chromaticAberrationEnabled || pp.audioForceCA) ? 1 : 0);
            postProcessProgram.setUniform("chromaticAberrationIntensity", pp.chromaticAberrationIntensity + pp.audioDeltaCA);
            postProcessProgram.setUniform("vignetteEnabled", (pp.vignetteEnabled || pp.audioForceVignette) ? 1 : 0);
            postProcessProgram.setUniform("vignetteIntensity", pp.vignetteIntensity + pp.audioDeltaVignette);
            postProcessProgram.setUniform("vignetteSoftness", (pp.vignetteEnabled || !pp.audioForceVignette) ? pp.vignetteSoftness : 0.6f);
            postProcessProgram.setUniform("filmGrainEnabled", pp.filmGrainEnabled ? 1 : 0);
            postProcessProgram.setUniform("filmGrainIntensity", pp.filmGrainIntensity);
            postProcessProgram.setUniform("filmGrainTime", (float) glfwGetTime());
            postProcessProgram.setUniform("sharpenEnabled", pp.sharpenEnabled ? 1 : 0);
            postProcessProgram.setUniform("sharpenIntensity", pp.sharpenIntensity);
            postProcessProgram.setUniform("saturation", pp.saturation + pp.audioDeltaSaturation);
            postProcessProgram.setUniform("lensEffectsEnabled", pp.lensEffectsEnabled ? 1 : 0);
            postProcessProgram.setUniform("lensDirtIntensity", pp.lensDirtIntensity);
            postProcessProgram.setUniform("starburstIntensity", pp.starburstIntensity);
            postProcessProgram.setUniform("colorGradingMode", pp.colorGradingMode);
            postProcessProgram.setUniform("colorGradingIntensity", pp.colorGradingIntensity);
            glBindVertexArray(quadVAO); glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
            glFinish();
            FloatBuffer buffer = MemoryUtil.memAllocFloat(result.length);
            glReadPixels(0, 0, currentWidth, currentHeight, GL_RGBA, GL_FLOAT, buffer);
            buffer.get(result); MemoryUtil.memFree(buffer);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        });
        flipImageY(result, currentWidth, currentHeight);
        return result;
    }

    public float[] readRawImage() {
        float[] result = new float[currentWidth * currentHeight * 4];
        runOnGLThread(() -> {
            glBindFramebuffer(GL_FRAMEBUFFER, accumFBO);
            FloatBuffer buffer = MemoryUtil.memAllocFloat(result.length);
            glReadPixels(0, 0, currentWidth, currentHeight, GL_RGBA, GL_FLOAT, buffer);
            buffer.get(result); MemoryUtil.memFree(buffer);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        });
        int samples = Math.max(1, sampleCount);
        for (int i = 0; i < result.length; i++) result[i] /= samples;
        flipImageY(result, currentWidth, currentHeight);
        return result;
    }

    public float readDepthAt(int x, int y) {
        if (x < 0 || x >= currentWidth || y < 0 || y >= currentHeight) return -1.0f;
        int glY = currentHeight - 1 - y;
        float[] depth = new float[1];
        runOnGLThread(() -> {
            glBindFramebuffer(GL_FRAMEBUFFER, accumFBO);
            FloatBuffer buffer = MemoryUtil.memAllocFloat(4);
            glReadPixels(x, glY, 1, 1, GL_RGBA, GL_FLOAT, buffer);
            depth[0] = buffer.get(3) / Math.max(1, sampleCount);
            MemoryUtil.memFree(buffer);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        });
        return depth[0];
    }

    public int getWidth() { return currentWidth; }
    public int getHeight() { return currentHeight; }
    public String getRenderer() { return renderer; }
    public String getGLVersion() { return glVersion; }
    public String getGLSLVersion() { return glslVersion; }

    private void createFullscreenQuad() {
        float[] vertices = { -1.0f, -1.0f, 0.0f, 0.0f, 1.0f, -1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, -1.0f, 1.0f, 0.0f, 1.0f };
        int[] indices = {0, 1, 2, 2, 3, 0};
        quadVAO = glGenVertexArrays(); quadVBO = glGenBuffers(); quadEBO = glGenBuffers();
        glBindVertexArray(quadVAO);
        glBindBuffer(GL_ARRAY_BUFFER, quadVBO); glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, quadEBO); glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0); glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8); glEnableVertexAttribArray(1);
        glBindVertexArray(0);
    }

    private void createFramebuffer(int width, int height) {
        accumTexture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, accumTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, width, height, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        accumFBO = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, accumFBO);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, accumTexture, 0);
        displayTexture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, displayTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, width, height, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        displayFBO = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, displayFBO);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, displayTexture, 0);
        varianceTexture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, varianceTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, width, height, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        varianceFBO = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, varianceFBO);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, varianceTexture, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private void recreateFramebuffer() {
        glDeleteTextures(accumTexture); glDeleteFramebuffers(accumFBO);
        glDeleteTextures(displayTexture); glDeleteFramebuffers(displayFBO);
        glDeleteTextures(varianceTexture); glDeleteFramebuffers(varianceFBO);
        createFramebuffer(currentWidth, currentHeight);
        glDeleteTextures(bloomTexture1); glDeleteTextures(bloomTexture2);
        glDeleteFramebuffers(bloomFBO1); glDeleteFramebuffers(bloomFBO2);
        createBloomFramebuffers(currentWidth, currentHeight);
        sampleCount = 0;
    }

    private void clearAccumulation() {
        glBindFramebuffer(GL_FRAMEBUFFER, accumFBO); glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, varianceFBO); glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, 0); sampleCount = 0;
    }

    private void loadDisplayShader() {
        try { displayProgram = new ShaderProgram(loadResource("/shaders/fullscreen.vert"), loadResource("/shaders/display.glsl")); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private void loadPostProcessShaders() {
        try {
            String v = loadResource("/shaders/fullscreen.vert");
            postProcessProgram = new ShaderProgram(v, loadResource("/shaders/postprocess.glsl"));
            bloomExtractProgram = new ShaderProgram(v, loadResource("/shaders/bloom_extract.glsl"));
            bloomBlurProgram = new ShaderProgram(v, loadResource("/shaders/bloom_blur.glsl"));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void createBloomFramebuffers(int width, int height) {
        bloomWidth = Math.max(1, width / 2); bloomHeight = Math.max(1, height / 2);
        bloomTexture1 = glGenTextures(); glBindTexture(GL_TEXTURE_2D, bloomTexture1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, bloomWidth, bloomHeight, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        bloomFBO1 = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, bloomFBO1);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, bloomTexture1, 0);
        bloomTexture2 = glGenTextures(); glBindTexture(GL_TEXTURE_2D, bloomTexture2);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, bloomWidth, bloomHeight, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        bloomFBO2 = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, bloomFBO2);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, bloomTexture2, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private void createLensDirtTexture() {
        int size = 512; ByteBuffer buffer = MemoryUtil.memAlloc(size * size * 4); Random rand = new Random(12345);
        for (int i = 0; i < size * size; i++) {
            float n = rand.nextFloat(); float val = (float) Math.pow(n, 10.0) * 0.5f + (float) Math.pow(n, 30.0) * 0.5f + (float) Math.pow(n, 2.0) * 0.1f;
            byte b = (byte) (Math.min(1.0f, val) * 255); buffer.put(b).put(b).put(b).put((byte)255);
        }
        buffer.flip(); lensDirtTexture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, lensDirtTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        MemoryUtil.memFree(buffer); glBindTexture(GL_TEXTURE_2D, 0);
    }

    private void renderBloom() {
        if (!postProcessParams.bloomEnabled) {
            glBindFramebuffer(GL_FRAMEBUFFER, bloomFBO1); glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT);
            glBindFramebuffer(GL_FRAMEBUFFER, 0); return;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, bloomFBO1); glViewport(0, 0, bloomWidth, bloomHeight); glClear(GL_COLOR_BUFFER_BIT);
        bloomExtractProgram.use();
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, accumTexture); bloomExtractProgram.setUniform("accumTexture", 0);
        bloomExtractProgram.setUniform("sampleCount", Math.max(1, sampleCount)); bloomExtractProgram.setUniform("threshold", postProcessParams.bloomThreshold); bloomExtractProgram.setUniform("softThreshold", 0.5f);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, varianceTexture); bloomExtractProgram.setUniform("varianceTex", 1); bloomExtractProgram.setUniform("adaptiveSampling", adaptiveSamplingEnabled ? 1 : 0);
        glBindVertexArray(quadVAO); glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
        for (int i = 0; i < postProcessParams.bloomRadius; i++) {
            glBindFramebuffer(GL_FRAMEBUFFER, bloomFBO2); glClear(GL_COLOR_BUFFER_BIT);
            bloomBlurProgram.use(); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, bloomTexture1); bloomBlurProgram.setUniform("inputTexture", 0);
            bloomBlurProgram.setUniform("direction", 1.0f, 0.0f); bloomBlurProgram.setUniform("resolution", (float) bloomWidth, (float) bloomHeight);
            glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
            glBindFramebuffer(GL_FRAMEBUFFER, bloomFBO1); glClear(GL_COLOR_BUFFER_BIT);
            glBindTexture(GL_TEXTURE_2D, bloomTexture2); bloomBlurProgram.setUniform("direction", 0.0f, 1.0f);
            glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public void setPostProcessParams(PostProcessParams params) { this.postProcessParams = params; }
    public PostProcessParams getPostProcessParams() { return postProcessParams; }
    public void setAdaptiveSamplingEnabled(boolean enabled) { this.adaptiveSamplingEnabled = enabled; }

    private void createDefaultEnvMap() {
        envMapTexture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, envMapTexture);
        ByteBuffer pixel = MemoryUtil.memAlloc(4); pixel.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) -1).flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        MemoryUtil.memFree(pixel); glBindTexture(GL_TEXTURE_2D, 0); envMapLoaded = false;
    }

    private void createDefaultPaletteTexture() {
        paletteTexture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, paletteTexture);
        FloatBuffer data = MemoryUtil.memAllocFloat(256 * 3); for (int i = 0; i < 256; i++) { float t = i / 255.0f; data.put(t).put(t).put(t); } data.flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB32F, 256, 1, 0, GL_RGB, GL_FLOAT, data);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        MemoryUtil.memFree(data); glBindTexture(GL_TEXTURE_2D, 0);
    }

    public void updatePaletteTexture(float[] rgbData, int resolution) {
        runOnGLThread(() -> {
            glBindTexture(GL_TEXTURE_2D, paletteTexture);
            FloatBuffer data = MemoryUtil.memAllocFloat(rgbData.length); data.put(rgbData).flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB32F, resolution, 1, 0, GL_RGB, GL_FLOAT, data);
            MemoryUtil.memFree(data); glBindTexture(GL_TEXTURE_2D, 0);
        });
    }

    public void loadEnvironmentMap(String filePath) {
        runOnGLThread(() -> {
            try {
                IntBuffer width = MemoryUtil.memAllocInt(1), height = MemoryUtil.memAllocInt(1), channels = MemoryUtil.memAllocInt(1);
                boolean isHDR = filePath.toLowerCase().endsWith(".hdr");
                org.lwjgl.stb.STBImage.stbi_set_flip_vertically_on_load(true);
                glDeleteTextures(envMapTexture); envMapTexture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, envMapTexture);
                if (isHDR) {
                    FloatBuffer imageData = org.lwjgl.stb.STBImage.stbi_loadf(filePath, width, height, channels, 3);
                    if (imageData == null) { createDefaultEnvMap(); return; }
                    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB16F, width.get(0), height.get(0), 0, GL_RGB, GL_FLOAT, imageData);
                    org.lwjgl.stb.STBImage.stbi_image_free(imageData);
                } else {
                    ByteBuffer imageData = org.lwjgl.stb.STBImage.stbi_load(filePath, width, height, channels, 4);
                    if (imageData == null) { createDefaultEnvMap(); return; }
                    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width.get(0), height.get(0), 0, GL_RGBA, GL_UNSIGNED_BYTE, imageData);
                    org.lwjgl.stb.STBImage.stbi_image_free(imageData);
                }
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glGenerateMipmap(GL_TEXTURE_2D); org.lwjgl.stb.STBImage.stbi_set_flip_vertically_on_load(false);
                int w = width.get(0), h = height.get(0); MemoryUtil.memFree(width); MemoryUtil.memFree(height); MemoryUtil.memFree(channels);
                glBindTexture(GL_TEXTURE_2D, 0); envMapLoaded = true; buildEnvironmentCDF(w, h);
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    public void loadEnvironmentMapFromResource(String resourcePath) {
        runOnGLThread(() -> {
            try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
                if (is == null) return;
                byte[] bytes = is.readAllBytes(); ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length); buffer.put(bytes).flip();
                IntBuffer width = MemoryUtil.memAllocInt(1), height = MemoryUtil.memAllocInt(1), channels = MemoryUtil.memAllocInt(1);
                org.lwjgl.stb.STBImage.stbi_set_flip_vertically_on_load(true);
                ByteBuffer imageData = org.lwjgl.stb.STBImage.stbi_load_from_memory(buffer, width, height, channels, 4);
                MemoryUtil.memFree(buffer); if (imageData == null) return;
                glDeleteTextures(envMapTexture); envMapTexture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, envMapTexture);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width.get(0), height.get(0), 0, GL_RGBA, GL_UNSIGNED_BYTE, imageData);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glGenerateMipmap(GL_TEXTURE_2D); org.lwjgl.stb.STBImage.stbi_set_flip_vertically_on_load(false);
                org.lwjgl.stb.STBImage.stbi_image_free(imageData);
                int w = width.get(0), h = height.get(0); MemoryUtil.memFree(width); MemoryUtil.memFree(height); MemoryUtil.memFree(channels);
                glBindTexture(GL_TEXTURE_2D, 0); envMapLoaded = true; buildEnvironmentCDF(w, h);
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    public void clearEnvironmentMap() {
        runOnGLThread(() -> {
            glDeleteTextures(envMapTexture); createDefaultEnvMap();
            glDeleteTextures(envMarginalCDFTexture); glDeleteTextures(envConditionalCDFTexture); createDefaultCDFTextures();
        });
    }

    private void createDefaultCDFTextures() {
        FloatBuffer pixel = MemoryUtil.memAllocFloat(1); pixel.put(1.0f).flip();
        envMarginalCDFTexture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, envMarginalCDFTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, 1, 1, 0, GL_RED, GL_FLOAT, pixel);
        envConditionalCDFTexture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, envConditionalCDFTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, 1, 1, 0, GL_RED, GL_FLOAT, pixel);
        MemoryUtil.memFree(pixel); glBindTexture(GL_TEXTURE_2D, 0); envCDFReady = false;
    }

    private void buildEnvironmentCDF(int width, int height) {
        this.envMapWidth = width; this.envMapHeight = height;
        glBindTexture(GL_TEXTURE_2D, envMapTexture); FloatBuffer pixels = MemoryUtil.memAllocFloat(width * height * 3); glGetTexImage(GL_TEXTURE_2D, 0, GL_RGB, GL_FLOAT, pixels);
        float[] rowWeights = new float[height], conditionalCDF = new float[width * height]; float totalWeight = 0.0f;
        for (int row = 0; row < height; row++) {
            float sinTheta = (float) Math.sin(Math.PI * (row + 0.5f) / height); float rowSum = 0.0f;
            for (int col = 0; col < width; col++) {
                int i = (row * width + col) * 3; float lum = 0.2126f * pixels.get(i) + 0.7152f * pixels.get(i+1) + 0.0722f * pixels.get(i+2);
                rowSum += lum * sinTheta; conditionalCDF[row * width + col] = rowSum;
            }
            if (rowSum > 0.0f) for (int col = 0; col < width; col++) conditionalCDF[row * width + col] /= rowSum;
            else for (int col = 0; col < width; col++) conditionalCDF[row * width + col] = (float)(col + 1) / width;
            rowWeights[row] = rowSum; totalWeight += rowSum;
        }
        MemoryUtil.memFree(pixels); this.envTotalLuminance = totalWeight;
        float[] marginalCDF = new float[height]; float cumulative = 0.0f;
        for (int row = 0; row < height; row++) { cumulative += rowWeights[row]; marginalCDF[row] = (totalWeight > 0.0f) ? cumulative / totalWeight : (float)(row + 1) / height; }
        glDeleteTextures(envMarginalCDFTexture); envMarginalCDFTexture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, envMarginalCDFTexture);
        FloatBuffer mBuf = MemoryUtil.memAllocFloat(height); mBuf.put(marginalCDF).flip(); glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, 1, height, 0, GL_RED, GL_FLOAT, mBuf); MemoryUtil.memFree(mBuf);
        glDeleteTextures(envConditionalCDFTexture); envConditionalCDFTexture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, envConditionalCDFTexture);
        FloatBuffer cBuf = MemoryUtil.memAllocFloat(width * height); cBuf.put(conditionalCDF).flip(); glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, width, height, 0, GL_RED, GL_FLOAT, cBuf); MemoryUtil.memFree(cBuf);
        glBindTexture(GL_TEXTURE_2D, 0); envCDFReady = true;
    }

    public boolean isEnvMapLoaded() { return envMapLoaded; }
    public void setEnvRotation(float r) { this.envRotation = r; }
    public float getEnvRotation() { return envRotation; }
    public void setEnvLightingMix(float m) { this.envLightingMix = Math.max(0, Math.min(1, m)); }
    public float getEnvLightingMix() { return envLightingMix; }

    private void setUniformValue(ShaderProgram program, String name, Object value) {
        if (value instanceof Float f) program.setUniform(name, f);
        else if (value instanceof Integer i) { program.setUniform(name, i); if ("renderMode".equals(name)) currentRenderMode = i; }
        else if (value instanceof float[] arr) {
            switch (arr.length) {
                case 2 -> program.setUniform(name, arr[0], arr[1]);
                case 3 -> program.setUniform(name, arr[0], arr[1], arr[2]);
                case 4 -> program.setUniform(name, arr[0], arr[1], arr[2], arr[3]);
                case 9 -> program.setUniformMatrix3(name, arr);
                case 16 -> program.setUniformMatrix4(name, arr);
                default -> program.setUniform1fv(name, arr);
            }
        } else if (value instanceof int[] arr) { if (arr.length == 1) program.setUniform(name, arr[0]); }
    }

    private void flipImageY(float[] image, int width, int height) {
        int rowSize = width * 4; if (image.length != width * height * 4 || width <= 0 || height <= 0) return;
        float[] tempRow = new float[rowSize];
        for (int y = 0; y < height / 2; y++) {
            int top = y * rowSize, bot = (height - 1 - y) * rowSize;
            System.arraycopy(image, top, tempRow, 0, rowSize); System.arraycopy(image, bot, image, top, rowSize); System.arraycopy(tempRow, 0, image, bot, rowSize);
        }
    }

    private String loadResource(String path) {
        try (InputStream is = getClass().getResourceAsStream(path)) { if (is == null) throw new RuntimeException(path); return new String(is.readAllBytes(), StandardCharsets.UTF_8); }
        catch (IOException e) { throw new RuntimeException(e); }
    }

    private void runOnGLThread(Runnable task) {
        if (Thread.currentThread().getName().equals("GLSLEngine-Thread")) task.run();
        else { try { glThread.submit(() -> { glfwMakeContextCurrent(window); task.run(); }).get(); } catch (Exception e) { throw new RuntimeException(e); } }
    }

    @Override
    public void close() {
        runOnGLThread(() -> {
            for (ShaderProgram p : programs.values()) p.delete();
            if (displayProgram != null) displayProgram.delete();
            if (postProcessProgram != null) postProcessProgram.delete();
            if (bloomExtractProgram != null) bloomExtractProgram.delete();
            if (bloomBlurProgram != null) bloomBlurProgram.delete();
            if (evaluatorProgram != null) evaluatorProgram.delete();
            glDeleteFramebuffers(accumFBO); glDeleteTextures(accumTexture);
            glDeleteFramebuffers(displayFBO); glDeleteTextures(displayTexture);
            glDeleteFramebuffers(varianceFBO); glDeleteTextures(varianceTexture);
            glDeleteFramebuffers(bloomFBO1); glDeleteFramebuffers(bloomFBO2);
            glDeleteTextures(bloomTexture1); glDeleteTextures(bloomTexture2);
            glDeleteTextures(envMapTexture); glDeleteTextures(envMarginalCDFTexture); glDeleteTextures(envConditionalCDFTexture);
            glDeleteTextures(paletteTexture); glDeleteVertexArrays(quadVAO); glDeleteBuffers(quadVBO); glDeleteBuffers(quadEBO);
            glfwDestroyWindow(window); glfwTerminate();
        });
        glThread.shutdown();
        try { glThread.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static class ShaderProgram {
        private final int programId; private final Map<String, Integer> uniformLocations = new HashMap<>();
        public ShaderProgram(String vertexSource, String fragmentSource) {
            int vs = compileShader(GL_VERTEX_SHADER, vertexSource, "Vertex");
            int fs = compileShader(GL_FRAGMENT_SHADER, fragmentSource, "Fragment");
            programId = glCreateProgram(); glAttachShader(programId, vs); glAttachShader(programId, fs); glLinkProgram(programId);
            if (glGetProgrami(programId, GL_LINK_STATUS) == GL_FALSE) throw new RuntimeException(glGetProgramInfoLog(programId));
            glDeleteShader(vs); glDeleteShader(fs);
        }
        private int compileShader(int type, String source, String typeName) {
            int s = glCreateShader(type); glShaderSource(s, source); glCompileShader(s);
            if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE) throw new RuntimeException(typeName + " failed: " + glGetShaderInfoLog(s));
            return s;
        }
        public void use() { glUseProgram(programId); }
        public int getUniformLocation(String name) { return uniformLocations.computeIfAbsent(name, n -> glGetUniformLocation(programId, n)); }
        public void setUniform(String name, int v) { glUniform1i(getUniformLocation(name), v); }
        public void setUniform(String name, float v) { glUniform1f(getUniformLocation(name), v); }
        public void setUniform(String name, float x, float y) { glUniform2f(getUniformLocation(name), x, y); }
        public void setUniform(String name, float x, float y, float z) { glUniform3f(getUniformLocation(name), x, y, z); }
        public void setUniform(String name, float x, float y, float z, float w) { glUniform4f(getUniformLocation(name), x, y, z, w); }
        public void setUniformMatrix3(String name, float[] v) { glUniformMatrix3fv(getUniformLocation(name), false, v); }
        public void setUniformMatrix4(String name, float[] v) { glUniformMatrix4fv(getUniformLocation(name), false, v); }
        public void setUniform1fv(String name, float[] v) { glUniform1fv(getUniformLocation(name), v); }
        public void delete() { glDeleteProgram(programId); }
    }

    public static class PostProcessParams {
        public int toneMapMode = 0; public float exposure = 1.0f;
        public boolean bloomEnabled = false; public float bloomIntensity = 0.5f, bloomThreshold = 1.0f; public int bloomRadius = 3;
        public boolean chromaticAberrationEnabled = false; public float chromaticAberrationIntensity = 0.005f;
        public boolean vignetteEnabled = false; public float vignetteIntensity = 0.3f, vignetteSoftness = 0.5f;
        public boolean filmGrainEnabled = false; public float filmGrainIntensity = 0.03f;
        public boolean sharpenEnabled = false; public float sharpenIntensity = 0.3f;
        public float saturation = 1.0f;
        public boolean lensEffectsEnabled = false; public float lensDirtIntensity = 0.5f, starburstIntensity = 0.3f;
        public int colorGradingMode = 0; public float colorGradingIntensity = 1.0f;
        public transient float audioDeltaExposure = 0f, audioDeltaSaturation = 0f, audioDeltaVignette = 0f, audioDeltaCA = 0f;
        public transient boolean audioForceVignette = false, audioForceCA = false;
        public PostProcessParams() {}
        public PostProcessParams copy() {
            PostProcessParams c = new PostProcessParams();
            c.toneMapMode = this.toneMapMode; c.exposure = this.exposure; c.bloomEnabled = this.bloomEnabled; c.bloomIntensity = this.bloomIntensity;
            c.bloomThreshold = this.bloomThreshold; c.bloomRadius = this.bloomRadius; c.chromaticAberrationEnabled = this.chromaticAberrationEnabled;
            c.chromaticAberrationIntensity = this.chromaticAberrationIntensity; c.vignetteEnabled = this.vignetteEnabled; c.vignetteIntensity = this.vignetteIntensity;
            c.vignetteSoftness = this.vignetteSoftness; c.filmGrainEnabled = this.filmGrainEnabled; c.filmGrainIntensity = this.filmGrainIntensity;
            c.sharpenEnabled = this.sharpenEnabled; c.sharpenIntensity = this.sharpenIntensity; c.saturation = this.saturation;
            c.lensEffectsEnabled = this.lensEffectsEnabled; c.lensDirtIntensity = this.lensDirtIntensity; c.starburstIntensity = this.starburstIntensity;
            c.colorGradingMode = this.colorGradingMode; c.colorGradingIntensity = this.colorGradingIntensity;
            c.audioDeltaExposure = this.audioDeltaExposure; c.audioDeltaSaturation = this.audioDeltaSaturation;
            c.audioDeltaVignette = this.audioDeltaVignette; c.audioDeltaCA = this.audioDeltaCA;
            c.audioForceVignette = this.audioForceVignette; c.audioForceCA = this.audioForceCA;
            return c;
        }
        public void applyCinematicPreset() {
            toneMapMode = 2; exposure = 1.1f; bloomEnabled = true; bloomIntensity = 0.4f; bloomThreshold = 0.8f; bloomRadius = 4;
            chromaticAberrationEnabled = true; chromaticAberrationIntensity = 0.003f; vignetteEnabled = true; vignetteIntensity = 0.4f; vignetteSoftness = 0.6f;   
            filmGrainEnabled = true; filmGrainIntensity = 0.02f; sharpenEnabled = false; saturation = 1.1f;
            lensEffectsEnabled = true; lensDirtIntensity = 0.15f; starburstIntensity = 0.2f; colorGradingMode = 1; colorGradingIntensity = 0.8f;
        }
        public void applyCleanPreset() {
            toneMapMode = 0; exposure = 1.0f; bloomEnabled = true; bloomIntensity = 0.2f; bloomThreshold = 1.2f; bloomRadius = 2;
            chromaticAberrationEnabled = false; vignetteEnabled = false; filmGrainEnabled = false; sharpenEnabled = true; sharpenIntensity = 0.2f;
            saturation = 1.0f; lensEffectsEnabled = false; lensDirtIntensity = 0.0f; starburstIntensity = 0.0f; colorGradingMode = 0;
        }
        public void applyVibrantPreset() {
            toneMapMode = 0; exposure = 1.2f; bloomEnabled = true; bloomIntensity = 0.6f; bloomThreshold = 0.7f; bloomRadius = 5;
            chromaticAberrationEnabled = true; chromaticAberrationIntensity = 0.008f; vignetteEnabled = true; vignetteIntensity = 0.2f; vignetteSoftness = 0.4f;   
            filmGrainEnabled = false; sharpenEnabled = false; saturation = 1.4f;
            lensEffectsEnabled = true; lensDirtIntensity = 0.1f; starburstIntensity = 0.4f; colorGradingMode = 4; colorGradingIntensity = 0.6f;
        }
        public void reset() {
            toneMapMode = 0; exposure = 1.0f; bloomEnabled = false; bloomIntensity = 0.5f; bloomThreshold = 1.0f; bloomRadius = 3;
            chromaticAberrationEnabled = false; chromaticAberrationIntensity = 0.005f; vignetteEnabled = false; vignetteIntensity = 0.3f; vignetteSoftness = 0.5f;
            filmGrainEnabled = false; filmGrainIntensity = 0.03f; sharpenEnabled = false; sharpenIntensity = 0.3f; saturation = 1.0f;
            lensEffectsEnabled = false; lensDirtIntensity = 0.0f; starburstIntensity = 0.0f; colorGradingMode = 0; colorGradingIntensity = 1.0f;
            audioDeltaExposure = 0f; audioDeltaSaturation = 0f; audioDeltaVignette = 0f; audioDeltaCA = 0f; audioForceVignette = false; audioForceCA = false;
        }
    }
}
