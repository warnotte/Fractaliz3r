package org.fractalizer.fractals;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a fractal parameter field as animatable.
 * Fields annotated with this are automatically discovered by reflection
 * and exposed to the AnimationManager for keyframe animation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Animatable {
    /** Display name shown in the timeline UI. */
    String display();
}
