package org.fractalizer.explore;

import org.fractalizer.explore.CameraExplorer.Candidate;
import org.fractalizer.explore.CameraExplorer.Settings;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The explorer's decisions — where the pivot is, how far to back off, how many views
 * come out, what cancelling does — are checked against an analytic sphere rendered on
 * the CPU. The GPU only ever changes what the images look like, not this logic.
 */
class CameraExplorerTest {

    /** A unit sphere at the origin, textured so a view of it has measurable detail. */
    static final class SphereRenderer implements ViewRenderer {
        final int w, h;
        int colourRenders = 0, depthRenders = 0;

        SphereRenderer(int w, int h) { this.w = w; this.h = h; }

        @Override public int width() { return w; }
        @Override public int height() { return h; }

        /** Ray-sphere hit distance from eye along dir, or NaN. Inside the sphere, the far wall. */
        static double hit(float[] eye, float[] dir) {
            double b = eye[0] * dir[0] + eye[1] * dir[1] + eye[2] * dir[2];
            double c = eye[0] * eye[0] + eye[1] * eye[1] + eye[2] * eye[2] - 1.0;
            double disc = b * b - c;
            if (disc < 0) return Double.NaN;
            double s = Math.sqrt(disc);
            double t0 = -b - s, t1 = -b + s;
            if (t0 > 1e-6) return t0;
            if (t1 > 1e-6) return t1;
            return Double.NaN;
        }

        float[][] basis(float[] eye, float[] target) {
            float[] f = CameraExplorer.normalize(CameraExplorer.sub(target, eye));
            float[] r = CameraExplorer.normalize(CameraExplorer.cross(new float[]{0, 1, 0}, f));
            if (CameraExplorer.len(r) < 1e-6f) r = new float[]{1, 0, 0};
            float[] u = CameraExplorer.cross(f, r);
            return new float[][]{f, r, u};
        }

        float[] dir(float[][] b, int x, int y, float fovDeg) {
            double t = Math.tan(Math.toRadians(fovDeg * 0.5));
            double px = ((x + 0.5) / w * 2 - 1) * t * ((double) w / h);
            double py = (1 - (y + 0.5) / h * 2) * t;
            float[] d = {
                (float) (b[0][0] + b[1][0] * px + b[2][0] * py),
                (float) (b[0][1] + b[1][1] * px + b[2][1] * py),
                (float) (b[0][2] + b[1][2] * px + b[2][2] * py)};
            return CameraExplorer.normalize(d);
        }

        @Override
        public float[] depth(float[] eye, float[] target, float fovDeg) {
            depthRenders++;
            float[][] b = basis(eye, target);
            float[] out = new float[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                double d = hit(eye, dir(b, x, y, fovDeg));
                if (Double.isNaN(d)) continue;
                out[y * w + x] = (float) (1.0 - Math.max(0, Math.min(1, Math.log(d + 0.1) / Math.log(15.0))));
            }
            return out;
        }

        @Override
        public BufferedImage colour(float[] eye, float[] target, float fovDeg, int samples) {
            colourRenders++;
            float[][] b = basis(eye, target);
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                float[] d = dir(b, x, y, fovDeg);
                double t = hit(eye, d);
                if (Double.isNaN(t)) { img.setRGB(x, y, 0x101018); continue; }
                double hx = eye[0] + d[0] * t, hy = eye[1] + d[1] * t, hz = eye[2] + d[2] * t;
                // a fine pattern on the surface, so closer views have more detail per pixel
                double pat = 0.5 + 0.5 * Math.sin(40 * hx) * Math.sin(40 * hy) * Math.sin(40 * hz);
                double shade = 0.3 + 0.7 * Math.max(0, hy * 0.6 + hz * -0.8);
                int v = (int) Math.round(255 * Math.max(0, Math.min(1, shade * (0.4 + 0.6 * pat))));
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
            return img;
        }
    }

    static class Collect implements CameraExplorer.Listener {
        final List<Candidate> seen = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();
        double lastProgress = -1;
        @Override public void candidate(Candidate c) { seen.add(c); }
        @Override public void status(double progress, String message) {
            assertTrue(progress >= lastProgress - 1e-9, "progress never goes backwards");
            assertTrue(progress <= 1.0 + 1e-9);
            lastProgress = progress;
            statuses.add(message);
        }
    }

    @Test
    void startingInsideTheSphereBacksOffUntilTheGlobalViewHasHealthyCoverage() {
        SphereRenderer r = new SphereRenderer(96, 54);
        Collect l = new Collect();
        CameraExplorer ex = new CameraExplorer(r, l, () -> false);
        // Inside the sphere, looking at its far wall: 100% coverage, must back off.
        List<Candidate> out = ex.explore(new float[]{0, 0, -0.5f}, new float[]{0, 0, 1}, 50f, new Settings(2, 3, 0.6f, 2));

        Candidate global = out.get(0);
        assertEquals("Global view", global.label());
        double cov = global.score().coverage();
        assertTrue(cov > 0.25 && cov < 0.85, "global coverage in the healthy band, was " + cov);
        assertTrue(CameraExplorer.len(global.eye()) > 1.0, "the global eye is outside the sphere");
        assertTrue(global.score().detail() > 0, "a textured surface scores some detail");
        assertTrue(l.statuses.stream().anyMatch(s -> s.startsWith("Auto-frame")), "reported the auto-frame steps");
    }

    @Test
    void producesTheGlobalViewPlusOneDivePerTargetAndStep() {
        SphereRenderer r = new SphereRenderer(96, 54);
        Collect l = new Collect();
        CameraExplorer ex = new CameraExplorer(r, l, () -> false);
        Settings s = new Settings(2, 3, 0.6f, 2);
        List<Candidate> out = ex.explore(new float[]{0, 0, -3f}, new float[]{0, 0, 1}, 50f, s);

        assertEquals(1 + s.targets() * s.steps(), out.size(), "one global + targets x steps");
        assertEquals(out.size(), l.seen.size(), "every candidate was reported as it came");
        assertEquals(1.0, l.lastProgress, 1e-9);

        // Each target's ladder gets strictly closer, by the shrink factor.
        for (int j = 0; j < s.targets(); j++) {
            for (int i = 1; i < s.steps(); i++) {
                Candidate a = out.get(1 + j * s.steps() + i - 1), b = out.get(1 + j * s.steps() + i);
                assertEquals(a.camDist() * s.shrink(), b.camDist(), 1e-4, a.label() + " -> " + b.label());
                assertArrayEquals(a.target(), b.target(), 1e-6f, "same surface point along one ladder");
            }
        }
        for (Candidate c : out) {
            assertFalse(Double.isNaN(c.aesthetic()), c.label() + " has a score");
            assertNotNull(c.thumbnail());
            assertEquals(96, c.thumbnail().getWidth());
        }
    }

    @Test
    void lookingAtNothingFallsBackToTheOriginAsPivot() {
        SphereRenderer r = new SphereRenderer(64, 36);
        Collect l = new Collect();
        CameraExplorer ex = new CameraExplorer(r, l, () -> false);
        // Off to the side, looking along +X: the centre ray misses the sphere entirely.
        List<Candidate> out = ex.explore(new float[]{0, 0, -3f}, new float[]{1, 0, 0}, 50f, new Settings(1, 2, 0.6f, 2));

        assertTrue(l.statuses.get(0).contains("origin"), l.statuses.get(0));
        Candidate global = out.get(0);
        assertArrayEquals(new float[]{0, 0, 0}, global.target(), 1e-6f, "the global view looks at the origin");
        assertTrue(global.score().coverage() > 0.25, "and sees the sphere");
    }

    @Test
    void cancellingStopsAtTheNextRender() {
        SphereRenderer r = new SphereRenderer(64, 36);
        AtomicBoolean cancel = new AtomicBoolean(false);
        Collect l = new Collect() {
            @Override public void candidate(Candidate c) {
                super.candidate(c);
                if (seen.size() == 2) cancel.set(true);
            }
        };
        CameraExplorer ex = new CameraExplorer(r, l, cancel::get);
        List<Candidate> out = ex.explore(new float[]{0, 0, -3f}, new float[]{0, 0, 1}, 50f, new Settings(3, 4, 0.6f, 2));

        assertEquals(2, out.size(), "nothing rendered after the cancel");
        assertEquals(2, l.seen.size());
    }

    @Test
    void frameScorerSeparatesFlatFromTexturedAndCountsCoverage() {
        int w = 40, h = 30;
        int[] flat = new int[w * h];
        java.util.Arrays.fill(flat, 0x808080);
        float[] half = new float[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) half[y * w + x] = (x < w / 2) ? 0.5f : 0f;

        FrameScorer.FrameScore f = FrameScorer.score(flat, half, w, h);
        assertEquals(0.0, f.detail(), 1e-9, "a flat image has no detail");
        assertEquals(0.5, f.coverage(), 0.06, "half the frame is surface");

        int[] checker = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) checker[y * w + x] = ((x + y) % 2 == 0) ? 0xFFFFFF : 0;
        FrameScorer.FrameScore c = FrameScorer.score(checker, half, w, h);
        assertTrue(c.detail() > 1000, "a checkerboard has detail: " + c.detail());
        assertTrue(c.aesthetic() > f.aesthetic());
        assertEquals(1.0, FrameScorer.coverage(new float[]{0.5f, 0.9f}), 1e-9);
        assertEquals(0.0, FrameScorer.coverage(new float[]{0f, 0.01f}), 1e-9);
    }
}
