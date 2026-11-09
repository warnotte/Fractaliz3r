package org.fractalizer.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Auto speed against a probe that answers when the test says so, as the GPU does: late.
 * What must hold: a move never covers more than the free distance left after the moves made
 * since the measure, however late it is; the camera waits for a measure it can trust (not too
 * old, taken along the way it is going); far from everything the dial is the step; and the
 * camera can always back out of the solid.
 */
class DiveSpeedTest {

    private static final double[] FORWARD = {0, 0, 1}, RIGHT = {1, 0, 0};

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final List<Consumer<DiveSpeed.Reading>> asked = new ArrayList<>();
    private final List<double[]> askedAlong = new ArrayList<>();
    private final DiveSpeed speed = new DiveSpeed((dir, r) -> { askedAlong.add(dir); asked.add(r); }, clock::get);

    private void answer(double distance, double clearance) { asked.remove(0).accept(new DiveSpeed.Reading(distance, clearance)); }

    @Test
    void waitsForAMeasureThenClosesAFractionOfTheGapAhead() {
        double[] cam = {0, 0, -3};
        assertEquals(0.0, speed.step(0.1, cam, FORWARD), "no measure yet: hold still");
        assertEquals(0.0, speed.step(0.1, cam, FORWARD), "still in flight");
        assertEquals(1, asked.size(), "one measure in flight, not one a frame");
        assertArrayEquals(FORWARD, askedAlong.get(0), "measured along the move");
        answer(2e-6, 1e-9);             // the estimate at the camera may be far too low: not used
        assertEquals(DiveSpeed.GAP_FRACTION * 0.1 * 2e-6, speed.step(0.1, cam, FORWARD), 1e-18);
    }

    @Test
    void nothingAheadOrFarAwayTheDialIsTheStep() {
        double[] cam = {0, 0, -30};
        speed.step(0.1, cam, FORWARD);
        answer(25.0, 20.0);
        assertEquals(0.1, speed.step(0.1, cam, FORWARD), 0.0);
        clock.addAndGet(DiveSpeed.STALE_NS + 1);
        speed.step(0.1, cam, FORWARD);
        answer(Double.POSITIVE_INFINITY, 20.0);
        assertEquals(0.1, speed.step(0.1, cam, FORWARD), 0.0, "a miss: open space");
    }

    @Test
    void aLateMeasureIsReducedByTheDistanceMovedSince() {
        double[] cam = {0, 0, 0};
        speed.step(0.5, cam, FORWARD);
        answer(1.0, 0.5);
        double[] moved = {0, 0, 0.6};                            // moved 0.6 since the measure
        assertEquals(DiveSpeed.GAP_FRACTION * 0.5 * 0.4, speed.step(0.5, moved, FORWARD), 1e-12, "the gap left is 0.4");
        assertEquals(1, asked.size(), "the move asked for a new measure");
        double[] further = {0, 0, 1.2};                          // past the old hit
        assertEquals(0.0, speed.step(0.5, further, FORWARD), "nothing left that is known to be free");
    }

    @Test
    void aMeasureAlongAnotherWayIsNotTrusted() {
        double[] cam = {0, 0, 0};
        speed.step(0.1, cam, FORWARD);
        answer(5.0, 1.0);
        assertEquals(0.0, speed.step(0.1, cam, RIGHT), "turned 90 degrees: wait for the way it now goes");
        assertArrayEquals(RIGHT, askedAlong.get(1));
        answer(0.01, 1.0);
        assertEquals(DiveSpeed.GAP_FRACTION * 0.1 * 0.01, speed.step(0.1, cam, RIGHT), 1e-15);
        double a = Math.toRadians(DiveSpeed.TURN_DEGREES / 2);
        double[] slightly = {Math.sin(a), 0, Math.cos(a)};
        assertTrue(speed.step(0.1, cam, RIGHT) > 0);
        assertEquals(0.0, speed.step(0.1, cam, FORWARD), "back to forward: the measure on hand is along the right");
        answer(5.0, 1.0);
        assertTrue(speed.step(0.1, cam, slightly) > 0, "a turn under the tolerance keeps the measure");
    }

    @Test
    void aStaleMeasureIsNotTrustedAtTheStartOfAMove() {
        double[] cam = {1, 2, 3};
        speed.step(0.1, cam, FORWARD);
        answer(0.5, 0.2);
        assertTrue(speed.step(0.1, cam, FORWARD) > 0);
        clock.addAndGet(DiveSpeed.STALE_NS + 1);                  // the scene may have changed meanwhile
        assertEquals(0.0, speed.step(0.1, cam, FORWARD));
        answer(0.25, 0.2);
        assertEquals(DiveSpeed.GAP_FRACTION * 0.1 * 0.25, speed.step(0.1, cam, FORWARD), 1e-15);
    }

    @Test
    void insideTheSolidItBacksOutAtTheScaleItCameIn() {
        double[] cam = {0, 0, 0};
        speed.step(0.1, cam, FORWARD);
        answer(2e-7, 1e-7);
        double[] inside = {0, 0, 1e-9};
        speed.step(0.1, inside, FORWARD);
        answer(0.0, -1e-8);
        assertEquals(DiveSpeed.GAP_FRACTION * 0.1 * 2e-7, speed.step(0.1, inside, FORWARD), 1e-20);
    }

    @Test
    void insideWithNoHistoryOrUnmeasurableUsesTheDial() {
        double[] cam = {0, 0, 0};
        speed.step(0.1, cam, FORWARD);
        answer(0.0, 0.0);
        assertEquals(0.1, speed.step(0.1, cam, FORWARD), 0.0, "on the surface from the start");

        DiveSpeed blind = new DiveSpeed((dir, r) -> asked.add(r), clock::get);
        blind.step(0.2, cam, FORWARD);
        answer(Double.NaN, Double.NaN);
        assertEquals(0.2, blind.step(0.2, cam, FORWARD), 0.0, "no program to measure with: manual");
    }
}
