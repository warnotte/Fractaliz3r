package org.fractalizer.fractals;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Descriptor for an animatable parameter, built automatically via reflection
 * from {@link Animatable} annotations on fractal parameter fields.
 */
public record AnimatableParameter(
    String name,              // Java field name ("power")
    String displayName,       // @Animatable.display ("Power")
    Class<?> valueType,       // float -> Float.class, int -> Integer.class
    Supplier<Object> getter,  // Reads the current value via reflection
    Consumer<Object> setter   // Writes the value via reflection
) {
    /**
     * Build the scoped track name for this parameter within a given fractal kernel.
     * Example: "mandelbulb.power"
     */
    public String trackName(String kernelName) {
        return kernelName + "." + name;
    }
}
