package org.fractalizer.explore;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The structure factor must tell a carved solid from a ball of dust, which the detail
 * score alone cannot: a sheet at one depth scores one, isolated specks score zero, a dense
 * cloud of specks at unrelated depths scores near zero even though every pixel is covered.
 */
class FrameScorerTest {

    private static final int W = 64, H = 36;

    @Test
    void aContinuousSheetIsSolidAndSmooth() {
        float[] depth = new float[W * H];
        for (int y = 4; y < H - 4; y++) for (int x = 8; x < W - 8; x++) depth[y * W + x] = 0.5f + 0.0001f * x;
        FrameScorer.Structure s = FrameScorer.structure(depth, W, H);
        assertTrue(s.solid() > 0.85, "interior fraction of a filled rectangle: " + s.solid());
        assertEquals(1.0, s.smooth(), 1e-9);
        assertTrue(s.factor() > 0.85);
    }

    @Test
    void isolatedSpecksHaveNoInterior() {
        float[] depth = new float[W * H];
        for (int y = 1; y < H - 1; y += 2) for (int x = 1; x < W - 1; x += 2) depth[y * W + x] = 0.5f;   // a checkerboard of specks
        FrameScorer.Structure s = FrameScorer.structure(depth, W, H);
        assertEquals(0.0, s.solid(), 1e-9);
        assertEquals(0.0, s.factor(), 1e-9);
    }

    @Test
    void aDenseCloudAtUnrelatedDepthsIsCoveredButNotSmooth() {
        Random rnd = new Random(1);
        float[] depth = new float[W * H];
        for (int i = 0; i < depth.length; i++) depth[i] = 0.2f + 0.6f * rnd.nextFloat();   // every pixel a speck
        FrameScorer.Structure s = FrameScorer.structure(depth, W, H);
        assertTrue(s.solid() > 0.9, "every pixel is covered: " + s.solid());
        assertTrue(s.smooth() < 0.05, "but no neighbour agrees on depth: " + s.smooth());
        assertTrue(s.factor() < 0.05);
    }

    @Test
    void anEmptyFrameScoresZeroWithoutDividingByZero() {
        FrameScorer.Structure s = FrameScorer.structure(new float[W * H], W, H);
        assertEquals(0.0, s.solid(), 1e-9);
        assertEquals(0.0, s.smooth(), 1e-9);
    }

    @Test
    void aSilhouetteEdgeCountsAsSurfaceButNotAsInterior() {
        float[] depth = new float[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W / 2; x++) depth[y * W + x] = 0.5f;   // half the frame, one sheet
        FrameScorer.Structure s = FrameScorer.structure(depth, W, H);
        assertTrue(s.solid() > 0.9 && s.solid() < 1.0, "the column along the silhouette is not interior: " + s.solid());
        assertEquals(1.0, s.smooth(), 1e-9);
    }
}
