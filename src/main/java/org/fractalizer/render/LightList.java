package org.fractalizer.render;

import org.fractalizer.graph.CSGNode;
import org.fractalizer.graph.EffectNode;
import org.fractalizer.graph.GraphCompiler;
import org.fractalizer.graph.GraphNode;
import org.fractalizer.graph.MaterialNode;
import org.fractalizer.graph.PrimitiveNode;
import org.fractalizer.graph.TransformNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The scene's lights as one table for the shaders (lights.glsl, SSBO binding 7).
 *
 * <p>Today the table holds the node graph's <em>emitters</em>: a MaterialNode with a solid
 * colour and an emission over a sphere or a box, reached through standard transforms
 * (translation, rotation, uniform scale) and unions only. Their world shape is analytic,
 * so the path tracer draws points on them directly instead of finding them by chance,
 * and a path that hits one is weighted against that draw. Anything else emissive (a
 * fractal, a primitive under a twist or a subtraction, a palette-coloured material) is
 * not listed and stays what it was: found by chance.
 *
 * <p>Twenty-four floats per light, all floats (std430, no int/float mixing), the layout
 * of {@code LightData} in lights.glsl.
 */
public final class LightList {

    public static final int FLOATS = 24;
    public static final int SUN = 0, BEAM = 1, POINT = 2, SPOT = 3, EMITTER_SPHERE = 4, EMITTER_BOX = 5;

    /** The table, its size, the sum of power over its emitters (the path tracer's draw)
     *  and over every light that sends photons (the photon pass's draw). */
    public record Table(float[] data, int count, float emitterPower, float powerTotal) {
        public static final Table EMPTY = new Table(null, 0, 0f, 0f);
    }

    /** For the probe: leave the emitters to be found by chance, as before the list. */
    public static volatile boolean emittersDisabled = Boolean.getBoolean("fractalizer.noEmitterSampling");

    private LightList() {}

    public static Table emitters(GraphNode root) {
        return build(null, null, null, root, false);
    }

    /**
     * The scene's lights: the graph's emitters always; with {@code photons} (BIDIR) the sun
     * and the beam too, the lights the photon pass draws from. Point and spot lights send
     * no photons and are not listed: their draw stands alone. The additional light's
     * position and axis are resolved here, camera-relative or fixed in the world, as the
     * shader resolves them for its own draw.
     */
    public static Table build(org.fractalizer.fractals.AbstractFractalParams p, float[] camPos, float[] camQuatWxyz,
                              GraphNode root, boolean photons) {
        List<float[]> entries = new ArrayList<>();
        if (root != null && !emittersDisabled) {
            List<MaterialNode> mats = GraphCompiler.materialNodes(root);
            collect(root, IDENTITY, new float[3], 1f, true, mats, entries);
        }
        if (photons && p != null) {
            float[] sun = sunEntry(p);
            if (sun != null) entries.add(sun);
            float[] beam = beamEntry(p, camPos, camQuatWxyz);
            if (beam != null) entries.add(beam);
            float[] extra = extraEntry(p, camPos, camQuatWxyz);
            if (extra != null) entries.add(extra);
        }
        if (entries.isEmpty()) return Table.EMPTY;
        float[] data = new float[entries.size() * FLOATS];
        float emitterPower = 0f, total = 0f;
        for (int i = 0; i < entries.size(); i++) {
            float[] e = entries.get(i);
            System.arraycopy(e, 0, data, i * FLOATS, FLOATS);
            if (e[0] >= EMITTER_SPHERE) emitterPower += e[18];
            total += e[18];
        }
        return new Table(data, entries.size(), emitterPower, total);
    }

    public static boolean hasEmitters(GraphNode root) { return emitters(root).count() > 0; }

    private static float lum(float r, float g, float b) { return 0.2126f * r + 0.7152f * g + 0.0722f * b; }

    /** The sun: its direction and irradiance; its photons leave the emission square, so its
     *  power is that irradiance over the square's area. */
    private static float[] sunEntry(org.fractalizer.fractals.AbstractFractalParams p) {
        float[] c = {p.getLightR(), p.getLightG(), p.getLightB()};
        float intensity = p.getLightIntensity();
        if (intensity <= 0f) return null;
        float[] d = {p.getLightX(), p.getLightY(), p.getLightZ()};
        float len = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        if (len < 1e-6f) return null;
        float[] e = new float[FLOATS];
        e[0] = SUN;
        e[4] = d[0] / len; e[5] = d[1] / len; e[6] = d[2] / len;
        e[7] = c[0]; e[8] = c[1]; e[9] = c[2];
        e[10] = intensity;
        e[15] = p.getShadowSoftness();
        float side = 2f * p.getCausticRadius();
        e[18] = lum(c[0], c[1], c[2]) * intensity * side * side;
        e[19] = -1f;
        return e;
    }

    /** The additional light as a beam: its source point and axis in the world, its radius,
     *  length and edge; its power the irradiance over the disk. */
    private static float[] beamEntry(org.fractalizer.fractals.AbstractFractalParams p, float[] camPos, float[] q) {
        if (p.getExtraLightType() != org.fractalizer.fractals.AbstractFractalParams.EXTRA_LIGHT_BEAM || p.getExtraLightIntensity() <= 0f) return null;
        float[] pos = {p.getExtraLightX(), p.getExtraLightY(), p.getExtraLightZ()};
        float[] dir = {p.getExtraLightDirX(), p.getExtraLightDirY(), p.getExtraLightDirZ()};
        if (p.isExtraLightAttachToCamera()) {
            if (camPos == null || q == null) return null;
            float[] local = {dir[0] * 0.2f, dir[1] * 0.2f, dir[2]};
            if (local[0] * local[0] + local[1] * local[1] + local[2] * local[2] < 1e-8f) local = new float[]{0f, 0f, 1f};
            dir = rotate(local, q);
            float[] off = rotate(new float[]{pos[0] * 0.1f, pos[1] * 0.1f, pos[2] * 0.1f}, q);
            pos = new float[]{camPos[0] + off[0], camPos[1] + off[1], camPos[2] + off[2]};
        }
        float len = (float) Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
        if (len < 1e-6f) dir = new float[]{0f, 0f, 1f}; else dir = new float[]{dir[0] / len, dir[1] / len, dir[2] / len};
        float radius = Math.max(p.getExtraLightAreaRadius(), 1e-4f);
        float[] e = new float[FLOATS];
        e[0] = BEAM;
        e[1] = pos[0]; e[2] = pos[1]; e[3] = pos[2];
        e[4] = dir[0]; e[5] = dir[1]; e[6] = dir[2];
        e[7] = p.getExtraLightR(); e[8] = p.getExtraLightG(); e[9] = p.getExtraLightB();
        e[10] = p.getExtraLightIntensity();
        e[15] = radius; e[16] = p.getExtraLightRange(); e[17] = p.getExtraLightConeSoftness();
        e[18] = (float) (lum(e[7], e[8], e[9]) * e[10] * Math.PI * radius * radius);
        e[19] = -1f;
        return e;
    }

    /** The additional light as a point or a spot: its position (and the spot's axis) in the
     *  world, its area radius, range, cone and softness; its power the intensity over the
     *  sphere or the cone. The falloff of its draw (a range, not an inverse square) is
     *  applied to the photons at their first hit, so both tell the same light. */
    private static float[] extraEntry(org.fractalizer.fractals.AbstractFractalParams p, float[] camPos, float[] q) {
        int type = p.getExtraLightType();
        boolean spot = type == org.fractalizer.fractals.AbstractFractalParams.EXTRA_LIGHT_SPOT;
        if ((type != org.fractalizer.fractals.AbstractFractalParams.EXTRA_LIGHT_POINT && !spot) || p.getExtraLightIntensity() <= 0f) return null;
        float[] pos = {p.getExtraLightX(), p.getExtraLightY(), p.getExtraLightZ()};
        float[] dir = {p.getExtraLightDirX(), p.getExtraLightDirY(), p.getExtraLightDirZ()};
        if (p.isExtraLightAttachToCamera()) {
            if (camPos == null || q == null) return null;
            float[] local = {dir[0] * 0.2f, dir[1] * 0.2f, dir[2]};
            if (local[0] * local[0] + local[1] * local[1] + local[2] * local[2] < 1e-8f) local = new float[]{0f, 0f, 1f};
            dir = rotate(local, q);
            float[] off = rotate(new float[]{pos[0] * 0.1f, pos[1] * 0.1f, pos[2] * 0.1f}, q);
            pos = new float[]{camPos[0] + off[0], camPos[1] + off[1], camPos[2] + off[2]};
        }
        float len = (float) Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
        if (len < 1e-6f) dir = new float[]{0f, 0f, 1f}; else dir = new float[]{dir[0] / len, dir[1] / len, dir[2] / len};
        float[] e = new float[FLOATS];
        e[0] = spot ? SPOT : POINT;
        e[1] = pos[0]; e[2] = pos[1]; e[3] = pos[2];
        e[4] = dir[0]; e[5] = dir[1]; e[6] = dir[2];
        e[7] = p.getExtraLightR(); e[8] = p.getExtraLightG(); e[9] = p.getExtraLightB();
        e[10] = p.getExtraLightIntensity();
        e[15] = Math.max(p.getExtraLightAreaRadius(), 0f);
        e[16] = Math.max(p.getExtraLightRange(), 1e-4f);
        float outer = Math.max(1f, Math.min(89f, p.getExtraLightConeAngle()));
        e[17] = outer;
        e[21] = Math.max(0f, Math.min(1f, p.getExtraLightConeSoftness()));
        double solid = spot ? 2.0 * Math.PI * (1.0 - Math.cos(Math.toRadians(outer))) : 4.0 * Math.PI;
        e[18] = (float) (lum(e[7], e[8], e[9]) * e[10] * solid);
        e[19] = -1f;
        return e;
    }

    /** v rotated by the unit quaternion (w, x, y, z): rotateByQuaternion in common.glsl. */
    private static float[] rotate(float[] v, float[] q) {
        float w = q[0], x = q[1], y = q[2], z = q[3];
        // t = 2 * cross(qv, v); v' = v + w * t + cross(qv, t)
        float tx = 2f * (y * v[2] - z * v[1]), ty = 2f * (z * v[0] - x * v[2]), tz = 2f * (x * v[1] - y * v[0]);
        return new float[]{v[0] + w * tx + (y * tz - z * ty), v[1] + w * ty + (z * tx - x * tz), v[2] + w * tz + (x * ty - y * tx)};
    }

    // ---- the walk: A is the world-from-local rotation and scale, o the world centre ----------

    private static final float[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};

    private static void collect(GraphNode node, float[] A, float[] o, float s, boolean sampleable,
                                List<MaterialNode> mats, List<float[]> out) {
        if (node instanceof CSGNode c) {
            boolean ok = sampleable && c.getOp() == CSGNode.Op.UNION;
            collect(c.getLeft(), A, o, s, ok, mats, out);
            collect(c.getRight(), A, o, s, ok, mats, out);
        } else if (node instanceof TransformNode t) {
            if (t.getMode() != TransformNode.Mode.STANDARD) { collect(t.getChild(), A, o, s, false, mats, out); return; }
            float[] A2 = compose(A, t);
            float[] o2 = add(o, mulVec(A, t.getOffset()));
            collect(t.getChild(), A2, o2, s * t.getScale(), sampleable, mats, out);
        } else if (node instanceof MaterialNode m) {
            if (sampleable && m.getEmission() > 0f && m.getColorMode() == MaterialNode.COLOR_SOLID) {
                float[] A2 = A, o2 = o; float s2 = s;
                GraphNode ch = m.getChild();
                boolean ok = true;
                while (ch instanceof TransformNode t) {
                    if (t.getMode() != TransformNode.Mode.STANDARD) { ok = false; break; }
                    o2 = add(o2, mulVec(A2, t.getOffset()));
                    A2 = compose(A2, t);
                    s2 *= t.getScale();
                    ch = t.getChild();
                }
                if (ok && ch instanceof PrimitiveNode p
                        && (p.getPrimitiveType() == PrimitiveNode.PrimitiveType.SPHERE || p.getPrimitiveType() == PrimitiveNode.PrimitiveType.BOX)) {
                    out.add(entry(m, p, A2, o2, Math.abs(s2), mats.indexOf(m)));
                }
            }
            collect(m.getChild(), A, o, s, sampleable, mats, out);
        } else if (node instanceof EffectNode e) {
            collect(e.getChild(), A, o, s, false, mats, out);
        }
    }

    private static float[] entry(MaterialNode m, PrimitiveNode p, float[] A, float[] o, float s, int matId) {
        float[] e = new float[FLOATS];
        boolean sphere = p.getPrimitiveType() == PrimitiveNode.PrimitiveType.SPHERE;
        e[0] = sphere ? EMITTER_SPHERE : EMITTER_BOX;
        e[1] = o[0]; e[2] = o[1]; e[3] = o[2];
        e[7] = m.getColorR(); e[8] = m.getColorG(); e[9] = m.getColorB();
        e[10] = m.getEmission();
        float[] q = quaternion(A, s);
        e[11] = q[0]; e[12] = q[1]; e[13] = q[2]; e[14] = q[3];
        float area;
        if (sphere) {
            float r = p.getSizeX() * s;
            e[15] = r; e[16] = r; e[17] = r;
            area = (float) (4.0 * Math.PI * r * r);
        } else {
            float hx = p.getSizeX() * s, hy = p.getSizeY() * s, hz = p.getSizeZ() * s;
            e[15] = hx; e[16] = hy; e[17] = hz;
            area = 8f * (hx * hy + hy * hz + hz * hx);
        }
        float lum = 0.2126f * e[7] + 0.7152f * e[8] + 0.0722f * e[9];
        e[18] = (float) (lum * m.getEmission() * area * Math.PI);
        e[19] = matId;
        e[20] = area;
        // 21 .. 23 padding
        return e;
    }

    // ---- small linear algebra, row-major 3x3 ----------------------------------------------

    /** A x (scale x Rw) where Rw is the transform's world-from-local rotation: the compiler
     *  maps world to local by Rz Ry Rx applied in that order, so world-from-local is its
     *  transpose. */
    private static float[] compose(float[] A, TransformNode t) {
        float[] r = t.getRotation();
        float[] R = mul(mul(rotZ(r[2]), rotY(r[1])), rotX(r[0]));
        float[] Rw = transpose(R);
        float sc = t.getScale();
        for (int i = 0; i < 9; i++) Rw[i] *= sc;
        return mul(A, Rw);
    }

    private static float[] rotX(float deg) {
        double a = Math.toRadians(deg); float c = (float) Math.cos(a), s = (float) Math.sin(a);
        return new float[]{1, 0, 0, 0, c, -s, 0, s, c};
    }
    private static float[] rotY(float deg) {
        double a = Math.toRadians(deg); float c = (float) Math.cos(a), s = (float) Math.sin(a);
        return new float[]{c, 0, s, 0, 1, 0, -s, 0, c};
    }
    private static float[] rotZ(float deg) {
        double a = Math.toRadians(deg); float c = (float) Math.cos(a), s = (float) Math.sin(a);
        return new float[]{c, -s, 0, s, c, 0, 0, 0, 1};
    }
    private static float[] mul(float[] a, float[] b) {
        float[] m = new float[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
            m[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
        return m;
    }
    private static float[] transpose(float[] a) {
        return new float[]{a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8]};
    }
    private static float[] mulVec(float[] a, float[] v) {
        return new float[]{a[0] * v[0] + a[1] * v[1] + a[2] * v[2],
                           a[3] * v[0] + a[4] * v[1] + a[5] * v[2],
                           a[6] * v[0] + a[7] * v[1] + a[8] * v[2]};
    }
    private static float[] add(float[] a, float[] b) { return new float[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }

    /** The unit quaternion (x, y, z, w) of the rotation in A, whose scale is s: the
     *  convention of rotateByQuaternion in common.glsl. */
    private static float[] quaternion(float[] A, float s) {
        float inv = s > 1e-8f ? 1f / s : 1f;
        float m00 = A[0] * inv, m01 = A[1] * inv, m02 = A[2] * inv;
        float m10 = A[3] * inv, m11 = A[4] * inv, m12 = A[5] * inv;
        float m20 = A[6] * inv, m21 = A[7] * inv, m22 = A[8] * inv;
        float tr = m00 + m11 + m22;
        float x, y, z, w;
        if (tr > 0f) {
            float S = (float) Math.sqrt(tr + 1f) * 2f;
            w = 0.25f * S; x = (m21 - m12) / S; y = (m02 - m20) / S; z = (m10 - m01) / S;
        } else if (m00 > m11 && m00 > m22) {
            float S = (float) Math.sqrt(1f + m00 - m11 - m22) * 2f;
            w = (m21 - m12) / S; x = 0.25f * S; y = (m01 + m10) / S; z = (m02 + m20) / S;
        } else if (m11 > m22) {
            float S = (float) Math.sqrt(1f + m11 - m00 - m22) * 2f;
            w = (m02 - m20) / S; x = (m01 + m10) / S; y = 0.25f * S; z = (m12 + m21) / S;
        } else {
            float S = (float) Math.sqrt(1f + m22 - m00 - m11) * 2f;
            w = (m10 - m01) / S; x = (m02 + m20) / S; y = (m12 + m21) / S; z = 0.25f * S;
        }
        float n = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        return new float[]{x / n, y / n, z / n, w / n};
    }
}
