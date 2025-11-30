package org.fractalizer.animation;

import java.util.Objects;

/**
 * A keyframe stores a value at a specific time with an easing function.
 * Supports various value types: Float, Integer, float[] (for vectors).
 *
 * @param <T> The type of value stored (Float, Integer, float[], etc.)
 */
public class Keyframe<T> implements Comparable<Keyframe<T>> {

    private double time;        // Time in seconds
    private T value;            // Value at this keyframe
    private Easing easing;      // Easing to use when interpolating TO this keyframe

    public Keyframe(double time, T value) {
        this(time, value, Easing.LINEAR);
    }

    public Keyframe(double time, T value, Easing easing) {
        this.time = time;
        this.value = value;
        this.easing = easing;
    }

    public double getTime() {
        return time;
    }

    public void setTime(double time) {
        this.time = time;
    }

    public T getValue() {
        return value;
    }

    public void setValue(T value) {
        this.value = value;
    }

    public Easing getEasing() {
        return easing;
    }

    public void setEasing(Easing easing) {
        this.easing = easing;
    }

    @Override
    public int compareTo(Keyframe<T> other) {
        return Double.compare(this.time, other.time);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Keyframe<?> keyframe = (Keyframe<?>) o;
        return Double.compare(keyframe.time, time) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(time);
    }

    /**
     * Create a copy of this keyframe.
     */
    @SuppressWarnings("unchecked")
    public Keyframe<T> copy() {
        T valueCopy = value;
        if (value instanceof float[] arr) {
            valueCopy = (T) arr.clone();
        }
        return new Keyframe<>(time, valueCopy, easing);
    }

    @Override
    public String toString() {
        return String.format("Keyframe[t=%.3f, value=%s, easing=%s]", time, value, easing);
    }
}
