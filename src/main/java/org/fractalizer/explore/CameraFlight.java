package org.fractalizer.explore;

import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.AbstractFractalParams;

/**
 * An eased flight from one camera pose to another: position and field of view are
 * interpolated with a smoothstep, orientation by spherical interpolation along the
 * shorter arc. Driven from the app's animation timer, one {@link #step} per frame.
 *
 * A pose is a position, a quaternion {@code {w, x, y, z}} (the {@link Camera}
 * convention) and a field of view in radians.
 */
public final class CameraFlight {

    public record Pose(float[] eye, float[] quaternion, float fov) {}

    private final Pose from, to;
    private final long durationNanos;
    private long startNanos = -1;

    public CameraFlight(Pose from, Pose to, double seconds) {
        this.from = new Pose(from.eye().clone(), unit(from.quaternion().clone()), from.fov());
        float[] q1 = unit(to.quaternion().clone());
        // q and -q are the same rotation; take the one on the shorter arc from q0.
        if (dot(this.from.quaternion(), q1) < 0) for (int i = 0; i < 4; i++) q1[i] = -q1[i];
        this.to = new Pose(to.eye().clone(), q1, to.fov());
        this.durationNanos = (long) (Math.max(0.05, seconds) * 1e9);
    }

    /** Pose at progress {@code t} in [0, 1], eased. */
    public Pose at(double t) {
        t = Math.max(0, Math.min(1, t));
        float e = (float) (t * t * (3 - 2 * t));
        float[] eye = new float[3];
        for (int i = 0; i < 3; i++) eye[i] = from.eye()[i] + (to.eye()[i] - from.eye()[i]) * e;
        return new Pose(eye, slerp(from.quaternion(), to.quaternion(), e), from.fov() + (to.fov() - from.fov()) * e);
    }

    /** Progress in [0, 1] for a clock reading; the first call starts the clock. */
    public double progress(long nowNanos) {
        if (startNanos < 0) startNanos = nowNanos;
        return Math.min(1.0, (nowNanos - startNanos) / (double) durationNanos);
    }

    /** Apply the pose for {@code nowNanos}; true once the flight has landed. */
    public boolean step(long nowNanos, Camera cam, AbstractFractalParams params) {
        double t = progress(nowNanos);
        Pose p = at(t);
        cam.setPosition(p.eye()[0], p.eye()[1], p.eye()[2]);
        cam.setQuaternion(p.quaternion()[0], p.quaternion()[1], p.quaternion()[2], p.quaternion()[3]);
        params.setFov(p.fov());
        return t >= 1.0;
    }

    /** Spherical interpolation between unit quaternions, assumed already on the same hemisphere. */
    static float[] slerp(float[] a, float[] b, float t) {
        double d = Math.max(-1.0, Math.min(1.0, dot(a, b)));
        float[] out = new float[4];
        if (d > 0.9995) {                       // nearly parallel: lerp and renormalise
            for (int i = 0; i < 4; i++) out[i] = a[i] + (b[i] - a[i]) * t;
            return unit(out);
        }
        double theta = Math.acos(d);
        double s = Math.sin(theta);
        double wa = Math.sin((1 - t) * theta) / s, wb = Math.sin(t * theta) / s;
        for (int i = 0; i < 4; i++) out[i] = (float) (a[i] * wa + b[i] * wb);
        return out;
    }

    static double dot(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]; }

    static float[] unit(float[] q) {
        double l = Math.sqrt(dot(q, q));
        if (l < 1e-9) return new float[]{1, 0, 0, 0};
        for (int i = 0; i < 4; i++) q[i] = (float) (q[i] / l);
        return q;
    }
}
