package org.fractalizer.ui.components;

import javafx.application.Platform;
import org.fractalizer.explore.CameraExplorer.Candidate;
import org.fractalizer.explore.FrameScorer.FrameScore;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.NodeGraphParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Explore dialog without a GPU: candidates fed in as the explorer would feed them
 * must come out best first, a click must move the host's camera to that candidate's
 * framing, and the controls the user is told about must be on the window.
 */
class ExploreDialogTest {

    static final class HostStub implements ExploreDialog.Host {
        final NodeGraphParams params = new NodeGraphParams(FractalType.MANDELBULB);
        final List<Boolean> exploringChanges = new ArrayList<>();
        float[] flownEye, flownTarget;
        float flownFov = -1;

        @Override public AbstractFractalParams params() { return params; }
        @Override public void exploringChanged(boolean exploring) { exploringChanges.add(exploring); }
        @Override public void flyTo(float[] eye, float[] target, float fovDeg) {
            flownEye = eye; flownTarget = target; flownFov = fovDeg;
        }
    }

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

    static Candidate candidate(String label, double detail, double coverage, float z) {
        BufferedImage img = new BufferedImage(ExploreDialog.THUMB_W, ExploreDialog.THUMB_H, BufferedImage.TYPE_INT_RGB);
        return new Candidate(label, new float[]{0, 0, z}, new float[]{0, 0, 0}, 50f, -z,
                new FrameScore(detail, coverage, 0.1), img);
    }

    @Test
    void candidatesAreShownBestFirstAndAClickFliesTheHostThere() throws Exception {
        HostStub host = new HostStub();
        ExploreDialog dialog = UiWiringTest.onFxThread(() -> new ExploreDialog(null, null, host));

        // Fed in a deliberately bad order: the grid must sort by the composed score.
        UiWiringTest.onFxThread(() -> {
            dialog.offer(candidate("dull", 1000, 0.55, -3f));
            dialog.offer(candidate("sharp, well framed", 9000, 0.55, -1f));
            dialog.offer(candidate("sharp but overflowing", 9000, 1.0, -0.5f));
            return null;
        });

        // Best first by the composed score: the overflowing view has the detail but sits
        // outside the coverage band, so even the dull well-framed one beats it.
        List<Candidate> shown = dialog.shownCandidates();
        assertEquals(List.of("sharp, well framed", "dull", "sharp but overflowing"),
                shown.stream().map(Candidate::label).toList(), "best first");
        assertTrue(shown.get(0).aesthetic() > shown.get(1).aesthetic());
        assertTrue(shown.get(1).aesthetic() > shown.get(2).aesthetic());

        UiWiringTest.onFxThread(() -> { dialog.clickShown(0); return null; });
        assertArrayEquals(new float[]{0, 0, -1f}, host.flownEye, "the click moved the camera to that view's eye");
        assertArrayEquals(new float[]{0, 0, 0}, host.flownTarget);
        assertEquals(50f, host.flownFov);
        assertTrue(host.exploringChanges.isEmpty(), "showing results never pauses the host; only a search does");
    }

    @Test
    void theControlsTheUserIsToldAboutAreOnTheWindow() throws Exception {
        HostStub host = new HostStub();
        ExploreDialog dialog = UiWiringTest.onFxThread(() -> new ExploreDialog(null, null, host));
        List<String> labels = new ArrayList<>();
        UiWiringTest.onFxThread(() -> { UiWiringTest.collectLabels(dialog.root(), labels); return null; });

        assertNotNull(UiWiringTest.findButton(dialog.root(), "Explore from current view"), "labels: " + labels);
        assertNotNull(UiWiringTest.findButton(dialog.root(), "Cancel"));
        for (String expected : List.of("Targets", "Steps", "Shrink", "Samples")) {
            assertTrue(labels.contains(expected), expected + " missing from " + labels);
        }
        assertFalse(dialog.isRunning());
    }
}
