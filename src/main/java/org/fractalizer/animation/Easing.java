package org.fractalizer.animation;

import java.util.function.DoubleUnaryOperator;

/**
 * Easing functions for animation interpolation.
 * All functions take t in [0, 1] and return a value in [0, 1].
 */
public enum Easing {

    LINEAR(t -> t),

    // Quadratic
    EASE_IN_QUAD(t -> t * t),
    EASE_OUT_QUAD(t -> t * (2 - t)),
    EASE_IN_OUT_QUAD(t -> t < 0.5 ? 2 * t * t : -1 + (4 - 2 * t) * t),

    // Cubic
    EASE_IN_CUBIC(t -> t * t * t),
    EASE_OUT_CUBIC(t -> {
        double t1 = t - 1;
        return t1 * t1 * t1 + 1;
    }),
    EASE_IN_OUT_CUBIC(t -> t < 0.5 ? 4 * t * t * t : (t - 1) * (2 * t - 2) * (2 * t - 2) + 1),

    // Quartic
    EASE_IN_QUART(t -> t * t * t * t),
    EASE_OUT_QUART(t -> {
        double t1 = t - 1;
        return 1 - t1 * t1 * t1 * t1;
    }),
    EASE_IN_OUT_QUART(t -> t < 0.5 ? 8 * t * t * t * t : 1 - 8 * Math.pow(t - 1, 4)),

    // Quintic
    EASE_IN_QUINT(t -> t * t * t * t * t),
    EASE_OUT_QUINT(t -> {
        double t1 = t - 1;
        return 1 + t1 * t1 * t1 * t1 * t1;
    }),
    EASE_IN_OUT_QUINT(t -> t < 0.5 ? 16 * t * t * t * t * t : 1 + 16 * Math.pow(t - 1, 5)),

    // Sine
    EASE_IN_SINE(t -> 1 - Math.cos(t * Math.PI / 2)),
    EASE_OUT_SINE(t -> Math.sin(t * Math.PI / 2)),
    EASE_IN_OUT_SINE(t -> -(Math.cos(Math.PI * t) - 1) / 2),

    // Exponential
    EASE_IN_EXPO(t -> t == 0 ? 0 : Math.pow(2, 10 * (t - 1))),
    EASE_OUT_EXPO(t -> t == 1 ? 1 : 1 - Math.pow(2, -10 * t)),
    EASE_IN_OUT_EXPO(t -> {
        if (t == 0) return 0.0;
        if (t == 1) return 1.0;
        if (t < 0.5) return Math.pow(2, 20 * t - 10) / 2;
        return (2 - Math.pow(2, -20 * t + 10)) / 2;
    }),

    // Circular
    EASE_IN_CIRC(t -> 1 - Math.sqrt(1 - t * t)),
    EASE_OUT_CIRC(t -> Math.sqrt(1 - Math.pow(t - 1, 2))),
    EASE_IN_OUT_CIRC(t -> t < 0.5
        ? (1 - Math.sqrt(1 - 4 * t * t)) / 2
        : (Math.sqrt(1 - Math.pow(-2 * t + 2, 2)) + 1) / 2),

    // Elastic
    EASE_IN_ELASTIC(t -> {
        if (t == 0) return 0.0;
        if (t == 1) return 1.0;
        return -Math.pow(2, 10 * t - 10) * Math.sin((t * 10 - 10.75) * (2 * Math.PI / 3));
    }),
    EASE_OUT_ELASTIC(t -> {
        if (t == 0) return 0.0;
        if (t == 1) return 1.0;
        return Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI / 3)) + 1;
    }),
    EASE_IN_OUT_ELASTIC(t -> {
        if (t == 0) return 0.0;
        if (t == 1) return 1.0;
        if (t < 0.5) {
            return -(Math.pow(2, 20 * t - 10) * Math.sin((20 * t - 11.125) * (2 * Math.PI / 4.5))) / 2;
        }
        return (Math.pow(2, -20 * t + 10) * Math.sin((20 * t - 11.125) * (2 * Math.PI / 4.5))) / 2 + 1;
    }),

    // Back (overshoot)
    EASE_IN_BACK(t -> {
        double c1 = 1.70158;
        double c3 = c1 + 1;
        return c3 * t * t * t - c1 * t * t;
    }),
    EASE_OUT_BACK(t -> {
        double c1 = 1.70158;
        double c3 = c1 + 1;
        return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
    }),
    EASE_IN_OUT_BACK(t -> {
        double c1 = 1.70158;
        double c2 = c1 * 1.525;
        return t < 0.5
            ? (Math.pow(2 * t, 2) * ((c2 + 1) * 2 * t - c2)) / 2
            : (Math.pow(2 * t - 2, 2) * ((c2 + 1) * (t * 2 - 2) + c2) + 2) / 2;
    }),

    // Bounce
    EASE_OUT_BOUNCE(t -> {
        double n1 = 7.5625;
        double d1 = 2.75;
        if (t < 1 / d1) {
            return n1 * t * t;
        } else if (t < 2 / d1) {
            return n1 * (t -= 1.5 / d1) * t + 0.75;
        } else if (t < 2.5 / d1) {
            return n1 * (t -= 2.25 / d1) * t + 0.9375;
        } else {
            return n1 * (t -= 2.625 / d1) * t + 0.984375;
        }
    }),
    EASE_IN_BOUNCE(t -> 1 - EASE_OUT_BOUNCE.apply(1 - t)),
    EASE_IN_OUT_BOUNCE(t -> t < 0.5
        ? (1 - EASE_OUT_BOUNCE.apply(1 - 2 * t)) / 2
        : (1 + EASE_OUT_BOUNCE.apply(2 * t - 1)) / 2);

    private final DoubleUnaryOperator function;

    Easing(DoubleUnaryOperator function) {
        this.function = function;
    }

    /**
     * Apply the easing function to a normalized time value.
     * @param t Time in range [0, 1]
     * @return Eased value in range [0, 1]
     */
    public double apply(double t) {
        return function.applyAsDouble(Math.max(0, Math.min(1, t)));
    }

    /**
     * Interpolate between two values using this easing.
     * @param start Start value
     * @param end End value
     * @param t Normalized time [0, 1]
     * @return Interpolated value
     */
    public double interpolate(double start, double end, double t) {
        return start + (end - start) * apply(t);
    }

    /**
     * Interpolate a float array (e.g., vec3, vec4).
     */
    public float[] interpolate(float[] start, float[] end, double t) {
        if (start.length != end.length) {
            throw new IllegalArgumentException("Arrays must have same length");
        }
        float[] result = new float[start.length];
        double easedT = apply(t);
        for (int i = 0; i < start.length; i++) {
            result[i] = (float) (start[i] + (end[i] - start[i]) * easedT);
        }
        return result;
    }
}
