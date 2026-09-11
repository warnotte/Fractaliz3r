package org.fractalizer.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cost model's strip planning against two kinds of scene, simulated: one whose cost
 * is throughput (a strip costs its rows' share of the sample), and one whose cost is
 * mostly latency (a strip costs about 100 ms whatever its size, the labyrinth at 1080p,
 * whose whole sample costs 6.9 s). The first must keep strips of one slice; the second
 * must not be cut into strips that each pay the latency for a few rows' work, which made
 * a sample cost thirty times what it does whole.
 */
class CostModelTest {

    private static final String KEY = "nodegraph#test";
    private static final int W = 1920, H = 1080;
    private static final long SLICE = CostModel.REFINE_SLICE_NS;

    /** Plans a whole sample as strips and returns the GPU time it took, feeding each
     *  strip's cost back as the scheduler does. */
    private static double sampleAsStrips(CostModel costs, double latencyNs, double nsPerRow, int[] stripsOut) {
        double total = 0;
        int y = 0, lastRows = 0, strips = 0;
        while (y < H) {
            int rows = Math.min(costs.rowsPerStrip(KEY, false, W, H, lastRows, SLICE), H - y);
            double cost = latencyNs + rows * nsPerRow;
            costs.record(KEY, false, (long) W * rows, (long) cost);
            total += cost;
            y += rows;
            lastRows = rows;
            strips++;
        }
        stripsOut[0] = strips;
        return total;
    }

    @Test
    void throughputBoundSceneKeepsStripsOfOneSlice() {
        // Albedo 0.39 at 1080p: a 190 ms sample, latency about 5 ms
        double latency = 5e6, perRow = 190e6 / H;
        CostModel costs = new CostModel();
        costs.record(KEY, true, 960L * 540, 12_000_000L);      // a preview, as the refinement starts from
        int[] strips = new int[1];
        for (int s = 0; s < 3; s++) sampleAsStrips(costs, latency, perRow, strips);
        double sample = sampleAsStrips(costs, latency, perRow, strips);
        assertTrue(strips[0] >= 3 && strips[0] <= 8, "a 190 ms sample is a few slices, got " + strips[0] + " strips");
        int rows = costs.rowsPerStrip(KEY, false, W, H, 200, SLICE);
        double strip = latency + rows * perRow;
        assertTrue(strip <= SLICE * 1.15, "a strip stays within the slice: " + strip / 1e6 + " ms");
        assertTrue(sample <= (190e6 + latency) * 1.3, "strips cost at most 30 % over the sample whole: " + sample / 1e6 + " ms");
    }

    @Test
    void latencyBoundSceneIsNotCutIntoLatencyOnlyStrips() {
        // the labyrinth at 1080p: 100 ms whatever the strip, 6.9 s the whole sample
        double latency = 100e6, perRow = (6900e6 - 100e6) / H;
        CostModel costs = new CostModel();
        costs.record(KEY, true, 960L * 540, 15_000_000L);
        int[] strips = new int[1];
        double first = sampleAsStrips(costs, latency, perRow, strips);
        assertTrue(first < 2.5 * 6900e6, "even the first sample, learning, costs under 2.5 times the whole: " + first / 1e6 + " ms");
        for (int s = 0; s < 2; s++) sampleAsStrips(costs, latency, perRow, strips);
        double sample = sampleAsStrips(costs, latency, perRow, strips);
        assertTrue(sample <= 1.6 * 6900e6, "a sample as strips costs at most about half as much again as whole: " + sample / 1e6 + " ms");
        assertTrue(strips[0] <= 40, "a few dozen strips at most: " + strips[0]);
        int rows = costs.rowsPerStrip(KEY, false, W, H, 1000, SLICE);
        double strip = latency + rows * perRow;
        assertTrue(strip <= CostModel.STRIP_CEILING_NS * 1.05, "a strip is planned to the ceiling: " + strip / 1e6 + " ms");
        assertTrue(strip >= CostModel.STRIP_CEILING_NS * 0.8, "and fills it: " + strip / 1e6 + " ms");
        assertTrue(costs.latencyKnown(KEY));
        assertEquals(latency, costs.latencyNs(KEY, false), latency * 0.1);
        assertEquals(perRow / W, costs.nsPerPixel(KEY, false), perRow / W * 0.1);
    }

    @Test
    void wholeSampleCostIncludesTheLatency() {
        double latency = 70e6, perRow = 30e6 / H;   // 30 ms of work over the frame, 70 ms of latency
        CostModel costs = new CostModel();
        for (int rows : new int[]{100, 400, 800}) costs.record(KEY, false, (long) W * rows, (long) (latency + rows * perRow));
        assertTrue(costs.latencyKnown(KEY));
        assertTrue(costs.sampleExceedsStep(KEY, false, W, H, SLICE), "100 ms does not fit a 60 ms slice");
        assertFalse(costs.sampleExceedsStep(KEY, false, W, H, 110_000_000L), "100 ms fits 110 ms");
        assertEquals(2, costs.samplesPerStep(KEY, false, W, H, 250_000_000L), "each sample costs 100 ms, two fit 250 ms");
    }

    @Test
    void unknownCostsPlanAsBefore() {
        CostModel costs = new CostModel();
        assertEquals(H / 64, costs.rowsPerStrip(KEY, false, W, H, 0, SLICE));
        assertEquals(1, costs.samplesPerStep(KEY, false, W, H, SLICE));
        assertFalse(costs.sampleExceedsStep(KEY, false, W, H, SLICE));
        assertEquals(0, costs.latencyNs(KEY, false));
    }
}
