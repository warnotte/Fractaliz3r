package org.fractalizer.engine;

/**
 * FPS-style camera using quaternions to avoid gimbal lock.
 * Supports free navigation inside fractals like a spaceship.
 *
 * <p>The position is kept in double precision. A float holds about seven significant
 * digits, so a camera one world unit from the origin cannot be placed closer than
 * ~1e-7 to anything: every move finer than that rounds away, and a dive past a zoom
 * of ~1e4 stalls. Doubles give nine more orders of magnitude. The float accessors are
 * kept for the many call sites that only need a rendering-precision copy; the shaders
 * that march in double read {@link #getPositionD()}.</p>
 */
public class Camera {

    // Position
    private double x, y, z;

    // Quaternion orientation (w, x, y, z)
    private float qw, qx, qy, qz;

    // Movement speed
    private float moveSpeed = 0.1f;
    private float rotateSpeed = 0.002f;

    public Camera() {
        // Default position
        this.x = 0.0;
        this.y = 0.0;
        this.z = -3.0;

        // Identity quaternion (no rotation)
        this.qw = 1f;
        this.qx = 0f;
        this.qy = 0f;
        this.qz = 0f;
    }

    /**
     * Move forward/backward along the camera's view direction.
     * @param amount Positive = forward, negative = backward
     */
    public void moveForward(float amount) {
        float[] forward = getForwardVector();
        double step = (double) amount * moveSpeed;
        x += forward[0] * step;
        y += forward[1] * step;
        z += forward[2] * step;
    }

    /**
     * Strafe left/right perpendicular to view direction.
     * @param amount Positive = right, negative = left
     */
    public void strafe(float amount) {
        float[] right = getRightVector();
        double step = (double) amount * moveSpeed;
        x += right[0] * step;
        y += right[1] * step;
        z += right[2] * step;
    }

    /**
     * Move up/down relative to camera orientation.
     * @param amount Positive = up, negative = down
     */
    public void moveUp(float amount) {
        float[] up = getUpVector();
        double step = (double) amount * moveSpeed;
        x += up[0] * step;
        y += up[1] * step;
        z += up[2] * step;
    }

    /**
     * Rotate camera based on mouse movement (yaw and pitch).
     * @param deltaX Mouse X movement (yaw - left/right)
     * @param deltaY Mouse Y movement (pitch - up/down)
     */
    public void rotate(float deltaX, float deltaY) {
        // Yaw rotation (around world Y axis for more intuitive control)
        // Positive deltaX = mouse moved right = look right
        if (deltaX != 0) {
            float yawAngle = deltaX * rotateSpeed;
            rotateAroundWorldY(yawAngle);
        }

        // Pitch rotation (around local X axis)
        // Positive deltaY = mouse moved down = look down
        if (deltaY != 0) {
            float pitchAngle = deltaY * rotateSpeed;
            rotateAroundLocalX(pitchAngle);
        }

        normalizeQuaternion();
    }

    /**
     * Roll the camera (for spaceship-like control).
     * @param amount Positive = roll right, negative = roll left
     */
    public void roll(float amount) {
        float rollAngle = amount * rotateSpeed * 10;
        rotateAroundLocalZ(rollAngle);
        normalizeQuaternion();
    }

    /**
     * Rotate around world Y axis (yaw).
     */
    private void rotateAroundWorldY(float angle) {
        float halfAngle = angle / 2f;
        float sinHalf = (float) Math.sin(halfAngle);
        float cosHalf = (float) Math.cos(halfAngle);

        // Quaternion for rotation around Y axis
        float rw = cosHalf;
        float rx = 0;
        float ry = sinHalf;
        float rz = 0;

        // Multiply: rotation * current (apply rotation in world space)
        float nw = rw * qw - rx * qx - ry * qy - rz * qz;
        float nx = rw * qx + rx * qw + ry * qz - rz * qy;
        float ny = rw * qy - rx * qz + ry * qw + rz * qx;
        float nz = rw * qz + rx * qy - ry * qx + rz * qw;

        qw = nw; qx = nx; qy = ny; qz = nz;
    }

    /**
     * Rotate around local X axis (pitch).
     */
    private void rotateAroundLocalX(float angle) {
        float halfAngle = angle / 2f;
        float sinHalf = (float) Math.sin(halfAngle);
        float cosHalf = (float) Math.cos(halfAngle);

        // Quaternion for rotation around X axis
        float rw = cosHalf;
        float rx = sinHalf;
        float ry = 0;
        float rz = 0;

        // Multiply: current * rotation (apply rotation in local space)
        float nw = qw * rw - qx * rx - qy * ry - qz * rz;
        float nx = qw * rx + qx * rw + qy * rz - qz * ry;
        float ny = qw * ry - qx * rz + qy * rw + qz * rx;
        float nz = qw * rz + qx * ry - qy * rx + qz * rw;

        qw = nw; qx = nx; qy = ny; qz = nz;
    }

    /**
     * Rotate around local Z axis (roll).
     */
    private void rotateAroundLocalZ(float angle) {
        float halfAngle = angle / 2f;
        float sinHalf = (float) Math.sin(halfAngle);
        float cosHalf = (float) Math.cos(halfAngle);

        // Quaternion for rotation around Z axis
        float rw = cosHalf;
        float rx = 0;
        float ry = 0;
        float rz = sinHalf;

        // Multiply: current * rotation (apply rotation in local space)
        float nw = qw * rw - qx * rx - qy * ry - qz * rz;
        float nx = qw * rx + qx * rw + qy * rz - qz * ry;
        float ny = qw * ry - qx * rz + qy * rw + qz * rx;
        float nz = qw * rz + qx * ry - qy * rx + qz * rw;

        qw = nw; qx = nx; qy = ny; qz = nz;
    }

    /**
     * Normalize quaternion to prevent drift.
     */
    private void normalizeQuaternion() {
        float length = (float) Math.sqrt(qw * qw + qx * qx + qy * qy + qz * qz);
        if (length > 0.0001f) {
            qw /= length;
            qx /= length;
            qy /= length;
            qz /= length;
        }
    }

    /**
     * Get forward direction vector from quaternion.
     */
    public float[] getForwardVector() {
        // Forward is -Z in OpenGL convention, rotated by quaternion
        float fx = 2f * (qx * qz + qw * qy);
        float fy = 2f * (qy * qz - qw * qx);
        float fz = 1f - 2f * (qx * qx + qy * qy);
        return new float[] { fx, fy, fz };
    }

    /**
     * Get right direction vector from quaternion.
     */
    public float[] getRightVector() {
        float rx = 1f - 2f * (qy * qy + qz * qz);
        float ry = 2f * (qx * qy + qw * qz);
        float rz = 2f * (qx * qz - qw * qy);
        return new float[] { rx, ry, rz };
    }

    /**
     * Get up direction vector from quaternion.
     */
    public float[] getUpVector() {
        float ux = 2f * (qx * qy - qw * qz);
        float uy = 1f - 2f * (qx * qx + qz * qz);
        float uz = 2f * (qy * qz + qw * qx);
        return new float[] { ux, uy, uz };
    }

    /**
     * Get a look-at target point for rendering.
     */
    public float[] getTarget() {
        float[] forward = getForwardVector();
        return new float[] {
            (float) (x + forward[0]),
            (float) (y + forward[1]),
            (float) (z + forward[2])
        };
    }

    // Getters and setters
    public float getX() { return (float) x; }
    public float getY() { return (float) y; }
    public float getZ() { return (float) z; }

    public void setPosition(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Full-precision placement: the way a deep-zoom camera or a saved scene sets it. */
    public void setPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Rendering-precision copy of the position. Lossy past ~1e-7 world units. */
    public float[] getPosition() {
        return new float[] { (float) x, (float) y, (float) z };
    }

    /** The position as held. What the double-precision march and the .frac file read. */
    public double[] getPositionD() {
        return new double[] { x, y, z };
    }

    public float[] getQuaternion() {
        return new float[] { qw, qx, qy, qz };
    }

    public void setQuaternion(float w, float x, float y, float z) {
        this.qw = w;
        this.qx = x;
        this.qy = y;
        this.qz = z;
        normalizeQuaternion();
    }

    public float getMoveSpeed() { return moveSpeed; }
    public void setMoveSpeed(float speed) { this.moveSpeed = speed; }

    public float getRotateSpeed() { return rotateSpeed; }
    public void setRotateSpeed(float speed) { this.rotateSpeed = speed; }

    /**
     * Reset camera to default position and orientation.
     */
    public void reset() {
        x = 0.0;
        y = 0.0;
        z = -3.0;
        qw = 1f;
        qx = 0f;
        qy = 0f;
        qz = 0f;
    }
}
