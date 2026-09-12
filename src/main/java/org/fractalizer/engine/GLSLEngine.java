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
    private int lensDirtTexture, paletteTexture, blueNoiseTexture;
    private int varianceTexture, varianceFBO;
    private boolean adaptiveSamplingEnabled = false;
    private int envMapTexture;
    private boolean envMapLoaded = false;
    private float envRotation = 0.0f, envLightingMix = 0.5f;
    private int envMarginalCDFTexture, envConditionalCDFTexture, envMapWidth, envMapHeight;
    private float envTotalLuminance;
    private boolean envCDFReady = false;
    private int materialSSBO = 0;
    // Caustics: the photon pass draws side x side photons into these and the splat pass adds
    // them to the accumulation, one pass per sample (photon.glsl); 0 = off
    private volatile int photonSide = 0;
    private int photonFBO, photonPixelTexture, photonColorTexture, photonTextureSide, photonVAO;
    private ShaderProgram splatProgram;
    // The emission square as a grid of cells: which have produced a specular hit (drawn by
    // the splat pass, read back now and then), and the snapshot the photon pass draws from
    private static final int CAUSTIC_GRID = 64;
    private int cellFBO, cellTexture, cellActiveTexture, cellListTexture, activeCells;
    private boolean cellMapDirty;
    // The gather (lights.glsl): the pass's photon vertices, and the hash grid over them built
    // by three compute passes; the radius shrinks with the samples (progressive photon mapping)
    private static final int MERGE_CELLS = 65536;
    private static final float MERGE_ALPHA = 0.7f;
    private int photonVertexSSBO, cellStartSSBO, cellCountSSBO, photonIndexSSBO, cellCursorSSBO;
    private int photonBufferSide;
    private ShaderProgram gridCount, gridScan, gridScatter;
    private float mergeRadiusNow;
    private int quadVAO, quadVBO, quadEBO;
    private final Map<String, ShaderProgram> programs = new HashMap<>();
    private String activeProgram;
    private int sampleCount = 0, maxSamples = 10000;
    private boolean needsReset = true;
    private int currentRenderMode = 0;
    private ShaderProgram displayProgram, postProcessProgram, bloomExtractProgram, bloomBlurProgram, evaluatorProgram;
    private PostProcessParams postProcessParams = new PostProcessParams();
    private final ScheduledExecutorService glThread;
    private volatile boolean initialized = false;
    private String renderer, glVersion, glslVersion;

    public GLSLEngine() { this(1280, 720); }

    public GLSLEngine(int width, int height) {
        this.currentWidth = width; this.currentHeight = height;
        this.glThread = Executors.newSingleThreadScheduledExecutor(r -> {
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
        createBlueNoiseTexture();
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
        touchGLState();
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
            evaluatorProgram.setUniform("gridResolution", res);
            for (Map.Entry<String, Object> entry : uniforms.entrySet()) { setUniformValue(evaluatorProgram, entry.getKey(), entry.getValue()); }
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, paletteTexture);
            evaluatorProgram.setUniform("paletteTexture", 1);
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

    /**
     * Compile a custom user-written fractal shader.
     * @return null on success, error message on failure
     */
    public String loadCustomFractalShader(String name, String userSource) {
        return loadCustomFractalShader(name, userSource, "");
    }

    /**
     * @param extraDefines lines injected between {@code #version} and common.glsl, for
     *                     features that have to be compiled out entirely when unused.
     */
    public String loadCustomFractalShader(String name, String userSource, String extraDefines) {
        return compileSceneProgram(name, userSource, extraDefines, false);
    }

    /** A scene program: the defines, common.glsl, the scene, the raytracer. The photon
     *  variant adds PHOTON_PASS, which takes the camera main out, and photon.glsl after. */
    private String compileSceneProgram(String name, String userSource, String extraDefines, boolean photon) {
        String[] error = {null};
        runOnGLThread(() -> {
            try {
                String vertexSource = loadResource("/shaders/fullscreen.vert");
                String commonSource = stripVersion(loadResource("/shaders/common.glsl"));
                String lightsSource = stripVersion(loadResource("/shaders/lights.glsl"));   // empty unless HAS_EMITTERS or BIDIR
                String raytracerSource = stripVersion(loadResource("/shaders/raytracer.glsl"));
                String fragmentSource = "#version 430 core\n" + extraDefines + (photon ? "#define PHOTON_PASS\n" : "")
                        + commonSource + "\n" + lightsSource + "\n" + userSource + "\n" + raytracerSource
                        + (photon ? "\n" + stripVersion(loadResource("/shaders/photon.glsl")) : "");
                ShaderProgram old = programs.remove(name);
                if (old != null) old.delete();
                ShaderProgram program = new ShaderProgram(vertexSource, fragmentSource);
                programs.put(name, program);
            } catch (Exception e) {
                error[0] = e.getMessage();
                System.err.println("GLSL compilation failed (" + name + "): " + e.getMessage());
            }
        });
        return error[0];
    }

    public void setActiveProgram(String name) {
        if (!programs.containsKey(name)) return;
        touchGLState();
        if (!name.equals(activeProgram)) { activeProgram = name; needsReset = true; }
    }

    public boolean hasProgram(String name) { return programs.containsKey(name); }

    /**
     * Load raw shader source from a resource path.
     */
    public String loadShaderSource(String resourcePath) { return loadResource(resourcePath); }

    /**
     * Compile a boolean shader that combines two fractal shaders.
     * The secondary source must already be preprocessed (symbols prefixed).
     * Injects #define BOOLEAN_OPS before common.glsl.
     */
    public void loadBooleanFractalShader(String name, String primaryPath, String secondarySource) {
        runOnGLThread(() -> {
            try {
                String vertexSource = loadResource("/shaders/fullscreen.vert");
                String commonSource = stripVersion(loadResource("/shaders/common.glsl"));
                String primarySource = stripVersion(loadResource(primaryPath));
                String raytracerSource = stripVersion(loadResource("/shaders/raytracer.glsl"));
                String fragmentSource = "#version 430 core\n#define BOOLEAN_OPS\n"
                    + commonSource + "\n" + primarySource + "\n" + secondarySource + "\n" + raytracerSource;
                // Delete old program if it exists
                ShaderProgram old = programs.remove(name);
                if (old != null) old.delete();
                ShaderProgram program = new ShaderProgram(vertexSource, fragmentSource);
                programs.put(name, program);
            } catch (Exception e) {
                System.err.println("Boolean shader compilation failed for " + name + ": " + e.getMessage());
                // Don't crash — boolean ops will be silently disabled
            }
        });
    }

    public void resize(int width, int height) {
        if (width != currentWidth || height != currentHeight) {
            runOnGLThread(() -> { currentWidth = width; currentHeight = height; recreateFramebuffer(); needsReset = true; });
        }
    }

    public void resetAccumulation() { needsReset = true; }

    private float[] materialSSBOData;   // what the buffer holds, to skip identical uploads
    private int lightSSBO = 0;
    private float[] lightSSBOData;

    /** The light table (LightList, lights.glsl), binding 7; as {@link #updateMaterialSSBO}. */
    public void updateLightSSBO(float[] data) {
        final float[] copy = data == null ? null : data.clone();
        postToGLThread(() -> {
            if (java.util.Arrays.equals(copy, lightSSBOData)) return;
            lightSSBOData = copy;
            touchGLState();
            if (copy == null || copy.length == 0) {
                if (lightSSBO != 0) { glDeleteBuffers(lightSSBO); lightSSBO = 0; }
                return;
            }
            if (lightSSBO == 0) lightSSBO = glGenBuffers();
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, lightSSBO);
            glBufferData(GL_SHADER_STORAGE_BUFFER, copy, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        });
    }

    /** Posted, not awaited: the GL thread runs its tasks in order, so the upload lands
     *  before any batch submitted after it. Identical data is not uploaded again. */
    public void updateMaterialSSBO(float[] data) {
        final float[] copy = data == null ? null : data.clone();
        postToGLThread(() -> {
            if (java.util.Arrays.equals(copy, materialSSBOData)) return;
            materialSSBOData = copy;
            touchGLState();
            if (copy == null || copy.length == 0) {
                if (materialSSBO != 0) { glDeleteBuffers(materialSSBO); materialSSBO = 0; }
                return;
            }
            if (materialSSBO == 0) materialSSBO = glGenBuffers();
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, materialSSBO);
            glBufferData(GL_SHADER_STORAGE_BUFFER, copy, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        });
    }

    // Binds the per-pass state that is constant across every sample of one
    // accumulation batch (FBO, blend, program, textures, SSBO, user uniforms). Bound
    // once per batch rather than once per sample. Must run on the GL thread.
    private void bindAccumPass(ShaderProgram program, Map<String, Object> uniforms) {
        touchGLState();
        if (photonSide > 0) ensurePhotonBuffers(photonSide);
        glDisable(GL_SCISSOR_TEST);            // a whole sample, unless drawRows narrows it right after
        glBindFramebuffer(GL_FRAMEBUFFER, accumFBO);
        glViewport(0, 0, currentWidth, currentHeight);
        glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE);
        program.use();
        program.setUniform("resolution", (float) currentWidth, (float) currentHeight);
        bindSceneState(program, uniforms);
        glBindVertexArray(quadVAO);
    }

    // Textures, SSBO and the scene's uniforms: what a scene program needs whichever pass it draws.
    private void bindSceneState(ShaderProgram program, Map<String, Object> uniforms) {
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, envMapTexture);
        program.setUniform("envMap", 0); program.setUniform("useEnvMap", envMapLoaded ? 1 : 0);
        program.setUniform("envRotation", envRotation); program.setUniform("envLightingMix", envLightingMix);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, paletteTexture);
        program.setUniform("paletteTexture", 1);
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, blueNoiseTexture);
        program.setUniform("blueNoiseTex", 2);
        if (envCDFReady) {
            glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, envMarginalCDFTexture); program.setUniform("envMarginalCDF", 3);
            glActiveTexture(GL_TEXTURE4); glBindTexture(GL_TEXTURE_2D, envConditionalCDFTexture); program.setUniform("envConditionalCDF", 4);
            program.setUniform("envTotalLuminance", envTotalLuminance);
            program.setUniform("envMapWidth", envMapWidth); program.setUniform("envMapHeight", envMapHeight);
        } else {
            program.setUniform("envMapWidth", 0); program.setUniform("envMapHeight", 0); program.setUniform("envTotalLuminance", 0.0f);
        }
        if (adaptiveSamplingEnabled) glBindImageTexture(5, varianceTexture, 0, false, 0, GL_READ_WRITE, GL_RGBA32F);
        if (materialSSBO != 0) glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, materialSSBO);
        if (lightSSBO != 0) glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 7, lightSSBO);
        if (photonSide > 0 && splatProgram != null) {
            // the sun's emission map, read by the photon pass to draw and by the path tracer to weigh
            glActiveTexture(GL_TEXTURE7); glBindTexture(GL_TEXTURE_2D, cellListTexture); program.setUniform("causticCellList", 7);
            glActiveTexture(GL_TEXTURE8); glBindTexture(GL_TEXTURE_2D, cellActiveTexture); program.setUniform("causticCellActive", 8);
            program.setUniform("causticActiveCells", activeCells);
            program.setUniform("photonSide", photonSide);
            if (photonVertexSSBO != 0) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 8, photonVertexSSBO);
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 9, cellStartSSBO);
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 10, cellCountSSBO);
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 11, photonIndexSSBO);
            }
            program.setUniform("mergeRadius", mergeRadiusNow);
            program.setUniform("cellMask", MERGE_CELLS - 1);
        }
        for (Map.Entry<String, Object> entry : uniforms.entrySet()) { setUniformValue(program, entry.getKey(), entry.getValue()); }
    }

    // Draws one accumulation sample; only the per-sample uniforms change. Must run on
    // the GL thread after bindAccumPass().
    private void drawAccumSample(ShaderProgram program) {
        program.setUniform("sampleIndex", sampleCount);
        program.setUniform("time", (float) glfwGetTime());
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
        if (adaptiveSamplingEnabled) glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        sampleCount++;
    }

    private void endAccumPass() {
        glDisable(GL_BLEND); glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public void renderSample(Map<String, Object> uniforms) {
        runOnGLThread(() -> {
            if (activeProgram == null) throw new IllegalStateException();
            if (needsReset) { clearAccumulation(); needsReset = false; }
            if (sampleCount >= maxSamples) return;
            ShaderProgram program = programs.get(activeProgram);
            if (photonsActive()) photonPass(uniforms, sampleCount);   // before the sample: its gather reads this pass's photons
            bindAccumPass(program, uniforms);
            drawAccumSample(program);
            endAccumPass();
        });
    }

    public void glSync() { runOnGLThread(GL43::glFinish); }

    // Renders `count` accumulation samples in a single GL-thread pass: constant pass
    // state is bound once and there is NO per-sample glFinish, so the GPU pipelines
    // every sample and is synchronised only at readback (readImage's glFinish). Used
    // by both progressive preview and still-frame export.
    public void renderSamples(Map<String, Object> uniforms, int count) {
        if (count <= 0) return;
        runOnGLThread(() -> {
            if (activeProgram == null) throw new IllegalStateException();
            if (needsReset) { clearAccumulation(); needsReset = false; }
            ShaderProgram program = programs.get(activeProgram);
            for (int s = 0; s < count && sampleCount < maxSamples; s++) {
                if (photonsActive()) photonPass(uniforms, sampleCount);   // before the sample: its gather reads this pass's photons
                if (s == 0 || photonsActive()) bindAccumPass(program, uniforms);   // the photon pass unbinds it
                drawAccumSample(program);
            }
            endAccumPass();
        });
    }

    // ------------------------------------------------------------------------------------
    // Sample slicing, for the viewport scheduler (GL thread only).
    //
    // A refinement sample on a heavy scene costs seconds; the scheduler draws it as bands
    // between which it can change its mind. The accumulation state (sampleCount,
    // needsReset) is only ever touched here, on the GL thread.
    // ------------------------------------------------------------------------------------

    private ShaderProgram sliceProgram;   // non-null while a sliced sample is open
    private Map<String, Object> sliceUniforms;
    private float sliceTime;              // the sample's time uniform, the same for every strip
    private boolean passBound;            // the accumulation pass of the open sample is bound

    /** Any pass or state change other than a strip of the open sample: the next strip
     *  binds the accumulation pass again. Binding it per strip regardless cost 2-4 ms of
     *  GPU time each (measured by StripCostProbe: 36 strips of a 175 ms sample, 378 ms
     *  rebinding every strip against 222 ms bound once). */
    private void touchGLState() { passBound = false; }

    /** Open one accumulation sample. Rows are then drawn with {@link #drawRows} and the
     *  sample closed with {@link #commitSample} or {@link #discardSample}. The strips of
     *  one sample may be separated by other GL tasks (a readback for the viewport, a
     *  resize request), so every strip binds the pass again: the state of the previous
     *  strip cannot be assumed to survive. */
    public void beginSample(Map<String, Object> uniforms) {
        assertGLThread();
        if (activeProgram == null) throw new IllegalStateException("no active program");
        if (needsReset) { clearAccumulation(); needsReset = false; }
        if (photonsActive()) photonPass(uniforms, sampleCount);   // before the strips: their gather reads this pass's photons
        sliceProgram = programs.get(activeProgram);
        sliceUniforms = uniforms;
        sliceTime = (float) glfwGetTime();
        passBound = false;
    }

    /** Draw rows {@code y .. y+h-1} of the open sample. The pass stays bound between
     *  strips; it is bound again only after something else touched the GL state. */
    public void drawRows(int y, int h) {
        assertGLThread();
        if (sliceProgram == null) throw new IllegalStateException("no open sample");
        if (!passBound) {
            bindAccumPass(sliceProgram, sliceUniforms);
            sliceProgram.setUniform("sampleIndex", sampleCount);
            sliceProgram.setUniform("time", sliceTime);
            glEnable(GL_SCISSOR_TEST);
            passBound = true;
        }
        glScissor(0, y, currentWidth, h);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
    }

    /** The open sample as {@code strips} scissored bands in one submission with the pass
     *  bound once: the reference for what rebinding per strip costs (StripCostProbe). */
    public void drawRowsBoundOnce(int y0, int rows, int strips) {
        assertGLThread();
        if (sliceProgram == null) throw new IllegalStateException("no open sample");
        bindAccumPass(sliceProgram, sliceUniforms);
        sliceProgram.setUniform("sampleIndex", sampleCount);
        sliceProgram.setUniform("time", sliceTime);
        glEnable(GL_SCISSOR_TEST);
        for (int b = 0; b < strips; b++) {
            int a = y0 + rows * b / strips, z = y0 + rows * (b + 1) / strips;
            glScissor(0, a, currentWidth, z - a);
            glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
        }
        glDisable(GL_SCISSOR_TEST);
        endAccumPass();
        passBound = false;
    }

    /** Close the open sample and count it. */
    public void commitSample() {
        closeSample();
        sampleCount++;
    }

    /** Close the open sample without counting it: the accumulation holds part of a sample
     *  and is cleared before anything else is drawn or shown. */
    public void discardSample() {
        closeSample();
        needsReset = true;
    }

    private void closeSample() {
        assertGLThread();
        if (sliceProgram == null) return;
        // The scissor of the last strip is disabled whatever passBound says: another GL
        // task between that strip and this close (a palette upload, an SSBO update) clears
        // passBound, and a scissor left on clipped every clear and sample after it to the
        // strip's rectangle: the bands seen after a preset load (BandedSampleProbe).
        glDisable(GL_SCISSOR_TEST);
        if (passBound) endAccumPass();
        if (adaptiveSamplingEnabled) glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        sliceProgram = null;
        sliceUniforms = null;
        passBound = false;
    }

    /** A point in the GPU's command stream. */
    public final class GpuFence {
        private long handle;
        private GpuFence(boolean flush) { handle = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0); if (flush) glFlush(); }
        /** Wait until the GPU has passed this point. */
        public void await() {
            if (handle == 0) return;
            glClientWaitSync(handle, GL_SYNC_FLUSH_COMMANDS_BIT, 10_000_000_000L);
            glDeleteSync(handle); handle = 0;
        }
        /** Whether the GPU has passed this point, without waiting. */
        public boolean isSignalled() {
            if (handle == 0) return true;
            int[] status = new int[1];
            glGetSynciv(handle, GL_SYNC_STATUS, new int[1], status);
            if (status[0] == GL_SIGNALED) { glDeleteSync(handle); handle = 0; return true; }
            return false;
        }
        public void discard() { if (handle != 0) { glDeleteSync(handle); handle = 0; } }
    }

    /** Insert a fence after everything submitted so far, and flush so the GPU starts on it. */
    public GpuFence fence() { return fence(true); }

    /** A fence without a flush: the commands before it reach the GPU with the next flush
     *  or wait (a wait with GL_SYNC_FLUSH_COMMANDS_BIT flushes everything queued since,
     *  so several strips travel in one submission). */
    public GpuFence fence(boolean flush) {
        assertGLThread();
        return new GpuFence(flush);
    }

    /**
     * GPU time of the commands between {@link #begin} and {@link #end}, read after they
     * are known to be done (a fence). Unlike wall clock on the CPU it does not include the
     * wait for earlier work or the round trip to the driver, so it is what the cost model
     * wants when one submission is kept in flight.
     */
    public final class GpuTimer {
        private int id = glGenQueries();
        private GpuTimer() {}
        public void begin() { glBeginQuery(GL_TIME_ELAPSED, id); }
        public void end() { glEndQuery(GL_TIME_ELAPSED); }
        /** Nanoseconds of GPU time; blocks if the result is not yet available. Frees the query. */
        public long elapsedNs() {
            if (id == 0) return 0;
            long ns = glGetQueryObjectui64(id, GL_QUERY_RESULT);
            glDeleteQueries(id); id = 0;
            return ns;
        }
        public void discard() { if (id != 0) { glDeleteQueries(id); id = 0; } }
    }

    public GpuTimer timer() {
        assertGLThread();
        return new GpuTimer();
    }

    /** The current accumulation as a display-ready frame: post-processed, BGRA bytes, top
     *  row first, with the sample count it holds. Read as bytes (a quarter of the float
     *  readback, no conversion): 33 MB and two passes over two million pixels per frame at
     *  1080p were most of the cost of showing a refinement sample. */
    public ViewportFrame readViewportFrame() {
        assertGLThread();
        runPostProcess();
        int w = currentWidth, h = currentHeight, row = w * 4;
        java.nio.ByteBuffer buffer = MemoryUtil.memAlloc(row * h);
        glReadPixels(0, 0, w, h, GL_BGRA, GL_UNSIGNED_BYTE, buffer);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        byte[] bgra = new byte[row * h];
        for (int y = 0; y < h; y++) {          // GL rows are bottom-up
            buffer.position((h - 1 - y) * row);
            buffer.get(bgra, y * row, row);
        }
        MemoryUtil.memFree(buffer);
        return new ViewportFrame(w, h, bgra, sampleCount);
    }

    public record ViewportFrame(int width, int height, byte[] bgra, int samples) {}

    // ------------------------------------------------------------------------------------
    // Programs by source, and the GL executor for the scheduler
    // ------------------------------------------------------------------------------------

    private final Map<String, String> programSources = new HashMap<>();

    /**
     * Make {@code name} hold the program for {@code defines + source}, compiling only when
     * that is not already what it holds (an editor and a startup task both asking for the
     * same scene compile it once). GL thread or any thread.
     * @return null on success, the driver's message on failure
     */
    public String ensureProgram(String name, String source, String defines) {
        String full = (defines == null ? "" : defines) + source;
        if (full.equals(programSources.get(name)) && programs.containsKey(name)) return null;
        String err = loadCustomFractalShader(name, source, defines == null ? "" : defines);
        if (err == null) programSources.put(name, full);
        return err;
    }

    /** Whether {@code name} already holds the program for exactly this source. */
    public boolean hasProgram(String name, String source, String defines) {
        String full = (defines == null ? "" : defines) + source;
        return programs.containsKey(name) && full.equals(programSources.get(name));
    }

    // ------------------------------------------------------------------------------------
    // Caustics: the photon variant of a scene program, and the two passes it takes
    // ------------------------------------------------------------------------------------

    /** The photon-pass variant of {@code name}'s scene, as {@link #ensureProgram}: asked
     *  for only when caustics are on for that scene, compiled once per source. */
    public String ensurePhotonProgram(String name, String source, String defines) {
        String key = photonKey(name);
        String full = (defines == null ? "" : defines) + source;
        if (full.equals(programSources.get(key)) && programs.containsKey(key)) return null;
        String err = compileSceneProgram(key, source, defines == null ? "" : defines, true);
        if (err == null) programSources.put(key, full);
        return err;
    }

    public boolean hasPhotonProgram(String name, String source, String defines) {
        String key = photonKey(name);
        return programs.containsKey(key) && ((defines == null ? "" : defines) + source).equals(programSources.get(key));
    }

    private static String photonKey(String name) { return name + "#photon"; }

    /** Photons per pass for caustics, as the side of a square (side x side photons); 0 is
     *  off. With a side set, every accumulation sample is followed by one photon pass of the
     *  active program's photon variant, which must exist ({@link #ensurePhotonProgram}). */
    public void setCausticPhotons(int side) { photonSide = Math.max(0, side); }
    public int getCausticPhotons() { return photonSide; }

    /** Photons follow the samples of a final render only: an AOV pass (depth, normals,
     *  the debug views) must not receive radiance splats. */
    private boolean photonsActive() { return photonSide > 0 && currentRenderMode == 0; }

    /** One pass of photons with the active program's photon variant, then their splat
     *  into the accumulation. GL thread, between samples; leaves no pass bound. */
    private void photonPass(Map<String, Object> uniforms, int passIndex) {
        ShaderProgram photon = programs.get(photonKey(activeProgram));
        if (photon == null) throw new IllegalStateException("caustics on but no photon program for " + activeProgram + ": ensurePhotonProgram first");
        int side = photonSide;
        ensurePhotonBuffers(side);
        touchGLState();
        // the gather radius of this pass: r0 from the uniforms, shrinking as k^((alpha-1)/2)
        Object r0 = uniforms.get("mergeRadius0");
        float base = r0 instanceof Float f ? f : 0f;
        mergeRadiusNow = base > 0f ? (float) (base * Math.pow(Math.max(passIndex, 0) + 1, (MERGE_ALPHA - 1.0) / 2.0)) : 0f;
        // 1. where each photon lands and what it adds there
        glBindFramebuffer(GL_FRAMEBUFFER, photonFBO);
        glViewport(0, 0, side, side);
        glDisable(GL_BLEND);
        photon.use();
        photon.setUniform("resolution", (float) currentWidth, (float) currentHeight);
        bindSceneState(photon, uniforms);
        photon.setUniform("photonSide", side);
        photon.setUniform("sampleIndex", passIndex);
        photon.setUniform("time", (float) glfwGetTime());
        glBindVertexArray(quadVAO);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
        // 2. a point per photon, added to the accumulation
        glBindFramebuffer(GL_FRAMEBUFFER, accumFBO);
        glViewport(0, 0, currentWidth, currentHeight);
        glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE);
        splatProgram.use();
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, photonPixelTexture); splatProgram.setUniform("photonPixelTex", 0);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, photonColorTexture); splatProgram.setUniform("photonColorTex", 1);
        splatProgram.setUniform("photonSide", side);
        splatProgram.setUniform("splatMode", 0);
        glBindVertexArray(photonVAO);
        glDrawArrays(GL_POINTS, 0, side * side);
        // 3. which cells of the emission square met glass or metal
        glBindFramebuffer(GL_FRAMEBUFFER, cellFBO);
        glViewport(0, 0, CAUSTIC_GRID, CAUSTIC_GRID);
        splatProgram.setUniform("splatMode", 1);
        glDrawArrays(GL_POINTS, 0, side * side);
        glBindVertexArray(0);
        glDisable(GL_BLEND);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        cellMapDirty = true;
        if (mergeRadiusNow > 0f) buildPhotonGrid(side);
        // The first passes are drawn over the whole square and shape the map fast; after
        // that a refresh every sixteen passes follows what the map still discovers.
        if (passIndex < 4 || passIndex % 16 == 15) refreshCellMap();
    }

    /** The hash grid over this pass's photon vertices: counts per cell, their prefix sum,
     *  the photons scattered into their cells' slots. GL thread, after the photon draw. */
    private void buildPhotonGrid(int side) {
        int photons = side * side;
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);         // the photon draw's vertex writes
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, cellCountSSBO);
        glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (ByteBuffer) null);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 8, photonVertexSSBO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 9, cellStartSSBO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 10, cellCountSSBO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 11, photonIndexSSBO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 12, cellCursorSSBO);
        int groups = (photons + 255) / 256;
        gridCount.use();
        gridCount.setUniform("photonCount", photons); gridCount.setUniform("cellSize", mergeRadiusNow); gridCount.setUniform("cellMask", MERGE_CELLS - 1);
        glDispatchCompute(groups, 1, 1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        gridScan.use();
        glDispatchCompute(1, 1, 1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        gridScatter.use();
        gridScatter.setUniform("photonCount", photons); gridScatter.setUniform("cellSize", mergeRadiusNow); gridScatter.setUniform("cellMask", MERGE_CELLS - 1);
        glDispatchCompute(groups, 1, 1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    }

    /** Read the cell map back, list the active cells, and give both to the photon pass as
     *  one consistent snapshot. GL thread. */
    private void refreshCellMap() {
        if (!cellMapDirty) return;
        cellMapDirty = false;
        int n = CAUSTIC_GRID * CAUSTIC_GRID;
        FloatBuffer hits = MemoryUtil.memAllocFloat(n);
        glBindFramebuffer(GL_FRAMEBUFFER, cellFBO);
        glReadPixels(0, 0, CAUSTIC_GRID, CAUSTIC_GRID, GL_RED, GL_FLOAT, hits);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        FloatBuffer active = MemoryUtil.memAllocFloat(n);
        FloatBuffer list = MemoryUtil.memAllocFloat(n);
        int count = 0;
        for (int i = 0; i < n; i++) {
            boolean hit = hits.get(i) > 0f;
            active.put(i, hit ? 1f : 0f);
            if (hit) list.put(count++, (float) i);
        }
        glBindTexture(GL_TEXTURE_2D, cellActiveTexture);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, CAUSTIC_GRID, CAUSTIC_GRID, GL_RED, GL_FLOAT, active);
        if (count > 0) {
            list.limit(count);
            glBindTexture(GL_TEXTURE_2D, cellListTexture);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, count, 1, GL_RED, GL_FLOAT, list);
        }
        activeCells = count;
        MemoryUtil.memFree(hits); MemoryUtil.memFree(active); MemoryUtil.memFree(list);
    }

    private void clearCellMap() {
        if (splatProgram == null) return;
        glBindFramebuffer(GL_FRAMEBUFFER, cellFBO); glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        activeCells = 0;
        cellMapDirty = false;
    }

    private void ensurePhotonBuffers(int side) {
        if (splatProgram == null) {
            splatProgram = new ShaderProgram(loadResource("/shaders/photon_splat.vert"), loadResource("/shaders/photon_splat.frag"));
            photonVAO = glGenVertexArrays();      // no attributes: the splat reads its photon by gl_VertexID
            cellTexture = createFloatTexture(CAUSTIC_GRID, CAUSTIC_GRID, GL_R32F, GL_RED);
            cellActiveTexture = createFloatTexture(CAUSTIC_GRID, CAUSTIC_GRID, GL_R32F, GL_RED);
            cellListTexture = createFloatTexture(CAUSTIC_GRID * CAUSTIC_GRID, 1, GL_R32F, GL_RED);
            cellFBO = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, cellFBO);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, cellTexture, 0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            clearCellMap();
        }
        if (gridCount == null) {
            gridCount = new ShaderProgram(loadResource("/shaders/grid_count.comp"));
            gridScan = new ShaderProgram(loadResource("/shaders/grid_scan.comp"));
            gridScatter = new ShaderProgram(loadResource("/shaders/grid_scatter.comp"));
            cellStartSSBO = glGenBuffers(); cellCountSSBO = glGenBuffers(); cellCursorSSBO = glGenBuffers();
            for (int b : new int[]{cellStartSSBO, cellCountSSBO, cellCursorSSBO}) {
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, b);
                glBufferData(GL_SHADER_STORAGE_BUFFER, (long) MERGE_CELLS * 4, GL_DYNAMIC_COPY);
            }
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        }
        if (photonBufferSide != side) {
            if (photonVertexSSBO != 0) { glDeleteBuffers(photonVertexSSBO); glDeleteBuffers(photonIndexSSBO); }
            photonVertexSSBO = glGenBuffers();
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, photonVertexSSBO);
            glBufferData(GL_SHADER_STORAGE_BUFFER, (long) side * side * 16 * 4, GL_DYNAMIC_COPY);
            photonIndexSSBO = glGenBuffers();
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, photonIndexSSBO);
            glBufferData(GL_SHADER_STORAGE_BUFFER, (long) side * side * 4, GL_DYNAMIC_COPY);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
            photonBufferSide = side;
        }
        if (photonTextureSide == side) return;
        deletePhotonBuffers();
        photonPixelTexture = createPhotonTexture(side);
        photonColorTexture = createPhotonTexture(side);
        photonFBO = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, photonFBO);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, photonPixelTexture, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, photonColorTexture, 0);
        glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1});
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        photonTextureSide = side;
    }

    private int createPhotonTexture(int side) { return createFloatTexture(side, side, GL_RGBA32F, GL_RGBA); }

    private int createFloatTexture(int w, int h, int internalFormat, int format) {
        int tex = glGenTextures(); glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        return tex;
    }

    private void deletePhotonBuffers() {
        if (photonTextureSide == 0) return;
        glDeleteFramebuffers(photonFBO); glDeleteTextures(photonPixelTexture); glDeleteTextures(photonColorTexture);
        photonTextureSide = 0;
    }

    public boolean isGLThread() { return Thread.currentThread().getName().equals("GLSLEngine-Thread"); }

    private void assertGLThread() {
        if (!isGLThread()) throw new IllegalStateException("GL thread only: " + Thread.currentThread().getName());
    }

    /** Run {@code task} on the GL thread after everything already queued, without waiting. */
    public void post(Runnable task) { postToGLThread(task); }

    /** Run {@code task} on the GL thread and wait for it (inline when already there). */
    public void postAndWait(Runnable task) { runOnGLThread(task); }

    /** Run {@code task} on the GL thread after a delay; cancel through the returned future. */
    public ScheduledFuture<?> postDelayed(Runnable task, long delayMs) {
        return glThread.schedule(() -> {
            glfwMakeContextCurrent(window);
            try { task.run(); } catch (Exception e) { e.printStackTrace(); }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    public int getSampleCount() { return sampleCount; }
    public void setMaxSamples(int max) { this.maxSamples = max; }

    public float[] readImage() {
        float[] result = new float[currentWidth * currentHeight * 4];
        runOnGLThread(() -> {
            runPostProcess();
            FloatBuffer buffer = MemoryUtil.memAllocFloat(result.length);
            glReadPixels(0, 0, currentWidth, currentHeight, GL_RGBA, GL_FLOAT, buffer);
            buffer.get(result); MemoryUtil.memFree(buffer);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        });
        flipImageY(result, currentWidth, currentHeight);
        return result;
    }

    /** Bloom and post-process passes into the display framebuffer, which is left bound and
     *  finished for a readback. GL thread. */
    private void runPostProcess() {
        touchGLState();
        {
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
        }
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
        touchGLState();
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
        touchGLState();
        glDisable(GL_SCISSOR_TEST);            // the whole buffer, whatever a strip left
        glBindFramebuffer(GL_FRAMEBUFFER, accumFBO); glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, varianceFBO); glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, 0); sampleCount = 0;
        if (photonSide > 0) clearCellMap();      // the scene or the light may have moved
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

    private void createBlueNoiseTexture() {
        FloatBuffer data = BlueNoiseGenerator.generate();
        blueNoiseTexture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, blueNoiseTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RG32F, 64, 64, 0, GL_RG, GL_FLOAT, data);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        MemoryUtil.memFree(data); glBindTexture(GL_TEXTURE_2D, 0);
    }

    public void updatePaletteTexture(float[] rgbData, int resolution) {
        touchGLState();
        runOnGLThread(() -> {
            glBindTexture(GL_TEXTURE_2D, paletteTexture);
            FloatBuffer data = MemoryUtil.memAllocFloat(rgbData.length); data.put(rgbData).flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB32F, resolution, 1, 0, GL_RGB, GL_FLOAT, data);
            MemoryUtil.memFree(data); glBindTexture(GL_TEXTURE_2D, 0);
        });
    }

    private volatile String envMapPath = null;     // the file the environment map came from, null for none
    /** The file the environment map was loaded from, or null when none is loaded. */
    public String getEnvironmentMapPath() { return envMapPath; }

    public void loadEnvironmentMap(String filePath) {
        envMapPath = filePath;
        touchGLState();
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
        touchGLState();
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
        envMapPath = null;
        touchGLState();
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

    /** Queue a task on the GL thread without waiting for it. Order with respect to other
     *  GL tasks is preserved (single-threaded executor). */
    private void postToGLThread(Runnable task) {
        if (Thread.currentThread().getName().equals("GLSLEngine-Thread")) { task.run(); return; }
        glThread.submit(() -> {
            glfwMakeContextCurrent(window);
            try { task.run(); } catch (Exception e) { e.printStackTrace(); }
        });
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
            glDeleteTextures(paletteTexture); glDeleteTextures(blueNoiseTexture);
            if (materialSSBO != 0) glDeleteBuffers(materialSSBO);
            if (lightSSBO != 0) glDeleteBuffers(lightSSBO);
            deletePhotonBuffers();
            if (splatProgram != null) {
                splatProgram.delete(); glDeleteVertexArrays(photonVAO);
                glDeleteFramebuffers(cellFBO); glDeleteTextures(cellTexture); glDeleteTextures(cellActiveTexture); glDeleteTextures(cellListTexture);
            }
            if (gridCount != null) {
                gridCount.delete(); gridScan.delete(); gridScatter.delete();
                glDeleteBuffers(cellStartSSBO); glDeleteBuffers(cellCountSSBO); glDeleteBuffers(cellCursorSSBO);
                if (photonVertexSSBO != 0) { glDeleteBuffers(photonVertexSSBO); glDeleteBuffers(photonIndexSSBO); }
            }
            glDeleteVertexArrays(quadVAO); glDeleteBuffers(quadVBO); glDeleteBuffers(quadEBO);
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
        /** A compute program. */
        public ShaderProgram(String computeSource) {
            int cs = compileShader(GL_COMPUTE_SHADER, computeSource, "Compute");
            programId = glCreateProgram(); glAttachShader(programId, cs); glLinkProgram(programId);
            if (glGetProgrami(programId, GL_LINK_STATUS) == GL_FALSE) throw new RuntimeException(glGetProgramInfoLog(programId));
            glDeleteShader(cs);
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
        /** Copy persisted values in place. A load must not swap the instance: the FX panel
         *  holds a reference to it, and the audio deltas are live state that belongs to the
         *  running session, not to the saved scene. */
        public void copyFrom(PostProcessParams o) {
            if (o == null) return;
            toneMapMode = o.toneMapMode; exposure = o.exposure;
            bloomEnabled = o.bloomEnabled; bloomIntensity = o.bloomIntensity;
            bloomThreshold = o.bloomThreshold; bloomRadius = o.bloomRadius;
            chromaticAberrationEnabled = o.chromaticAberrationEnabled;
            chromaticAberrationIntensity = o.chromaticAberrationIntensity;
            vignetteEnabled = o.vignetteEnabled; vignetteIntensity = o.vignetteIntensity;
            vignetteSoftness = o.vignetteSoftness;
            filmGrainEnabled = o.filmGrainEnabled; filmGrainIntensity = o.filmGrainIntensity;
            sharpenEnabled = o.sharpenEnabled; sharpenIntensity = o.sharpenIntensity;
            saturation = o.saturation;
            lensEffectsEnabled = o.lensEffectsEnabled; lensDirtIntensity = o.lensDirtIntensity;
            starburstIntensity = o.starburstIntensity;
            colorGradingMode = o.colorGradingMode; colorGradingIntensity = o.colorGradingIntensity;
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
