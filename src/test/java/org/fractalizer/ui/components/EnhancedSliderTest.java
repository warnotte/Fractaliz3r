package org.fractalizer.ui.components;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The logarithmic slider (focal distance, aperture, move speed): the knob moves along
 * log10, everything the panels call speaks the value itself. A panel that passed a
 * focal distance of 1e-7 and got the knob position back, or the reverse, would focus a
 * deep zoom at 10^-7 or at -7.
 */
class EnhancedSliderTest {

    @BeforeAll
    static void startToolkit() throws InterruptedException {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            assertTrue(started.await(30, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        } catch (IllegalStateException alreadyRunning) {
            // another test class started it in this JVM
        }
        Platform.setImplicitExit(false);
    }

    private static <T> T onFx(java.util.function.Supplier<T> work) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { f.complete(work.get()); } catch (Throwable t) { f.completeExceptionally(t); }
        });
        return f.get(30, TimeUnit.SECONDS);
    }

    @Test
    void logarithmicSliderSpeaksValuesAndMovesInDecades() throws Exception {
        onFx(() -> {
            EnhancedSlider s = EnhancedSlider.logarithmic("Focal Distance", 1e-9, 10, 2.5);
            assertEquals(2.5, s.getValue(), 1e-12, "initial value");
            assertEquals(Math.log10(2.5), s.getSlider().getValue(), 1e-12, "the knob holds log10");
            assertEquals(-9.0, s.getSlider().getMin(), 1e-12);
            assertEquals(1.0, s.getSlider().getMax(), 1e-12);

            List<Double> seen = new ArrayList<>();
            s.setOnAction(seen::add);
            s.setValue(1.37e-7);
            assertEquals(1.37e-7, s.getValue(), 1.37e-7 * 1e-12, "a deep value round-trips");
            assertEquals(1.37e-7, seen.get(seen.size() - 1), 1.37e-7 * 1e-12, "the action receives the value, not the knob");

            s.getSlider().setValue(-3.0);                     // the user drags the knob
            assertEquals(1e-3, seen.get(seen.size() - 1), 1e-15);

            s.setValue(1e-20);                                 // below the range: clamped
            assertEquals(1e-9, s.getValue(), 1e-21);
            s.setValue(0.0);                                   // an aperture of 0: the minimum
            assertEquals(1e-9, s.getValue(), 1e-21);
            s.setValue(1e6);                                   // above: clamped
            assertEquals(10.0, s.getValue(), 1e-12);
            return null;
        });
    }

    @Test
    void linearSliderIsUnchanged() throws Exception {
        onFx(() -> {
            EnhancedSlider s = new EnhancedSlider("Fudge", 0.1, 2.0, 1.0, false);
            List<Double> seen = new ArrayList<>();
            s.setOnAction(seen::add);
            s.setValue(0.5);
            assertEquals(0.5, s.getValue(), 0.0);
            assertEquals(0.5, s.getSlider().getValue(), 0.0);
            assertEquals(0.5, seen.get(seen.size() - 1), 0.0);
            return null;
        });
    }
}
