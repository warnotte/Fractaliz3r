package org.fractalizer.explore;

import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.test.CameraUtils;
import org.fractalizer.ui.GLSLFractalizerController;

import java.awt.image.BufferedImage;
import java.util.function.BooleanSupplier;

/**
 * {@link ViewRenderer} over the GPU controller: aims the scene's own camera and renders
 * in memory at thumbnail size. The caller owns the camera — it snapshots the position,
 * orientation and field of view before exploring and puts them back after, because every
 * call here overwrites them.
 */
public final class ControllerViewRenderer implements ViewRenderer {

    private final GLSLFractalizerController controller;
    private final AbstractFractalParams params;
    private final int width, height;
    private final BooleanSupplier cancelled;

    public ControllerViewRenderer(GLSLFractalizerController controller, AbstractFractalParams params,
                                  int width, int height, BooleanSupplier cancelled) {
        this.controller = controller;
        this.params = params;
        this.width = width;
        this.height = height;
        this.cancelled = cancelled;
    }

    @Override public int width() { return width; }
    @Override public int height() { return height; }

    private void aim(float[] eye, float[] target, float fovDeg) {
        Camera cam = params.getCamera();
        cam.setPosition(eye[0], eye[1], eye[2]);
        float[] q = CameraUtils.lookAt(eye, target);
        cam.setQuaternion(q[0], q[1], q[2], q[3]);
        params.setFovDegrees(fovDeg);
    }

    @Override
    public float[] depth(float[] eye, float[] target, float fovDeg) {
        aim(eye, target, fovDeg);
        return controller.renderDepthAOV(width, height);
    }

    @Override
    public BufferedImage colour(float[] eye, float[] target, float fovDeg, int samples) {
        aim(eye, target, fovDeg);
        return controller.renderStill(width, height, samples, cancelled::getAsBoolean);
    }
}
