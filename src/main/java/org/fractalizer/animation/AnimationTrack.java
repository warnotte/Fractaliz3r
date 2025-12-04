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
