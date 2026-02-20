package org.fractalizer.test;

/**
 * Camera math utilities for programmatic scene creation.
 * Computes lookAt quaternions and cinematic camera paths.
 */
public class CameraUtils {

    /**
     * Compute a quaternion that makes the camera at 'eye' look toward 'target'.
     * Camera convention: identity quaternion = forward is +Z, up is +Y, right is +X.
     *
     * @return float[4] = {w, x, y, z}
     */
    public static float[] lookAt(float[] eye, float[] target) {
        return lookAt(eye, target, new float[]{0, 1, 0});
    }

    public static float[] lookAt(float[] eye, float[] target, float[] worldUp) {
        float fx = target[0] - eye[0];
        float fy = target[1] - eye[1];
        float fz = target[2] - eye[2];
        float fLen = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fLen < 1e-8f) return new float[]{1, 0, 0, 0};
        fx /= fLen; fy /= fLen; fz /= fLen;

        float rx = worldUp[1] * fz - worldUp[2] * fy;
        float ry = worldUp[2] * fx - worldUp[0] * fz;
        float rz = worldUp[0] * fy - worldUp[1] * fx;
        float rLen = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (rLen < 1e-8f) {
            rx = 1; ry = 0; rz = 0;
        } else {
            rx /= rLen; ry /= rLen; rz /= rLen;
        }

        float ux = fy * rz - fz * ry;
        float uy = fz * rx - fx * rz;
        float uz = fx * ry - fy * rx;

        float trace = rx + uy + fz;
        float w, x, y, z;
        if (trace > 0) {
            float s = (float) Math.sqrt(trace + 1.0f) * 2;
            w = 0.25f * s;
            x = (uz - fy) / s;
            y = (fx - rz) / s;
            z = (ry - ux) / s;
        } else if (rx > uy && rx > fz) {
            float s = (float) Math.sqrt(1.0f + rx - uy - fz) * 2;
            w = (uz - fy) / s;
            x = 0.25f * s;
            y = (ux + ry) / s;
            z = (fx + rz) / s;
        } else if (uy > fz) {
            float s = (float) Math.sqrt(1.0f + uy - rx - fz) * 2;
            w = (fx - rz) / s;
            x = (ux + ry) / s;
            y = 0.25f * s;
            z = (fy + uz) / s;
        } else {
            float s = (float) Math.sqrt(1.0f + fz - rx - uy) * 2;
            w = (ry - ux) / s;
            x = (fx + rz) / s;
            y = (fy + uz) / s;
            z = 0.25f * s;
        }

        float len = (float) Math.sqrt(w * w + x * x + y * y + z * z);
        return new float[]{w / len, x / len, y / len, z / len};
    }

    // ========================================================================
    // Camera path generators — all return float[numPoints][2][] = {pos, quat}
    // ========================================================================

    /**
     * Orbit around a center point at constant height.
     */
    public static float[][][] orbit(float[] center, float radius, float height,
                                     float startAngle, float endAngle, int numPoints) {
        float[][][] result = new float[numPoints][][];
        for (int i = 0; i < numPoints; i++) {
            float t = numPoints == 1 ? 0 : (float) i / (numPoints - 1);
            float angle = (float) Math.toRadians(startAngle + t * (endAngle - startAngle));

            float px = center[0] + radius * (float) Math.sin(angle);
            float py = center[1] + height;
            float pz = center[2] - radius * (float) Math.cos(angle);

            float[] pos = {px, py, pz};
            float[] quat = lookAt(pos, center);
            result[i] = new float[][]{pos, quat};
        }
        return result;
    }

    /**
     * Dolly shot: straight-line camera move while looking at a target.
     * Classic "approach" or "pull-away" shot.
     */
    public static float[][][] dolly(float[] startPos, float[] endPos, float[] target, int numPoints) {
        float[][][] result = new float[numPoints][][];
        for (int i = 0; i < numPoints; i++) {
            float t = numPoints == 1 ? 0 : (float) i / (numPoints - 1);
            float[] pos = lerp(startPos, endPos, t);
            float[] quat = lookAt(pos, target);
            result[i] = new float[][]{pos, quat};
        }
        return result;
    }

    /**
     * Spiral approach: orbit while closing in on the target.
     * Radius decreases linearly from startRadius to endRadius.
     * Height can also change (startHeight to endHeight).
     */
    public static float[][][] spiral(float[] center, float startRadius, float endRadius,
                                      float startHeight, float endHeight,
                                      float startAngle, float endAngle, int numPoints) {
        float[][][] result = new float[numPoints][][];
        for (int i = 0; i < numPoints; i++) {
            float t = numPoints == 1 ? 0 : (float) i / (numPoints - 1);
            float angle = (float) Math.toRadians(startAngle + t * (endAngle - startAngle));
            float radius = startRadius + t * (endRadius - startRadius);
            float height = startHeight + t * (endHeight - startHeight);

            float px = center[0] + radius * (float) Math.sin(angle);
            float py = center[1] + height;
            float pz = center[2] - radius * (float) Math.cos(angle);

            float[] pos = {px, py, pz};
            float[] quat = lookAt(pos, center);
            result[i] = new float[][]{pos, quat};
        }
        return result;
    }

    /**
     * Crane shot: arc from low to high (or high to low) while looking at target.
     * Useful for dramatic reveals.
     */
    public static float[][][] crane(float[] center, float radius, float angle,
                                     float startHeight, float endHeight, int numPoints) {
        float[][][] result = new float[numPoints][][];
        float rad = (float) Math.toRadians(angle);
        for (int i = 0; i < numPoints; i++) {
            float t = numPoints == 1 ? 0 : (float) i / (numPoints - 1);
            float height = startHeight + t * (endHeight - startHeight);

            float px = center[0] + radius * (float) Math.sin(rad);
            float py = center[1] + height;
            float pz = center[2] - radius * (float) Math.cos(rad);

            float[] pos = {px, py, pz};
            float[] quat = lookAt(pos, center);
            result[i] = new float[][]{pos, quat};
        }
        return result;
    }

    /**
     * Linearly interpolate between two positions.
     */
    public static float[] lerp(float[] a, float[] b, float t) {
        float[] r = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[i] + t * (b[i] - a[i]);
        }
        return r;
    }
}
