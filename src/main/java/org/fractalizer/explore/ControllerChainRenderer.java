package org.fractalizer.explore;

import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.HybridNode;
import org.fractalizer.test.CameraUtils;
import org.fractalizer.ui.GLSLFractalizerController;

import java.awt.image.BufferedImage;
import java.util.function.BooleanSupplier;

/**
 * {@link ChainProspector.ChainRenderer} over the GPU controller. The params it drives
 * must be the controller's current ones: setting a chain makes it the graph's root and
 * marks the params dirty, so the next render compiles it; a parameter change refreshes the
 * uniforms without a compile. The caller owns the scene: in the app the search runs on a
 * throw-away {@link NodeGraphParams} that is swapped in for the run and the user's own
 * params are put back afterwards.
 */
public final class ControllerChainRenderer implements ChainProspector.ChainRenderer {

    private final GLSLFractalizerController controller;
    private final NodeGraphParams params;
    private final BooleanSupplier cancelled;

    public ControllerChainRenderer(GLSLFractalizerController controller, NodeGraphParams params, BooleanSupplier cancelled) {
        this.controller = controller;
        this.params = params;
        this.cancelled = cancelled;
    }

    @Override
    public void setChain(HybridNode chain) {
        params.setGraphRoot(chain);   // dirty: compiled on the next render
        if (controller.getParams() != params) {
            // First chain of a search run on a throw-away scene: make it the controller's
            // current one now, so the compile that follows is this chain's and not the
            // default graph's. A harness whose params are already current skips this.
            controller.replaceParams(params);
            controller.updatePaletteTexture(params.getCustomGradient());
        }
    }

    @Override
    public void chainParamsChanged() {
        params.updateUniforms();
    }

    private void aim(float[] eye, float[] target, float fovDeg) {
        Camera cam = params.getCamera();
        cam.setPosition(eye[0], eye[1], eye[2]);
        float[] q = CameraUtils.lookAt(eye, target);
        cam.setQuaternion(q[0], q[1], q[2], q[3]);
        params.setFovDegrees(fovDeg);
    }

    @Override
    public float[] depth(float[] eye, float[] target, float fovDeg, int w, int h) {
        aim(eye, target, fovDeg);
        return controller.renderDepthAOV(w, h);
    }

    @Override
    public BufferedImage colour(float[] eye, float[] target, float fovDeg, int w, int h, int samples) {
        aim(eye, target, fovDeg);
        return controller.renderStill(w, h, samples, cancelled::getAsBoolean);
    }
}
