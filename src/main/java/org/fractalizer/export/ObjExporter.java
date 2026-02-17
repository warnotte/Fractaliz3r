package org.fractalizer.export;

import java.io.*;
import java.util.Locale;

/**
 * Wavefront OBJ mesh exporter with vertex colors and normals.
 * Uses the "v x y z r g b" extension for per-vertex colors (supported by MeshLab, Blender).
 */
public class ObjExporter {

    public static void export(File file, MarchingCubes.Mesh mesh) throws IOException {
        float[] pos = mesh.positions();
        float[] norms = mesh.normals();
        float[] colors = mesh.colors();
        int[] indices = mesh.indices();
        int vertexCount = mesh.vertexCount();

        try (PrintWriter w = new PrintWriter(new BufferedWriter(new FileWriter(file), 65536))) {
            w.println("# Fractaliz3r Mesh Export");
            w.println("# Vertices: " + vertexCount + ", Triangles: " + mesh.triangleCount());
            w.println();

            // Vertices with colors (v x y z r g b)
            for (int i = 0; i < vertexCount; i++) {
                int pi = i * 3;
                int ci = i * 4;
                w.printf(Locale.ROOT, "v %f %f %f %f %f %f%n",
                        pos[pi], pos[pi + 1], pos[pi + 2],
                        colors[ci], colors[ci + 1], colors[ci + 2]);
            }

            w.println();

            // Normals
            for (int i = 0; i < vertexCount; i++) {
                int ni = i * 3;
                w.printf(Locale.ROOT, "vn %f %f %f%n",
                        norms[ni], norms[ni + 1], norms[ni + 2]);
            }

            w.println();

            // Faces (1-indexed)
            for (int i = 0; i < indices.length; i += 3) {
                int v1 = indices[i] + 1;
                int v2 = indices[i + 1] + 1;
                int v3 = indices[i + 2] + 1;
                w.printf(Locale.ROOT, "f %d//%d %d//%d %d//%d%n",
                        v1, v1, v2, v2, v3, v3);
            }
        }
    }
}
