package org.fractalizer.engine;

import java.nio.FloatBuffer;
import java.util.Random;

import org.lwjgl.system.MemoryUtil;

/**
 * Generates a 64x64 blue noise texture (RG32F) using Mitchell's best-candidate algorithm.
 * Each channel (R, G) is an independent blue noise pattern with values in [0, 1].
 * The texture tiles seamlessly due to toroidal distance computation.
 */
public class BlueNoiseGenerator {

    private static final int SIZE = 64;
    private static final int TOTAL = SIZE * SIZE;
    private static final int CANDIDATES = 16;

    /**
     * Generate a 64x64 RG32F blue noise texture.
     * @return FloatBuffer with SIZE*SIZE*2 floats (RG pairs), caller must free with MemoryUtil.memFree()
     */
    public static FloatBuffer generate() {
        float[] channelR = generateChannel(42);
        float[] channelG = generateChannel(137);

        FloatBuffer buffer = MemoryUtil.memAllocFloat(TOTAL * 2);
        for (int i = 0; i < TOTAL; i++) {
            buffer.put(channelR[i]);
            buffer.put(channelG[i]);
        }
        buffer.flip();
        return buffer;
    }

    /**
     * Generate one channel of blue noise values using Mitchell's best-candidate algorithm.
     * For each pixel to place, generate CANDIDATES random positions and pick the one
     * farthest from all previously placed points (using toroidal distance for seamless tiling).
     */
    private static float[] generateChannel(long seed) {
        Random rng = new Random(seed);
        float[] result = new float[TOTAL];

        // Track placed point positions (x, y in [0, SIZE) space)
        int placed = 0;
        int[] placedX = new int[TOTAL];
        int[] placedY = new int[TOTAL];

        for (int i = 0; i < TOTAL; i++) {
            int bestIdx = -1;
            float bestMinDist = -1;

            for (int c = 0; c < CANDIDATES; c++) {
                int cx = rng.nextInt(SIZE);
                int cy = rng.nextInt(SIZE);
                int cIdx = cy * SIZE + cx;

                // Skip if this pixel is already placed
                if (result[cIdx] != 0 && i > 0) continue;

                // Find minimum toroidal distance to all placed points
                float minDist = Float.MAX_VALUE;
                for (int p = 0; p < placed; p++) {
                    float dist = toroidalDistSq(cx, cy, placedX[p], placedY[p]);
                    if (dist < minDist) minDist = dist;
                }

                if (minDist > bestMinDist) {
                    bestMinDist = minDist;
                    bestIdx = cIdx;
                }
            }

            // Fallback: if all candidates collided, find first empty pixel
            if (bestIdx == -1 || (result[bestIdx] != 0 && i > 0)) {
                for (int j = 0; j < TOTAL; j++) {
                    if (result[j] == 0 || i == 0) {
                        bestIdx = j;
                        break;
                    }
                }
            }

            // Value is the normalized rank: i / (TOTAL - 1)
            result[bestIdx] = (float) i / (TOTAL - 1);
            placedX[placed] = bestIdx % SIZE;
            placedY[placed] = bestIdx / SIZE;
            placed++;
        }

        return result;
    }

    /** Squared toroidal distance on a SIZE x SIZE grid */
    private static float toroidalDistSq(int x1, int y1, int x2, int y2) {
        int dx = Math.abs(x1 - x2);
        int dy = Math.abs(y1 - y2);
        if (dx > SIZE / 2) dx = SIZE - dx;
        if (dy > SIZE / 2) dy = SIZE - dy;
        return dx * dx + dy * dy;
    }
}
