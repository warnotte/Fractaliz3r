package org.fractalizer.render;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Everything one render of the viewport needs, frozen on the JavaFX thread at the moment
 * of the request. The scheduler and its jobs read it on the GL thread and never touch the
 * live params again, so a slider moved while a step runs cannot tear a frame.
 *
 * @param programKey     the engine program to draw with (the node graph is "nodegraph")
 * @param programSource  GLSL to make {@code programKey} hold before drawing (the engine
 *                       compiles it only if it does not already hold exactly this source)
 * @param programDefines lines injected before common.glsl when compiling (may be "")
 * @param uniforms       the uniform map of the full-quality render, without the
 *                       size-dependent {@code pixelRadius}
 * @param coneTan        tan(fov/2) when cone tracing is on, 0 when off: {@code pixelRadius}
 *                       is derived from it and the height actually rendered
 * @param materialSSBO   per-node material data for the SSBO, or null for none
 * @param viewportWidth  the viewport size the refinement renders at
 * @param viewportHeight
 * @param previewScale   the scene's ceiling for the preview size, 0..1
 * @param previewFastShading whether the preview may drop path tracing and cut steps
 * @param previewSamples how many samples a preview accumulates
 * @param fullSamples    how many samples the refinement accumulates
 * @param causticPhotons photons per pass for caustics, as the side of a square, 0 for none:
 *                       the engine then needs the scene's photon program too
 */
public record SceneSnapshot(String programKey,
                            String programSource,
                            String programDefines,
                            Map<String, Object> uniforms,
                            float coneTan,
                            float[] materialSSBO,
                            int viewportWidth,
                            int viewportHeight,
                            float previewScale,
                            boolean previewFastShading,
                            int previewSamples,
                            int fullSamples,
                            int causticPhotons) {

    public SceneSnapshot {
        uniforms = Collections.unmodifiableMap(new HashMap<>(uniforms));
        programDefines = programDefines == null ? "" : programDefines;
        viewportWidth = Math.max(1, viewportWidth);
        viewportHeight = Math.max(1, viewportHeight);
        previewScale = Math.max(0.05f, Math.min(1f, previewScale));
        previewSamples = Math.max(1, previewSamples);
        fullSamples = Math.max(1, fullSamples);
        causticPhotons = Math.max(0, causticPhotons);
    }

    /** Photons per pass for a render at {@code scale} of the viewport: none for a preview
     *  that drops path tracing (caustics are a path-tracing term), else the side scaled with
     *  the image so the photons per pixel stay what the full render gets. */
    public int causticPhotonsFor(boolean preview, float scale) {
        if (causticPhotons == 0 || (preview && previewFastShading)) return 0;
        return preview ? Math.max(64, Math.round(causticPhotons * scale)) : causticPhotons;
    }

    /**
     * The uniforms for a render {@code height} pixels tall: {@code pixelRadius} follows the
     * height (cone tracing's pixel footprint), and a preview drops what the eye cannot see
     * while the camera moves. The scene's own settings are untouched.
     */
    public Map<String, Object> uniformsFor(int height, boolean preview) {
        Map<String, Object> u = new HashMap<>(uniforms);
        u.put("pixelRadius", coneTan > 0f ? coneTan / (height * 0.5f) : 0f);
        if (preview && previewFastShading) applyFastPreview(u);
        return u;
    }

    /** Path tracing is the dominant cost (126 ms against 16 ms for classic shading on the
     *  benchmark Mandelbulb); the step counts and DoF are what remain after that. */
    static void applyFastPreview(Map<String, Object> u) {
        u.put("pathTracingEnabled", 0);
        u.put("dofEnabled", 0);
        u.put("detailLOD", 0f);            // extra DE iterations are for stills
        u.put("volumetricFogEnabled", 0);
        Object steps = u.get("maxRaySteps");
        if (steps instanceof Integer n) u.put("maxRaySteps", Math.max(60, n / 2));
        Object sh = u.get("shadowSteps");
        if (sh instanceof Integer n) u.put("shadowSteps", Math.max(16, n / 4));
        u.put("aoSteps", 2);
    }
}
