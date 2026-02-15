package org.fractalizer.export;

import javafx.scene.paint.Color;
import org.fractalizer.fractals.AbstractFractalParams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Marching Cubes isosurface extraction with 2-slice memory optimization.
 * Extracts a triangle mesh from the fractal's distance field at DE=0.
 */
public class MarchingCubes {

    /** Output mesh data. */
    public record Mesh(float[] positions, float[] normals, float[] colors, int[] indices) {
        public int vertexCount() { return positions.length / 3; }
        public int triangleCount() { return indices.length / 3; }
    }

    /**
     * Extract an isosurface mesh from the given fractal parameters.
     *
     * @param params        Fractal parameters
     * @param resolution    Grid cells per axis (e.g. 128)
     * @param boundsHalf    Half-width of bounding cube (e.g. 2.5 means [-2.5, 2.5])
     * @param onProgress    Progress callback [0.0, 1.0] per Z-slice
     * @param cancelCheck   Returns true if export should be cancelled
     * @return Mesh or null if cancelled
     */
    public static Mesh extract(AbstractFractalParams params, int resolution, float boundsHalf,
                                Consumer<Double> onProgress, Supplier<Boolean> cancelCheck) {
        int res = resolution;
        int gridSize = res + 1; // grid points per axis
        float step = (2f * boundsHalf) / res;

        // 2-slice scalar field buffer: [2][gridSize][gridSize]
        float[][] slices = new float[2][gridSize * gridSize];

        // Vertex deduplication: edge key -> vertex index
        HashMap<Long, Integer> vertexMap = new HashMap<>();
        List<Float> positions = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<Float> vertColors = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();

        float normalEps = step * 0.5f;

        for (int z = 0; z < res; z++) {
            if (cancelCheck != null && cancelCheck.get()) return null;

            if (z == 0) {
                // Evaluate both slice 0 and slice 1
                evaluateSlice(slices[0], params, res, boundsHalf, step, 0);
                evaluateSlice(slices[1], params, res, boundsHalf, step, 1);
            } else {
                // Swap: old slice[1] becomes slice[0], evaluate new slice[1]
                float[] temp = slices[0];
                slices[0] = slices[1];
                slices[1] = temp;
                evaluateSlice(slices[1], params, res, boundsHalf, step, z + 1);
            }

            // Process all cells in this Z-slice
            for (int y = 0; y < res; y++) {
                for (int x = 0; x < res; x++) {
                    // 8 corner DE values for this cell
                    float[] corners = new float[8];
                    corners[0] = slices[0][ y      * gridSize + x    ];
                    corners[1] = slices[0][ y      * gridSize + x + 1];
                    corners[2] = slices[0][(y + 1) * gridSize + x + 1];
                    corners[3] = slices[0][(y + 1) * gridSize + x    ];
                    corners[4] = slices[1][ y      * gridSize + x    ];
                    corners[5] = slices[1][ y      * gridSize + x + 1];
                    corners[6] = slices[1][(y + 1) * gridSize + x + 1];
                    corners[7] = slices[1][(y + 1) * gridSize + x    ];

                    // Compute cube index (negative DE = inside surface)
                    int cubeIndex = 0;
                    for (int i = 0; i < 8; i++) {
                        if (corners[i] < 0) cubeIndex |= (1 << i);
                    }

                    int edgeBits = EDGE_TABLE[cubeIndex];
                    if (edgeBits == 0) continue;

                    // Corner positions in world space
                    float bx = -boundsHalf + x * step;
                    float by = -boundsHalf + y * step;
                    float bz = -boundsHalf + z * step;

                    // Interpolate vertices on active edges
                    int[] edgeVerts = new int[12];
                    for (int e = 0; e < 12; e++) {
                        if ((edgeBits & (1 << e)) == 0) continue;

                        int c0 = EDGE_CORNERS[e][0];
                        int c1 = EDGE_CORNERS[e][1];
                        float d0 = corners[c0];
                        float d1 = corners[c1];
                        float denom = d0 - d1;
                        float t = (Math.abs(denom) < 1e-12f) ? 0.5f : clamp01(d0 / denom);

                        float[] p0 = cornerPos(c0, bx, by, bz, step);
                        float[] p1 = cornerPos(c1, bx, by, bz, step);

                        float vx = p0[0] + t * (p1[0] - p0[0]);
                        float vy = p0[1] + t * (p1[1] - p0[1]);
                        float vz = p0[2] + t * (p1[2] - p0[2]);

                        // Skip non-finite vertex positions
                        if (!isFinite(vx) || !isFinite(vy) || !isFinite(vz)) {
                            edgeVerts[e] = -1;
                            continue;
                        }

                        // Edge deduplication key
                        long edgeKey = edgeKey(x, y, z, e, gridSize);
                        Integer existing = vertexMap.get(edgeKey);
                        if (existing != null) {
                            edgeVerts[e] = existing;
                        } else {
                            int vi = positions.size() / 3;
                            positions.add(vx); positions.add(vy); positions.add(vz);

                            // Compute normal
                            float[] n = FractalEvaluator.computeNormal(vx, vy, vz, params, normalEps);
                            if (!isFinite(n[0]) || !isFinite(n[1]) || !isFinite(n[2])) {
                                n = new float[]{0, 1, 0};
                            }
                            normals.add(n[0]); normals.add(n[1]); normals.add(n[2]);

                            // Compute color
                            FractalEvaluator.OrbitTrap trap = new FractalEvaluator.OrbitTrap();
                            FractalEvaluator.evaluate(vx, vy, vz, params, trap);
                            float[] factors = FractalEvaluator.computeFactors(params, trap);
                            Color color = FractalEvaluator.computeColor(trap, params, factors);
                            vertColors.add((float) color.getRed());
                            vertColors.add((float) color.getGreen());
                            vertColors.add((float) color.getBlue());
                            vertColors.add(1f); // alpha

                            vertexMap.put(edgeKey, vi);
                            edgeVerts[e] = vi;
                        }
                    }

                    // Generate triangles (skip any with invalid vertices)
                    int[] triRow = TRI_TABLE[cubeIndex];
                    for (int i = 0; i < triRow.length; i += 3) {
                        int a = edgeVerts[triRow[i]];
                        int b = edgeVerts[triRow[i + 1]];
                        int c = edgeVerts[triRow[i + 2]];
                        if (a < 0 || b < 0 || c < 0) continue;
                        indices.add(a);
                        indices.add(b);
                        indices.add(c);
                    }
                }
            }

            if (onProgress != null) {
                onProgress.accept((double)(z + 1) / res);
            }
        }

        // Convert lists to arrays
        float[] posArr = new float[positions.size()];
        float[] normArr = new float[normals.size()];
        float[] colorArr = new float[vertColors.size()];
        int[] idxArr = new int[indices.size()];

        for (int i = 0; i < posArr.length; i++) posArr[i] = positions.get(i);
        for (int i = 0; i < normArr.length; i++) normArr[i] = normals.get(i);
        for (int i = 0; i < colorArr.length; i++) colorArr[i] = vertColors.get(i);
        for (int i = 0; i < idxArr.length; i++) idxArr[i] = indices.get(i);

        return new Mesh(posArr, normArr, colorArr, idxArr);
    }

    private static void evaluateSlice(float[] slice, AbstractFractalParams params,
                                       int res, float boundsHalf, float step, int zIdx) {
        int gridSize = res + 1;
        float zPos = -boundsHalf + zIdx * step;
        for (int y = 0; y <= res; y++) {
            float yPos = -boundsHalf + y * step;
            for (int x = 0; x <= res; x++) {
                float xPos = -boundsHalf + x * step;
                slice[y * gridSize + x] = FractalEvaluator.evaluateSimple(xPos, yPos, zPos, params);
            }
        }
    }

    private static float clamp01(float x) {
        return Math.max(0f, Math.min(1f, x));
    }

    private static boolean isFinite(float v) {
        return Float.isFinite(v);
    }

    private static float[] cornerPos(int corner, float bx, float by, float bz, float step) {
        return switch (corner) {
            case 0 -> new float[]{bx, by, bz};
            case 1 -> new float[]{bx + step, by, bz};
            case 2 -> new float[]{bx + step, by + step, bz};
            case 3 -> new float[]{bx, by + step, bz};
            case 4 -> new float[]{bx, by, bz + step};
            case 5 -> new float[]{bx + step, by, bz + step};
            case 6 -> new float[]{bx + step, by + step, bz + step};
            case 7 -> new float[]{bx, by + step, bz + step};
            default -> new float[]{0, 0, 0};
        };
    }

    /**
     * Generate a unique edge key for vertex deduplication.
     * Each edge is identified by its lower-index corner + axis direction.
     */
    private static long edgeKey(int x, int y, int z, int edge, int gridSize) {
        // Map edge to: grid corner index + axis (0=X, 1=Y, 2=Z)
        int cx, cy, cz, axis;
        switch (edge) {
            case 0:  cx=x; cy=y; cz=z; axis=0; break;
            case 1:  cx=x+1; cy=y; cz=z; axis=1; break;
            case 2:  cx=x; cy=y+1; cz=z; axis=0; break;
            case 3:  cx=x; cy=y; cz=z; axis=1; break;
            case 4:  cx=x; cy=y; cz=z+1; axis=0; break;
            case 5:  cx=x+1; cy=y; cz=z+1; axis=1; break;
            case 6:  cx=x; cy=y+1; cz=z+1; axis=0; break;
            case 7:  cx=x; cy=y; cz=z+1; axis=1; break;
            case 8:  cx=x; cy=y; cz=z; axis=2; break;
            case 9:  cx=x+1; cy=y; cz=z; axis=2; break;
            case 10: cx=x+1; cy=y+1; cz=z; axis=2; break;
            case 11: cx=x; cy=y+1; cz=z; axis=2; break;
            default: cx=0; cy=0; cz=0; axis=0;
        }
        // Pack into long: axis(2) | cz(20) | cy(20) | cx(20)
        return ((long)axis << 60) | ((long)(cz & 0xFFFFF) << 40) | ((long)(cy & 0xFFFFF) << 20) | (cx & 0xFFFFF);
    }

    // Edge-to-corner mapping
    private static final int[][] EDGE_CORNERS = {
        {0,1}, {1,2}, {2,3}, {3,0},
        {4,5}, {5,6}, {6,7}, {7,4},
        {0,4}, {1,5}, {2,6}, {3,7}
    };

    // Paul Bourke's Marching Cubes edge table (256 entries)
    private static final int[] EDGE_TABLE = {
        0x0, 0x109, 0x203, 0x30a, 0x406, 0x50f, 0x605, 0x70c,
        0x80c, 0x905, 0xa0f, 0xb06, 0xc0a, 0xd03, 0xe09, 0xf00,
        0x190, 0x99, 0x393, 0x29a, 0x596, 0x49f, 0x795, 0x69c,
        0x99c, 0x895, 0xb9f, 0xa96, 0xd9a, 0xc93, 0xf99, 0xe90,
        0x230, 0x339, 0x33, 0x13a, 0x636, 0x73f, 0x435, 0x53c,
        0xa3c, 0xb35, 0x83f, 0x936, 0xe3a, 0xf33, 0xc39, 0xd30,
        0x3a0, 0x2a9, 0x1a3, 0xaa, 0x7a6, 0x6af, 0x5a5, 0x4ac,
        0xbac, 0xaa5, 0x9af, 0x8a6, 0xfaa, 0xea3, 0xda9, 0xca0,
        0x460, 0x569, 0x663, 0x76a, 0x66, 0x16f, 0x265, 0x36c,
        0xc6c, 0xd65, 0xe6f, 0xf66, 0x86a, 0x963, 0xa69, 0xb60,
        0x5f0, 0x4f9, 0x7f3, 0x6fa, 0x1f6, 0xff, 0x3f5, 0x2fc,
        0xdfc, 0xcf5, 0xfff, 0xef6, 0x9fa, 0x8f3, 0xbf9, 0xaf0,
        0x650, 0x759, 0x453, 0x55a, 0x256, 0x35f, 0x55, 0x15c,
        0xe5c, 0xf55, 0xc5f, 0xd56, 0xa5a, 0xb53, 0x859, 0x950,
        0x7c0, 0x6c9, 0x5c3, 0x4ca, 0x3c6, 0x2cf, 0x1c5, 0xcc,
        0xfcc, 0xec5, 0xdcf, 0xcc6, 0xbca, 0xac3, 0x9c9, 0x8c0,
        0x8c0, 0x9c9, 0xac3, 0xbca, 0xcc6, 0xdcf, 0xec5, 0xfcc,
        0xcc, 0x1c5, 0x2cf, 0x3c6, 0x4ca, 0x5c3, 0x6c9, 0x7c0,
        0x950, 0x859, 0xb53, 0xa5a, 0xd56, 0xc5f, 0xf55, 0xe5c,
        0x15c, 0x55, 0x35f, 0x256, 0x55a, 0x453, 0x759, 0x650,
        0xaf0, 0xbf9, 0x8f3, 0x9fa, 0xef6, 0xfff, 0xcf5, 0xdfc,
        0x2fc, 0x3f5, 0xff, 0x1f6, 0x6fa, 0x7f3, 0x4f9, 0x5f0,
        0xb60, 0xa69, 0x963, 0x86a, 0xf66, 0xe6f, 0xd65, 0xc6c,
        0x36c, 0x265, 0x16f, 0x66, 0x76a, 0x663, 0x569, 0x460,
        0xca0, 0xda9, 0xea3, 0xfaa, 0x8a6, 0x9af, 0xaa5, 0xbac,
        0x4ac, 0x5a5, 0x6af, 0x7a6, 0xaa, 0x1a3, 0x2a9, 0x3a0,
        0xd30, 0xc39, 0xf33, 0xe3a, 0x936, 0x83f, 0xb35, 0xa3c,
        0x53c, 0x435, 0x73f, 0x636, 0x13a, 0x33, 0x339, 0x230,
        0xe90, 0xf99, 0xc93, 0xd9a, 0xa96, 0xb9f, 0x895, 0x99c,
        0x69c, 0x795, 0x49f, 0x596, 0x29a, 0x393, 0x99, 0x190,
        0xf00, 0xe09, 0xd03, 0xc0a, 0xb06, 0xa0f, 0x905, 0x80c,
        0x70c, 0x605, 0x50f, 0x406, 0x30a, 0x203, 0x109, 0x0
    };

    // Paul Bourke's Marching Cubes triangle table (256 entries, variable length)
    private static final int[][] TRI_TABLE = {
        {},
        {0,8,3},
        {0,1,9},
        {1,8,3,9,8,1},
        {1,2,10},
        {0,8,3,1,2,10},
        {9,2,10,0,2,9},
        {2,8,3,2,10,8,10,9,8},
        {3,11,2},
        {0,11,2,8,11,0},
        {1,9,0,2,3,11},
        {1,11,2,1,9,11,9,8,11},
        {3,10,1,11,10,3},
        {0,10,1,0,8,10,8,11,10},
        {3,9,0,3,11,9,11,10,9},
        {9,8,10,10,8,11},
        {4,7,8},
        {4,3,0,7,3,4},
        {0,1,9,8,4,7},
        {4,1,9,4,7,1,7,3,1},
        {1,2,10,8,4,7},
        {3,4,7,3,0,4,1,2,10},
        {9,2,10,9,0,2,8,4,7},
        {2,10,9,2,9,7,2,7,3,7,9,4},
        {8,4,7,3,11,2},
        {11,4,7,11,2,4,2,0,4},
        {9,0,1,8,4,7,2,3,11},
        {4,7,11,9,4,11,9,11,2,9,2,1},
        {3,10,1,3,11,10,7,8,4},
        {1,11,10,1,4,11,1,0,4,7,11,4},
        {4,7,8,9,0,11,9,11,10,11,0,3},
        {4,7,11,4,11,9,9,11,10},
        {9,5,4},
        {9,5,4,0,8,3},
        {0,5,4,1,5,0},
        {8,5,4,8,3,5,3,1,5},
        {1,2,10,9,5,4},
        {3,0,8,1,2,10,4,9,5},
        {5,2,10,5,4,2,4,0,2},
        {2,10,5,3,2,5,3,5,4,3,4,8},
        {9,5,4,2,3,11},
        {0,11,2,0,8,11,4,9,5},
        {0,5,4,0,1,5,2,3,11},
        {2,1,5,2,5,8,2,8,11,4,8,5},
        {10,3,11,10,1,3,9,5,4},
        {4,9,5,0,8,1,8,10,1,8,11,10},
        {5,4,0,5,0,11,5,11,10,11,0,3},
        {5,4,8,5,8,10,10,8,11},
        {9,7,8,5,7,9},
        {9,3,0,9,5,3,5,7,3},
        {0,7,8,0,1,7,1,5,7},
        {1,5,3,3,5,7},
        {9,7,8,9,5,7,10,1,2},
        {10,1,2,9,5,0,5,3,0,5,7,3},
        {8,0,2,8,2,5,8,5,7,10,5,2},
        {2,10,5,2,5,3,3,5,7},
        {7,9,5,7,8,9,3,11,2},
        {9,5,7,9,7,2,9,2,0,2,7,11},
        {2,3,11,0,1,8,1,7,8,1,5,7},
        {11,2,1,11,1,7,7,1,5},
        {9,5,8,8,5,7,10,1,3,10,3,11},
        {5,7,0,5,0,9,7,11,0,1,0,10,11,10,0},
        {11,10,0,11,0,3,10,5,0,8,0,7,5,7,0},
        {11,10,5,7,11,5},
        {10,6,5},
        {0,8,3,5,10,6},
        {9,0,1,5,10,6},
        {1,8,3,1,9,8,5,10,6},
        {1,6,5,2,6,1},
        {1,6,5,1,2,6,3,0,8},
        {9,6,5,9,0,6,0,2,6},
        {5,9,8,5,8,2,5,2,6,3,2,8},
        {2,3,11,10,6,5},
        {11,0,8,11,2,0,10,6,5},
        {0,1,9,2,3,11,5,10,6},
        {5,10,6,1,9,2,9,11,2,9,8,11},
        {6,3,11,6,5,3,5,1,3},
        {0,8,11,0,11,5,0,5,1,5,11,6},
        {3,11,6,0,3,6,0,6,5,0,5,9},
        {6,5,9,6,9,11,11,9,8},
        {5,10,6,4,7,8},
        {4,3,0,4,7,3,6,5,10},
        {1,9,0,5,10,6,8,4,7},
        {10,6,5,1,9,7,1,7,3,7,9,4},
        {6,1,2,6,5,1,4,7,8},
        {1,2,5,5,2,6,3,0,4,3,4,7},
        {8,4,7,9,0,5,0,6,5,0,2,6},
        {7,3,9,7,9,4,3,2,9,5,9,6,2,6,9},
        {3,11,2,7,8,4,10,6,5},
        {5,10,6,4,7,2,4,2,0,2,7,11},
        {0,1,9,4,7,8,2,3,11,5,10,6},
        {9,2,1,9,11,2,9,4,11,7,11,4,5,10,6},
        {8,4,7,3,11,5,3,5,1,5,11,6},
        {5,1,11,5,11,6,1,0,11,7,11,4,0,4,11},
        {0,5,9,0,6,5,0,3,6,11,6,3,8,4,7},
        {6,5,9,6,9,11,4,7,9,7,11,9},
        {10,4,9,6,4,10},
        {4,10,6,4,9,10,0,8,3},
        {10,0,1,10,6,0,6,4,0},
        {8,3,1,8,1,6,8,6,4,6,1,10},
        {1,4,9,1,2,4,2,6,4},
        {3,0,8,1,2,9,2,4,9,2,6,4},
        {0,2,4,4,2,6},
        {8,3,2,8,2,4,4,2,6},
        {10,4,9,10,6,4,11,2,3},
        {0,8,2,2,8,11,4,9,10,4,10,6},
        {3,11,2,0,1,6,0,6,4,6,1,10},
        {6,4,1,6,1,10,4,8,1,2,1,11,8,11,1},
        {9,6,4,9,3,6,9,1,3,11,6,3},
        {8,11,1,8,1,0,11,6,1,9,1,4,6,4,1},
        {3,11,6,3,6,0,0,6,4},
        {6,4,8,11,6,8},
        {7,10,6,7,8,10,8,9,10},
        {0,7,3,0,10,7,0,9,10,6,7,10},
        {10,6,7,1,10,7,1,7,8,1,8,0},
        {10,6,7,10,7,1,1,7,3},
        {1,2,6,1,6,8,1,8,9,8,6,7},
        {2,6,9,2,9,1,6,7,9,0,9,3,7,3,9},
        {7,8,0,7,0,6,6,0,2},
        {7,3,2,6,7,2},
        {2,3,11,10,6,8,10,8,9,8,6,7},
        {2,0,7,2,7,11,0,9,7,6,7,10,9,10,7},
        {1,8,0,1,7,8,1,10,7,6,7,10,2,3,11},
        {11,2,1,11,1,7,10,6,1,6,7,1},
        {8,9,6,8,6,7,9,1,6,11,6,3,1,3,6},
        {0,9,1,11,6,7},
        {7,8,0,7,0,6,3,11,0,11,6,0},
        {7,11,6},
        {7,6,11},
        {3,0,8,11,7,6},
        {0,1,9,11,7,6},
        {8,1,9,8,3,1,11,7,6},
        {10,1,2,6,11,7},
        {1,2,10,3,0,8,6,11,7},
        {2,9,0,2,10,9,6,11,7},
        {6,11,7,2,10,3,10,8,3,10,9,8},
        {7,2,3,6,2,7},
        {7,0,8,7,6,0,6,2,0},
        {2,7,6,2,3,7,0,1,9},
        {1,6,2,1,8,6,1,9,8,8,7,6},
        {10,7,6,10,1,7,1,3,7},
        {10,7,6,1,7,10,1,8,7,1,0,8},
        {0,3,7,0,7,10,0,10,9,6,10,7},
        {7,6,10,7,10,8,8,10,9},
        {6,8,4,11,8,6},
        {3,6,11,3,0,6,0,4,6},
        {8,6,11,8,4,6,9,0,1},
        {9,4,6,9,6,3,9,3,1,11,3,6},
        {6,8,4,6,11,8,2,10,1},
        {1,2,10,3,0,11,0,6,11,0,4,6},
        {4,11,8,4,6,11,0,2,9,2,10,9},
        {10,9,3,10,3,2,9,4,3,11,3,6,4,6,3},
        {8,2,3,8,4,2,4,6,2},
        {0,4,2,4,6,2},
        {1,9,0,2,3,4,2,4,6,4,3,8},
        {1,9,4,1,4,2,2,4,6},
        {8,1,3,8,6,1,8,4,6,6,10,1},
        {10,1,0,10,0,6,6,0,4},
        {4,6,3,4,3,8,6,10,3,0,3,9,10,9,3},
        {10,9,4,6,10,4},
        {4,9,5,7,6,11},
        {0,8,3,4,9,5,11,7,6},
        {5,0,1,5,4,0,7,6,11},
        {11,7,6,8,3,4,3,5,4,3,1,5},
        {9,5,4,10,1,2,7,6,11},
        {6,11,7,1,2,10,0,8,3,4,9,5},
        {7,6,11,5,4,10,4,2,10,4,0,2},
        {3,4,8,3,5,4,3,2,5,10,5,2,11,7,6},
        {7,2,3,7,6,2,5,4,9},
        {9,5,4,0,8,6,0,6,2,6,8,7},
        {3,6,2,3,7,6,1,5,0,5,4,0},
        {6,2,8,6,8,7,2,1,8,4,8,5,1,5,8},
        {9,5,4,10,1,6,1,7,6,1,3,7},
        {1,6,10,1,7,6,1,0,7,8,7,0,9,5,4},
        {4,0,10,4,10,5,0,3,10,6,10,7,3,7,10},
        {7,6,10,7,10,8,5,4,10,4,8,10},
        {6,9,5,6,11,9,11,8,9},
        {3,6,11,0,6,3,0,5,6,0,9,5},
        {0,11,8,0,5,11,0,1,5,5,6,11},
        {6,11,3,6,3,5,5,3,1},
        {1,2,10,9,5,11,9,11,8,11,5,6},
        {0,11,3,0,6,11,0,9,6,5,6,9,1,2,10},
        {11,8,5,11,5,6,8,0,5,10,5,2,0,2,5},
        {6,11,3,6,3,5,2,10,3,10,5,3},
        {5,8,9,5,2,8,5,6,2,3,8,2},
        {9,5,6,9,6,0,0,6,2},
        {1,5,8,1,8,0,5,6,8,3,8,2,6,2,8},
        {1,5,6,2,1,6},
        {1,3,6,1,6,10,3,8,6,5,6,9,8,9,6},
        {10,1,0,10,0,6,9,5,0,5,6,0},
        {0,3,8,5,6,10},
        {10,5,6},
        {11,5,10,7,5,11},
        {11,5,10,11,7,5,8,3,0},
        {5,11,7,5,10,11,1,9,0},
        {10,7,5,10,11,7,9,8,1,8,3,1},
        {11,1,2,11,7,1,7,5,1},
        {0,8,3,1,2,7,1,7,5,7,2,11},
        {9,7,5,9,2,7,9,0,2,2,11,7},
        {7,5,2,7,2,11,5,9,2,3,2,8,9,8,2},
        {2,5,10,2,3,5,3,7,5},
        {8,2,0,8,5,2,8,7,5,10,2,5},
        {9,0,1,5,10,3,5,3,7,3,10,2},
        {9,8,2,9,2,1,8,7,2,10,2,5,7,5,2},
        {1,3,5,3,7,5},
        {0,8,7,0,7,1,1,7,5},
        {9,0,3,9,3,5,5,3,7},
        {9,8,7,5,9,7},
        {5,8,4,5,10,8,10,11,8},
        {5,0,4,5,11,0,5,10,11,11,3,0},
        {0,1,9,8,4,10,8,10,11,10,4,5},
        {10,11,4,10,4,5,11,3,4,9,4,1,3,1,4},
        {2,5,1,2,8,5,2,11,8,4,5,8},
        {0,4,11,0,11,3,4,5,11,2,11,1,5,1,11},
        {0,2,5,0,5,9,2,11,5,4,5,8,11,8,5},
        {9,4,5,2,11,3},
        {2,5,10,3,5,2,3,4,5,3,8,4},
        {5,10,2,5,2,4,4,2,0},
        {3,10,2,3,5,10,3,8,5,4,5,8,0,1,9},
        {5,10,2,5,2,4,1,9,2,9,4,2},
        {8,4,5,8,5,3,3,5,1},
        {0,4,5,1,0,5},
        {8,4,5,8,5,3,9,0,5,0,3,5},
        {9,4,5},
        {4,11,7,4,9,11,9,10,11},
        {0,8,3,4,9,7,9,11,7,9,10,11},
        {1,10,11,1,11,4,1,4,0,7,4,11},
        {3,1,4,3,4,8,1,10,4,7,4,11,10,11,4},
        {4,11,7,9,11,4,9,2,11,9,1,2},
        {9,7,4,9,11,7,9,1,11,2,11,1,0,8,3},
        {11,7,4,11,4,2,2,4,0},
        {11,7,4,11,4,2,8,3,4,3,2,4},
        {2,9,10,2,7,9,2,3,7,7,4,9},
        {9,10,7,9,7,4,10,2,7,8,7,0,2,0,7},
        {3,7,10,3,10,2,7,4,10,1,10,0,4,0,10},
        {1,10,2,8,7,4},
        {4,9,1,4,1,7,7,1,3},
        {4,9,1,4,1,7,0,8,1,8,7,1},
        {4,0,3,7,4,3},
        {4,8,7},
        {9,10,8,10,11,8},
        {3,0,9,3,9,11,11,9,10},
        {0,1,10,0,10,8,8,10,11},
        {3,1,10,11,3,10},
        {1,2,11,1,11,9,9,11,8},
        {3,0,9,3,9,11,1,2,9,2,11,9},
        {0,2,11,8,0,11},
        {3,2,11},
        {2,3,8,2,8,10,10,8,9},
        {9,10,2,0,9,2},
        {2,3,8,2,8,10,0,1,8,1,10,8},
        {1,10,2},
        {1,3,8,9,1,8},
        {0,9,1},
        {0,3,8},
        {}
    };
}
