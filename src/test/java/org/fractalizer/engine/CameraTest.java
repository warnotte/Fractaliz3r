package org.fractalizer.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The camera is a unit quaternion; every navigation key composes one more rotation onto
 * it. The properties that matter to the renderer are that the three basis vectors stay
 * orthonormal, that the quaternion never drifts off unit length, and that the axes point
 * where the controls say they do.
 */
class CameraTest {

    static final float EPS = 1e-5f;

    static float dot(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static float len(float[] a) { return (float) Math.sqrt(dot(a, a)); }

    static void assertOrthonormal(Camera c) {
        float[] f = c.getForwardVector(), r = c.getRightVector(), u = c.getUpVector();
        assertEquals(1f, len(f), EPS, "forward is unit");
        assertEquals(1f, len(r), EPS, "right is unit");
        assertEquals(1f, len(u), EPS, "up is unit");
        assertEquals(0f, dot(f, r), EPS, "forward ⟂ right");
        assertEquals(0f, dot(f, u), EPS, "forward ⟂ up");
        assertEquals(0f, dot(r, u), EPS, "right ⟂ up");
        float[] q = c.getQuaternion();
        assertEquals(1f, (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]), EPS, "unit quaternion");
    }

    @Test
    void defaultCameraLooksDownPositiveZFromMinusThree() {
        Camera c = new Camera();
        assertArrayEquals(new float[]{0, 0, -3}, c.getPosition(), EPS);
        assertArrayEquals(new float[]{0, 0, 1}, c.getForwardVector(), EPS);
        assertArrayEquals(new float[]{1, 0, 0}, c.getRightVector(), EPS);
        assertArrayEquals(new float[]{0, 1, 0}, c.getUpVector(), EPS);
        assertOrthonormal(c);
    }

    @Test
    void yawByNinetyDegreesTurnsForwardOntoRight() {
        Camera c = new Camera();
        float[] rightBefore = c.getRightVector();
        c.rotate((float) (Math.PI / 2) / c.getRotateSpeed(), 0);
        assertArrayEquals(rightBefore, c.getForwardVector(), 1e-4f, "forward is now the old right");
        assertEquals(0f, c.getForwardVector()[1], EPS, "a yaw introduces no pitch");
        assertOrthonormal(c);
    }

    @Test
    void pitchTiltsForwardTowardsDown() {
        Camera c = new Camera();
        c.rotate(0, (float) (Math.PI / 4) / c.getRotateSpeed());
        float[] f = c.getForwardVector();
        assertTrue(f[1] < -0.5f, "positive deltaY looks down, got " + f[1]);
        assertEquals(0f, f[0], EPS, "a pitch introduces no yaw");
        assertOrthonormal(c);
    }

    @Test
    void rollKeepsForwardAndSpinsUp() {
        Camera c = new Camera();
        float[] forwardBefore = c.getForwardVector();
        c.roll((float) (Math.PI / 2) / (c.getRotateSpeed() * 10));
        assertArrayEquals(forwardBefore, c.getForwardVector(), 1e-4f, "roll does not change the view direction");
        assertEquals(0f, Math.abs(c.getUpVector()[1]), 1e-4f, "up is now horizontal");
        assertOrthonormal(c);
    }

    @Test
    void manySmallRotationsDoNotDriftOffUnitLength() {
        Camera c = new Camera();
        for (int i = 0; i < 5000; i++) {
            c.rotate(3.7f, -1.3f);
            c.roll(0.4f);
        }
        assertOrthonormal(c);
    }

    @Test
    void movementFollowsTheLocalAxesScaledBySpeed() {
        Camera c = new Camera();
        c.setMoveSpeed(0.5f);
        c.rotate((float) (Math.PI / 2) / c.getRotateSpeed(), 0);   // forward = +X
        float[] f = c.getForwardVector(), r = c.getRightVector(), u = c.getUpVector();
        float[] p0 = c.getPosition();
        c.moveForward(2f);
        c.strafe(-1f);
        c.moveUp(3f);
        float[] p1 = c.getPosition();
        for (int i = 0; i < 3; i++) {
            float expected = p0[i] + 0.5f * (2f * f[i] - 1f * r[i] + 3f * u[i]);
            assertEquals(expected, p1[i], 1e-4f, "axis " + i);
        }
    }

    @Test
    void setQuaternionNormalisesAndResetRestoresDefaults() {
        Camera c = new Camera();
        c.setQuaternion(2f, 0f, 0f, 0f);
        assertArrayEquals(new float[]{1, 0, 0, 0}, c.getQuaternion(), EPS, "normalised on set");
        c.setPosition(4, 5, 6);
        c.rotate(100, 100);
        c.reset();
        assertArrayEquals(new float[]{0, 0, -3}, c.getPosition(), EPS);
        assertArrayEquals(new float[]{1, 0, 0, 0}, c.getQuaternion(), EPS);
    }

    @Test
    void targetIsOneUnitAheadOfThePosition() {
        Camera c = new Camera();
        c.setPosition(1, 2, 3);
        assertArrayEquals(new float[]{1, 2, 4}, c.getTarget(), EPS);
    }
}
