package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.ui.GLSLFractalizerController;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A sample drawn in strips must be the same sample.
 *
 * {@code GLSLEngine.renderSampleBanded} draws one accumulation sample as N scissored
 * strips with an abort check between them; the progressive renderer uses it for samples
 * dearer than its batch slice so a camera move is not stuck behind a whole sample. This
 * probe renders the same first sample whole and in 1, 3, 8 and 16 strips and compares the
 * raw accumulation buffers, then aborts a banded sample after its first strip and checks
 * that the buffer is cleared before the next sample rather than left half-accumulated.
 *
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.BandedSampleProbe"
 *
 * Exit code 1 on any pixel difference or a partial sample surviving an abort.
 */
public class BandedSampleProbe {

    public static void main(String[] args) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        controller.setViewportSize(320, 180);
        controller.setFractalType(FractalType.MANDELBOX);
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        params.setPathTracingEnabled(true);          // the noisiest path: per-sample jitter must match too
        controller.renderStill(32, 18, 1, () -> false);   // compile
        var engine = controller.getEngine();
        engine.resize(320, 180);
        Map<String, Object> uniforms = controller.buildUniformsForProbe();

        boolean ok = true;
        engine.resetAccumulation();
        engine.renderSamples(uniforms, 1);
        float[] whole = engine.readRawImage();
        System.out.println("=== BandedSampleProbe: one sample, whole vs strips, 320x180 Mandelbox path traced ===");
        for (int bands : new int[]{1, 3, 8, 16}) {
            engine.resetAccumulation();
            boolean complete = engine.renderSampleBanded(uniforms, bands, () -> false);
            float[] banded = engine.readRawImage();
            int diff = 0;
            for (int i = 0; i < whole.length; i++) if (whole[i] != banded[i]) diff++;
            System.out.printf("  %2d strips: complete=%s  differing values %d / %d  samples=%d%n",
                    bands, complete, diff, whole.length, engine.getSampleCount());
            ok &= complete && diff == 0 && engine.getSampleCount() == 1;
        }

        // Abort after the first strip: the count must not move, and the next whole sample
        // must start from a cleared buffer (equal to a fresh single sample).
        engine.resetAccumulation();
        AtomicInteger calls = new AtomicInteger();
        boolean complete = engine.renderSampleBanded(uniforms, 8, () -> calls.incrementAndGet() >= 1);
        int countAfterAbort = engine.getSampleCount();
        engine.renderSamples(uniforms, 1);
        float[] afterAbort = engine.readRawImage();
        int diff = 0;
        for (int i = 0; i < whole.length; i++) if (whole[i] != afterAbort[i]) diff++;
        System.out.printf("  abort after strip 1: complete=%s  samples after abort=%d  next sample differs in %d values%n",
                complete, countAfterAbort, diff);
        ok &= !complete && countAfterAbort == 0 && diff == 0 && engine.getSampleCount() == 1;

        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        engine.close();
        Platform.exit();
        System.exit(ok ? 0 : 1);
    }
}
