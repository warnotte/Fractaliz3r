package org.fractalizer.explore;

import org.fractalizer.engine.Camera;
import org.fractalizer.explore.CameraFlight.Pose;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.test.CameraUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CameraFlightTest {

    static Pose pose(float[] eye, float[] target, float fovDeg) {
        return new Pose(eye, CameraUtils.lookAt(eye, target), (float) Math.toRadians(fovDeg));
    }

    @Test
    void startsAtTheStartPoseAndLandsExactlyOnTheEndPose() {
        Pose a = pose(new float[]{0, 0, -3}, new float[]{0, 0, 0}, 50);
        Pose b = pose(new float[]{1, 0.5f, -1}, new float[]{0.2f, 0.1f, 0}, 40);
        CameraFlight f = new CameraFlight(a, b, 1.0);

        Pose p0 = f.at(0), p1 = f.at(1);
        assertArrayEquals(a.eye(), p0.eye(), 1e-6f);
        assertArrayEquals(b.eye(), p1.eye(), 1e-6f);
        assertEquals(a.fov(), p0.fov(), 1e-6f);
        assertEquals(b.fov(), p1.fov(), 1e-6f);
        assertArrayEquals(a.quaternion(), p0.quaternion(), 1e-5f);
        assertArrayEquals(b.quaternion(), p1.quaternion(), 1e-5f);
    }

    @Test
    void positionMovesMonotonicallyAndTheOrientationStaysAUnitQuaternion() {
        Pose a = pose(new float[]{0, 0, -3}, new float[]{0, 0, 0}, 50);
        Pose b = pose(new float[]{2, 1, 1}, new float[]{0, 0, 0}, 50);
        CameraFlight f = new CameraFlight(a, b, 1.0);
        double lastX = -1;
        for (int i = 0; i <= 20; i++) {
            Pose p = f.at(i / 20.0);
            assertTrue(p.eye()[0] >= lastX - 1e-6, "x never goes back");
            lastX = p.eye()[0];
            float[] q = p.quaternion();
            assertEquals(1.0, Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]), 1e-4);
        }
        // Eased: the middle of the flight is the middle of the path, the start is slow.
        assertEquals(1.0, f.at(0.5).eye()[0], 1e-5);
        assertTrue(f.at(0.1).eye()[0] < 0.2 * 2, "slow start");
    }

    @Test
    void takesTheShorterArcWhenTheTargetQuaternionIsNegated() {
        Pose a = pose(new float[]{0, 0, -3}, new float[]{0, 0, 0}, 50);
        Pose b = pose(new float[]{3, 0, 0}, new float[]{0, 0, 0}, 50);
        float[] neg = b.quaternion().clone();
        for (int i = 0; i < 4; i++) neg[i] = -neg[i];
        Pose bNeg = new Pose(b.eye(), neg, b.fov());

        Pose mid = new CameraFlight(a, b, 1).at(0.5);
        Pose midNeg = new CameraFlight(a, bNeg, 1).at(0.5);
        assertArrayEquals(mid.quaternion(), midNeg.quaternion(), 1e-5f, "same rotation, same path");
        // and the midpoint is really between the two: its forward vector is between -Z view and -X view
        Camera c = new Camera();
        c.setQuaternion(mid.quaternion()[0], mid.quaternion()[1], mid.quaternion()[2], mid.quaternion()[3]);
        float[] fwd = c.getForwardVector();
        assertTrue(fwd[2] > 0.3 && fwd[0] < -0.3, "looking half way between +Z and -X: " + fwd[0] + "," + fwd[2]);
    }

    @Test
    void stepDrivesTheCameraAndReportsLanding() {
        Pose a = pose(new float[]{0, 0, -3}, new float[]{0, 0, 0}, 50);
        Pose b = pose(new float[]{0, 0, -1}, new float[]{0, 0, 0}, 30);
        CameraFlight f = new CameraFlight(a, b, 0.5);
        NodeGraphParams params = new NodeGraphParams(FractalType.MANDELBULB);
        Camera cam = params.getCamera();

        long t0 = 1_000_000_000L;
        assertFalse(f.step(t0, cam, params), "just started");
        assertEquals(-3f, cam.getZ(), 1e-5f);
        assertFalse(f.step(t0 + 250_000_000L, cam, params), "half way");
        assertEquals(-2f, cam.getZ(), 1e-4f);
        assertTrue(f.step(t0 + 600_000_000L, cam, params), "landed after the duration");
        assertEquals(-1f, cam.getZ(), 1e-5f);
        assertEquals(Math.toRadians(30), params.getFov(), 1e-5);
    }
}
