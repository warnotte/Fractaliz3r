package org.fractalizer.ui;

import org.fractalizer.engine.GLSLEngine;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The auto speed: a frame of a held key moves the camera by a fraction of the free distance
 * ahead of it, in the direction it is moving, so a dive closes the same fraction of the gap
 * every frame at every depth and never crosses the surface it is heading for. The speed dial
 * sets the fraction; far from everything the dial itself is the step, as with auto speed off.
 *
 * <p>The free distance is the probe's (the scene's own program marching a ray from the camera
 * along the motion), for any scene. It is not the scene's distance estimate at the camera: that
 * is a lower bound which can be far too low (3800 times at the Mandelbox's default camera,
 * DiveProbe), and a speed tied to it froze the camera. The march along the ray has stepped all
 * the way to the surface and knows where it is.
 *
 * <p>Asked every frame from the JavaFX thread, this never waits for the GPU. It uses the last
 * measure less how far the camera has moved since, so a late measure only slows the camera
 * down. One measure is in flight while the camera moves. A measure is not trusted at the start
 * of a move if it is older than {@link #STALE_NS} (the scene may have changed under a still
 * camera), nor once the motion has turned more than {@link #TURN_DEGREES} from where it was
 * measured. The camera then waits a frame or two for a fresh one.
 */
public final class DiveSpeed {

    /** At a dial of 1 a frame covers this fraction of the free distance. The default dial of
     *  0.1 then closes 3.8 % of the gap a frame, a decade a second at 60 frames per second
     *  (0.962^60 = 0.1). */
    static final double GAP_FRACTION = 0.38;
    static final long STALE_NS = 250_000_000L;
    static final double TURN_DEGREES = 2.0;
    private static final double TURN_COS = Math.cos(Math.toRadians(TURN_DEGREES));

    /**
     * What the probe measured from the camera along a direction.
     *
     * @param distance  to the surface along the direction, +infinity for none, NaN when the
     *                  scene cannot be measured (no compiled program yet)
     * @param clearance the scene's estimate at the camera: zero or less on or inside the solid
     */
    public record Reading(double distance, double clearance) {}

    /** Measures from the camera as it is now along a unit world direction and hands the
     *  reading back, from any thread. */
    public interface Probe { void measure(double[] direction, Consumer<Reading> onReading); }

    private record Measure(Reading reading, double[] at, double[] dir, long ns) {}

    private final Probe probe;
    private final LongSupplier clock;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile Measure last;
    private volatile double lastFree = Double.NaN;   // to back out of the solid at the scale it was entered

    public DiveSpeed(Probe probe, LongSupplier clock) {
        this.probe = probe;
        this.clock = clock;
    }

    /** The auto speed over the controller's scene: its probe along the move. */
    public static DiveSpeed over(GLSLFractalizerController controller) {
        return new DiveSpeed((dir, onReading) -> controller.probeAlong(dir, hits -> onReading.accept(reading(hits))), System::nanoTime);
    }

    /** A probe's answer for one ray as a reading: no program is NaN, a miss is +infinity. */
    public static Reading reading(GLSLEngine.ProbeHit[] hits) {
        if (hits == null) return new Reading(Double.NaN, Double.NaN);
        return new Reading(hits[0].hit() ? hits[0].distance() : Double.POSITIVE_INFINITY, hits[0].clearance());
    }

    /**
     * The length of this frame's move, in world units, for the speed {@code dial} with the
     * camera at {@code cam} moving along the unit world direction {@code dir}: 0 to wait for a
     * measure, the dial itself when the scene cannot be measured.
     */
    public double step(double dial, double[] cam, double[] dir) {
        long now = clock.getAsLong();
        Measure m = last;
        boolean fresh = m != null && now - m.ns < STALE_NS && dot(dir, m.dir) >= TURN_COS;
        if (!fresh || !same(cam, m.at) || !same(dir, m.dir)) request(cam, dir);
        if (!fresh) return 0.0;
        Reading r = m.reading;
        if (Double.isNaN(r.distance())) return dial;
        if (r.clearance() <= 0.0) {
            // On or inside the solid every direction is blocked at once. Move at the scale the
            // camera came in at; with none known, the dial is all there is.
            double p = lastFree;
            return Double.isNaN(p) ? dial : Math.min(dial, GAP_FRACTION * dial * p);
        }
        double free = r.distance() - distance(cam, m.at);
        return Math.min(dial, GAP_FRACTION * dial * Math.max(0.0, free));
    }

    /** One measure in flight at most. Its age counts from its answer: on a heavy scene the
     *  answer may take a whole viewport step, and the distance moved meanwhile is already
     *  taken off by {@link #step}. */
    private void request(double[] cam, double[] dir) {
        if (!inFlight.compareAndSet(false, true)) return;
        double[] at = cam.clone(), along = dir.clone();
        try {
            probe.measure(along, reading -> {
                if (reading.distance() > 0.0 && Double.isFinite(reading.distance()) && reading.clearance() > 0.0) {
                    lastFree = reading.distance();
                }
                last = new Measure(reading, at, along, clock.getAsLong());
                inFlight.set(false);
            });
        } catch (RuntimeException e) {
            inFlight.set(false);
            throw e;
        }
    }

    private static boolean same(double[] a, double[] b) { return a[0] == b[0] && a[1] == b[1] && a[2] == b[2]; }
    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
