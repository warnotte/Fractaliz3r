package org.fractalizer.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Keyframe evaluation: hold outside the keyed range, exact on a key, eased in between,
 * Catmull-Rom when the track opts in. The spline case checks the one property that
 * matters for a camera path, that a spline through evenly spaced collinear keys is
 * still a straight line, and that a spline over quaternions stays unit length.
 */
class AnimationTrackTest {

    @Test
    void emptyTrackReturnsItsDefault() {
        AnimationTrack<Float> t = new AnimationTrack<>("fov", Float.class, 60f);
        assertEquals(60f, t.getValue(12.5));
        assertFalse(t.hasKeyframes());
    }

    @Test
    void valueHoldsBeforeTheFirstAndAfterTheLastKey() {
        AnimationTrack<Float> t = new AnimationTrack<>("power", Float.class, 8f);
        t.setKeyframe(1.0, 2f);
        t.setKeyframe(3.0, 10f);
        assertEquals(2f, t.getValue(0.0), "before first key");
        assertEquals(10f, t.getValue(99.0), "after last key");
        assertEquals(1.0, t.getStartTime());
        assertEquals(3.0, t.getEndTime());
    }

    @Test
    void linearInterpolationBetweenTwoKeys() {
        AnimationTrack<Float> t = new AnimationTrack<>("power", Float.class, 8f);
        t.setKeyframe(0.0, 0f);
        t.setKeyframe(2.0, 10f);
        assertEquals(5f, t.getValue(1.0), 1e-6f);
        assertEquals(2.5f, t.getValue(0.5), 1e-6f);
        assertEquals(0f, t.getValue(0.0));
        assertEquals(10f, t.getValue(2.0));
    }

    @Test
    void easingOfTheTargetKeyShapesTheSegment() {
        AnimationTrack<Float> t = new AnimationTrack<>("power", Float.class, 8f);
        t.setKeyframe(0.0, 0f);
        t.setKeyframe(1.0, 10f, Easing.EASE_IN_QUAD);
        assertEquals(10f * 0.25f, t.getValue(0.5), 1e-5f, "ease-in quad at the midpoint is t²");
        assertEquals(0f, t.getValue(0.0));
        assertEquals(10f, t.getValue(1.0));
    }

    @Test
    void integerTracksRoundToTheNearestValue() {
        AnimationTrack<Integer> t = new AnimationTrack<>("iterations", Integer.class, 8);
        t.setKeyframe(0.0, 10);
        t.setKeyframe(1.0, 13);
        assertEquals(12, t.getValue(0.5));    // 11.5 rounds up
        assertEquals(11, t.getValue(0.3));    // 10.9
    }

    @Test
    void vectorTracksInterpolateEachComponent() {
        AnimationTrack<float[]> t = new AnimationTrack<>("camPos", float[].class, new float[]{0, 0, 0});
        t.setSplineInterpolation(false);
        t.setKeyframe(0.0, new float[]{0, 0, 0});
        t.setKeyframe(1.0, new float[]{2, 4, -6});
        assertArrayEquals(new float[]{1, 2, -3}, t.getValue(0.5), 1e-6f);
    }

    @Test
    void splineThroughCollinearKeysIsAStraightLineOnInteriorSegments() {
        // Catmull-Rom needs a neighbour on each side. The first and last segments clamp
        // the missing neighbour to the segment end, which bends them slightly; every
        // interior segment through evenly spaced collinear keys must be exactly linear.
        AnimationTrack<Float> t = new AnimationTrack<>("x", Float.class, 0f);
        t.setSplineInterpolation(true);
        for (int i = 0; i <= 4; i++) t.setKeyframe(i, (float) i);
        for (double time = 1; time <= 3; time += 0.125) {
            assertEquals((float) time, t.getValue(time), 1e-5f, "t=" + time);
        }
        // The clamped end segments still pass through their keys and stay inside them.
        assertEquals(0f, t.getValue(0.0));
        assertEquals(4f, t.getValue(4.0));
        assertTrue(t.getValue(0.5) > 0f && t.getValue(0.5) < 1f);
        assertTrue(t.getValue(3.5) > 3f && t.getValue(3.5) < 4f);
    }

    @Test
    void splineIsExactOnTheKeysAndSmoothBetweenThem() {
        AnimationTrack<Float> t = new AnimationTrack<>("x", Float.class, 0f);
        t.setSplineInterpolation(true);
        t.setKeyframe(0.0, 0f);
        t.setKeyframe(1.0, 1f);
        t.setKeyframe(2.0, 0f);
        t.setKeyframe(3.0, 1f);
        assertEquals(1f, t.getValue(1.0));
        assertEquals(0f, t.getValue(2.0));
        float mid = t.getValue(1.5);
        assertEquals(0.5f, mid, 1e-5f, "symmetric neighbours give the midpoint");
    }

    @Test
    void splineOverQuaternionsStaysUnitLength() {
        AnimationTrack<float[]> t = new AnimationTrack<>("camQuat", float[].class, new float[]{1, 0, 0, 0});
        t.setSplineInterpolation(true);
        float s = (float) Math.sqrt(0.5);
        t.setKeyframe(0.0, new float[]{1, 0, 0, 0});
        t.setKeyframe(1.0, new float[]{s, 0, s, 0});
        t.setKeyframe(2.0, new float[]{0, 0, 1, 0});
        t.setKeyframe(3.0, new float[]{-s, 0, s, 0});
        for (double time = 0; time <= 3; time += 0.1) {
            float[] q = t.getValue(time);
            float len = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
            assertEquals(1f, len, 1e-4f, "t=" + time);
        }
    }

    @Test
    void keyframesCanBeReplacedRemovedAndCopied() {
        AnimationTrack<Float> t = new AnimationTrack<>("x", Float.class, 0f);
        t.setKeyframe(1.0, 5f);
        t.setKeyframe(1.0, 7f);
        assertEquals(1, t.getKeyframeCount(), "same time replaces");
        assertEquals(7f, t.getKeyframeAt(1.0).getValue());

        t.setKeyframe(2.0, 9f);
        AnimationTrack<Float> copy = t.copy();
        t.removeKeyframeNear(2.02, 0.05);
        assertEquals(1, t.getKeyframeCount(), "removed within tolerance");
        assertEquals(2, copy.getKeyframeCount(), "the copy is independent");

        t.clear();
        assertFalse(t.hasKeyframes());
    }

    @Test
    void timelineRoutesValuesByTrackName() {
        Timeline tl = new Timeline(10.0, 30.0);
        tl.createTrack("fov", Float.class, 60f);
        tl.setKeyframe("fov", 0.0, 40f);
        tl.setKeyframe("fov", 2.0, 80f);
        assertEquals(60f, tl.<Float>getValue("fov", 1.0), 1e-6f);
        tl.setCurrentTime(2.0);
        assertEquals(80f, tl.<Float>getValue("fov"), 1e-6f);
        assertEquals(300, tl.getTotalFrames());
        assertTrue(tl.hasTrack("fov"));
        tl.removeTrack("fov");
        assertFalse(tl.hasTrack("fov"));
    }
}
