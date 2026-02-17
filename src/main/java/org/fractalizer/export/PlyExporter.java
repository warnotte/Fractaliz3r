package org.fractalizer.export;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Stanford PLY exporter focused on Point Clouds.
 * Exports vertices with positions, normals, and colors.
 * Optimized with Binary Little Endian format for speed and file size.
 */
public class PlyExporter {

    /**
     * Export mesh vertices as a point cloud in PLY format (Binary Little Endian).
     * This is much faster and produces smaller files than ASCII for large point clouds.
     */
    public static void exportPointCloud(File file, MarchingCubes.Mesh mesh) throws IOException {
        float[] pos = mesh.positions();
        float[] norms = mesh.normals();
        float[] colors = mesh.colors();
        int vertexCount = mesh.vertexCount();

        try (FileOutputStream fos = new FileOutputStream(file);
             BufferedOutputStream bos = new BufferedOutputStream(fos, 1024 * 1024)) {
            
            // 1. Write ASCII Header
            StringBuilder header = new StringBuilder();
            header.append("ply\n");
            header.append("format binary_little_endian 1.0\n");
            header.append("comment Created by Fractaliz3r Point Cloud Exporter (Binary)\n");
            header.append("element vertex ").append(vertexCount).append("\n");
            header.append("property float x\n");
            header.append("property float y\n");
            header.append("property float z\n");
            header.append("property float nx\n");
            header.append("property float ny\n");
            header.append("property float nz\n");
            header.append("property uchar red\n");
            header.append("property uchar green\n");
            header.append("property uchar blue\n");
            header.append("property uchar alpha\n");
            header.append("end_header\n");
            
            bos.write(header.toString().getBytes(StandardCharsets.US_ASCII));

            // 2. Write Binary Data
            // We use a ByteBuffer to handle Little Endian conversion
            ByteBuffer buffer = ByteBuffer.allocate(28); // 6 floats (24) + 4 uchars (4)
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            for (int i = 0; i < vertexCount; i++) {
                int pi = i * 3;
                int ci = i * 4;
                
                buffer.clear();
                
                // Positions
                buffer.putFloat(pos[pi]);
                buffer.putFloat(pos[pi + 1]);
                buffer.putFloat(pos[pi + 2]);
                
                // Normals
                buffer.putFloat(norms[pi]);
                buffer.putFloat(norms[pi + 1]);
                buffer.putFloat(norms[pi + 2]);
                
                // Colors (uchar 0-255)
                buffer.put((byte) (colors[ci] * 255));
                buffer.put((byte) (colors[ci + 1] * 255));
                buffer.put((byte) (colors[ci + 2] * 255));
                buffer.put((byte) (colors[ci + 3] * 255));
                
                bos.write(buffer.array());
            }
        }
    }
}
