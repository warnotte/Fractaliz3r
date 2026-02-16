package org.fractalizer.animation;

import java.util.*;

/**
 * An animation track manages keyframes for a single parameter.
 * Supports float, int, and float[] (vector) values.
 *
 * @param <T> The type of value being animated
 */
public class AnimationTrack<T> {

    private final String name;
    private final Class<T> valueType;
    private final TreeMap<Double, Keyframe<T>> keyframes;
    private T defaultValue;
    private boolean splineInterpolation = false;

    public AnimationTrack(String name, Class<T> valueType, T defaultValue) {
        this.name = name;
        this.valueType = valueType;
        this.defaultValue = defaultValue;
        this.keyframes = new TreeMap<>();
    }

    public String getName() {
        return name;
    }

    public Class<T> getValueType() {
        return valueType;
    }

    public T getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(T defaultValue) {
        this.defaultValue = defaultValue;
    }

    public boolean isSplineInterpolation() {
        return splineInterpolation;
    }

    public void setSplineInterpolation(boolean splineInterpolation) {
        this.splineInterpolation = splineInterpolation;
    }

    /**
     * Add or update a keyframe at the specified time.
     */
    public void setKeyframe(double time, T value) {
        setKeyframe(time, value, Easing.LINEAR);
    }

    public void setKeyframe(double time, T value, Easing easing) {
        // Remove any existing keyframe within tolerance to avoid duplicates
        removeKeyframeNear(time, 0.001);
        keyframes.put(time, new Keyframe<>(time, value, easing));
    }

    /**
     * Remove keyframe at approximately the specified time (within tolerance).
     */
    public void removeKeyframeNear(double time, double tolerance) {
        Double keyToRemove = null;
        for (Double key : keyframes.keySet()) {
            if (Math.abs(key - time) < tolerance) {
                keyToRemove = key;
                break;
            }
        }
        if (keyToRemove != null) {
            keyframes.remove(keyToRemove);
        }
    }

    /**
     * Remove a keyframe at the specified time.
     */
    public void removeKeyframe(double time) {
        keyframes.remove(time);
    }

    /**
     * Get the keyframe at exactly the specified time, or null.
     */
    public Keyframe<T> getKeyframeAt(double time) {
        return keyframes.get(time);
    }

    /**
     * Get all keyframes.
     */
    public Collection<Keyframe<T>> getKeyframes() {
        return Collections.unmodifiableCollection(keyframes.values());
    }

    /**
     * Get the number of keyframes.
     */
    public int getKeyframeCount() {
        return keyframes.size();
    }

    /**
     * Check if the track has any keyframes.
     */
    public boolean hasKeyframes() {
        return !keyframes.isEmpty();
    }

    /**
     * Get the time of the first keyframe, or 0 if none.
     */
    public double getStartTime() {
        return keyframes.isEmpty() ? 0 : keyframes.firstKey();
    }

    /**
     * Get the time of the last keyframe, or 0 if none.
     */
    public double getEndTime() {
        return keyframes.isEmpty() ? 0 : keyframes.lastKey();
    }

    /**
     * Get the interpolated value at the specified time.
     */
    @SuppressWarnings("unchecked")
    public T getValue(double time) {
        if (keyframes.isEmpty()) {
            return defaultValue;
        }

        // Get surrounding keyframes
        Map.Entry<Double, Keyframe<T>> floorEntry = keyframes.floorEntry(time);
        Map.Entry<Double, Keyframe<T>> ceilEntry = keyframes.ceilingEntry(time);

        // Before first keyframe
        if (floorEntry == null) {
            return ceilEntry.getValue().getValue();
        }

        // After last keyframe
        if (ceilEntry == null) {
            return floorEntry.getValue().getValue();
        }

        // Exactly on a keyframe
        if (floorEntry.getKey().equals(ceilEntry.getKey())) {
            return floorEntry.getValue().getValue();
        }

        // Interpolate between keyframes
        Keyframe<T> k1 = floorEntry.getValue();
        Keyframe<T> k2 = ceilEntry.getValue();

        double t1 = k1.getTime();
        double t2 = k2.getTime();
        double normalizedTime = (time - t1) / (t2 - t1);

        // Use the easing of the target keyframe
        Easing easing = k2.getEasing();

        // Spline interpolation for supported types
        if (splineInterpolation && (valueType == Float.class || valueType == Double.class || valueType == float[].class)) {
            return interpolateSpline(time, floorEntry, ceilEntry);
        }

        return interpolate(k1.getValue(), k2.getValue(), normalizedTime, easing);
    }

    @SuppressWarnings("unchecked")
    private T interpolate(T start, T end, double t, Easing easing) {
        if (valueType == Float.class) {
            float s = (Float) start;
            float e = (Float) end;
            return (T) Float.valueOf((float) easing.interpolate(s, e, t));
        } else if (valueType == Double.class) {
            double s = (Double) start;
            double e = (Double) end;
            return (T) Double.valueOf(easing.interpolate(s, e, t));
        } else if (valueType == Integer.class) {
            int s = (Integer) start;
            int e = (Integer) end;
            return (T) Integer.valueOf((int) Math.round(easing.interpolate(s, e, t)));
        } else if (valueType == float[].class) {
            float[] s = (float[]) start;
            float[] e = (float[]) end;
            return (T) easing.interpolate(s, e, t);
        } else {
            // No interpolation possible, return start value
            return start;
        }
    }

    // ========================================================================
    // Catmull-Rom Spline Interpolation
    // ========================================================================

    @SuppressWarnings("unchecked")
    private T interpolateSpline(double time, Map.Entry<Double, Keyframe<T>> floorEntry, Map.Entry<Double, Keyframe<T>> ceilEntry) {
        Double floorKey = floorEntry.getKey();
        Double ceilKey = ceilEntry.getKey();

        // P1 = floor, P2 = ceil
        T p1 = floorEntry.getValue().getValue();
        T p2 = ceilEntry.getValue().getValue();

        // P0 = keyframe before floor (or clamp to P1)
        Map.Entry<Double, Keyframe<T>> prevEntry = keyframes.lowerEntry(floorKey);
        T p0 = prevEntry != null ? prevEntry.getValue().getValue() : p1;

        // P3 = keyframe after ceil (or clamp to P2)
        Map.Entry<Double, Keyframe<T>> nextEntry = keyframes.higherEntry(ceilKey);
        T p3 = nextEntry != null ? nextEntry.getValue().getValue() : p2;

        double t1 = floorEntry.getValue().getTime();
        double t2 = ceilEntry.getValue().getTime();
        double normalizedTime = (time - t1) / (t2 - t1);

        // Apply easing from target keyframe
        Easing easing = ceilEntry.getValue().getEasing();
        double t = easing.apply(normalizedTime);

        if (valueType == Float.class) {
            float result = catmullRomScalar(
                (Float) p0, (Float) p1, (Float) p2, (Float) p3, (float) t);
            return (T) Float.valueOf(result);
        } else if (valueType == Double.class) {
            double result = catmullRomDouble(
                (Double) p0, (Double) p1, (Double) p2, (Double) p3, t);
            return (T) Double.valueOf(result);
        } else if (valueType == float[].class) {
            float[] a0 = (float[]) p0, a1 = (float[]) p1, a2 = (float[]) p2, a3 = (float[]) p3;
            float[] result = new float[a1.length];
            for (int i = 0; i < a1.length; i++) {
                result[i] = catmullRomScalar(a0[i], a1[i], a2[i], a3[i], (float) t);
            }
            // Normalize quaternions (length 4)
            if (result.length == 4) {
                float len = 0;
                for (float v : result) len += v * v;
                len = (float) Math.sqrt(len);
                if (len > 0.0001f) {
                    for (int i = 0; i < 4; i++) result[i] /= len;
                }
            }
            return (T) result;
        }

        // Fallback: linear
        return interpolate(p1, p2, normalizedTime, easing);
    }

    private static float catmullRomScalar(float p0, float p1, float p2, float p3, float t) {
        return 0.5f * ((2 * p1)
            + (-p0 + p2) * t
            + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t
            + (-p0 + 3 * p1 - 3 * p2 + p3) * t * t * t);
    }

    private static double catmullRomDouble(double p0, double p1, double p2, double p3, double t) {
        return 0.5 * ((2 * p1)
            + (-p0 + p2) * t
            + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t
            + (-p0 + 3 * p1 - 3 * p2 + p3) * t * t * t);
    }

    /**
     * Clear all keyframes.
     */
    public void clear() {
        keyframes.clear();
    }

    /**
     * Create a copy of this track.
     */
    public AnimationTrack<T> copy() {
        AnimationTrack<T> copy = new AnimationTrack<>(name, valueType, defaultValue);
        copy.splineInterpolation = this.splineInterpolation;
        for (Keyframe<T> kf : keyframes.values()) {
            copy.keyframes.put(kf.getTime(), kf.copy());
        }
        return copy;
    }

    @Override
    public String toString() {
        return String.format("AnimationTrack[%s, %d keyframes]", name, keyframes.size());
    }
}
