package org.fractalizer.export;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Minimal glTF 2.0 binary (.glb) writer.
 * Exports a single mesh with positions, normals, vertex colors (COLOR_0), and indices.
 * No external dependencies required.
 */
public class GlbExporter {

    private static final int GLB_MAGIC = 0x46546C67; // "glTF"
    private static final int GLB_VERSION = 2;
    private static final int CHUNK_TYPE_JSON = 0x4E4F534A; // "JSON"
    private static final int CHUNK_TYPE_BIN = 0x004E4942;  // "BIN\0"

    public static void export(File file, MarchingCubes.Mesh mesh) throws IOException {
        int vertexCount = mesh.vertexCount();
        int indexCount = mesh.indices().length;

        // Compute binary buffer sizes
        int posBytes = vertexCount * 3 * 4;   // VEC3 FLOAT
        int normBytes = vertexCount * 3 * 4;  // VEC3 FLOAT
        int colorBytes = vertexCount * 4 * 4; // VEC4 FLOAT
        int indexBytes = indexCount * 4;       // SCALAR UINT

        int binDataLength = posBytes + normBytes + colorBytes + indexBytes;
        int binPadding = (4 - (binDataLength % 4)) % 4;
        int binChunkLength = binDataLength + binPadding;

        // Compute min/max for POSITION accessor (required by spec)
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        float[] pos = mesh.positions();
        for (int i = 0; i < pos.length; i += 3) {
            minX = Math.min(minX, pos[i]); maxX = Math.max(maxX, pos[i]);
            minY = Math.min(minY, pos[i+1]); maxY = Math.max(maxY, pos[i+1]);
            minZ = Math.min(minZ, pos[i+2]); maxZ = Math.max(maxZ, pos[i+2]);
        }

        // Buffer view offsets
        int bvPosOffset = 0;
        int bvNormOffset = posBytes;
        int bvColorOffset = posBytes + normBytes;
        int bvIndexOffset = posBytes + normBytes + colorBytes;

        // Build JSON
        String json = buildJson(vertexCount, indexCount,
                posBytes, normBytes, colorBytes, indexBytes,
                bvPosOffset, bvNormOffset, bvColorOffset, bvIndexOffset,
                binChunkLength,
                minX, minY, minZ, maxX, maxY, maxZ);

        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int jsonPadding = (4 - (jsonBytes.length % 4)) % 4;
        int jsonChunkLength = jsonBytes.length + jsonPadding;

        // Total file size
        int totalLength = 12 + (8 + jsonChunkLength) + (8 + binChunkLength);

        // Write GLB
        ByteBuffer buf = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);

        // Header (12 bytes)
        buf.putInt(GLB_MAGIC);
        buf.putInt(GLB_VERSION);
        buf.putInt(totalLength);

        // JSON chunk
        buf.putInt(jsonChunkLength);
        buf.putInt(CHUNK_TYPE_JSON);
        buf.put(jsonBytes);
        for (int i = 0; i < jsonPadding; i++) buf.put((byte) 0x20); // pad with spaces

        // BIN chunk
        buf.putInt(binChunkLength);
        buf.putInt(CHUNK_TYPE_BIN);

        // Positions
        for (int i = 0; i < pos.length; i++) buf.putFloat(pos[i]);

        // Normals
        float[] norms = mesh.normals();
        for (int i = 0; i < norms.length; i++) buf.putFloat(norms[i]);

        // Colors (RGBA)
        float[] colors = mesh.colors();
        for (int i = 0; i < colors.length; i++) buf.putFloat(colors[i]);

        // Indices
        int[] indices = mesh.indices();
        for (int i = 0; i < indices.length; i++) buf.putInt(indices[i]);

        // BIN padding
        for (int i = 0; i < binPadding; i++) buf.put((byte) 0);

        buf.flip();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.getChannel().write(buf);
        }
    }

    private static String buildJson(int vertexCount, int indexCount,
                                     int posBytes, int normBytes, int colorBytes, int indexBytes,
                                     int bvPosOff, int bvNormOff, int bvColorOff, int bvIndexOff,
                                     int bufferLength,
                                     float minX, float minY, float minZ,
                                     float maxX, float maxY, float maxZ) {
        return String.format(Locale.ROOT, """
        {
          "asset":{"version":"2.0","generator":"Fractaliz3r"},
          "scene":0,
          "scenes":[{"nodes":[0]}],
          "nodes":[{"mesh":0}],
          "meshes":[{"primitives":[{
            "attributes":{"POSITION":0,"NORMAL":1,"COLOR_0":2},
            "indices":3,
            "material":0
          }]}],
          "materials":[{
            "pbrMetallicRoughness":{
              "metallicFactor":0.0,
              "roughnessFactor":0.8
            },
            "doubleSided":true
          }],
          "accessors":[
            {"bufferView":0,"componentType":5126,"count":%d,"type":"VEC3","min":[%f,%f,%f],"max":[%f,%f,%f]},
            {"bufferView":1,"componentType":5126,"count":%d,"type":"VEC3"},
            {"bufferView":2,"componentType":5126,"count":%d,"type":"VEC4"},
            {"bufferView":3,"componentType":5125,"count":%d,"type":"SCALAR"}
          ],
          "bufferViews":[
            {"buffer":0,"byteOffset":%d,"byteLength":%d},
            {"buffer":0,"byteOffset":%d,"byteLength":%d},
            {"buffer":0,"byteOffset":%d,"byteLength":%d},
            {"buffer":0,"byteOffset":%d,"byteLength":%d}
          ],
          "buffers":[{"byteLength":%d}]
        }
        """,
                vertexCount, minX, minY, minZ, maxX, maxY, maxZ,
                vertexCount,
                vertexCount,
                indexCount,
                bvPosOff, posBytes,
                bvNormOff, normBytes,
                bvColorOff, colorBytes,
                bvIndexOff, indexBytes,
                bufferLength
        );
    }
}
