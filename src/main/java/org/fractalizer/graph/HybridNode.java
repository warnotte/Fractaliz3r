package org.fractalizer.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Leaf node that chains several formulas <em>inside</em> one iteration loop.
 *
 * This is a different operation from anything {@link CSGNode} can express. A CSG node
 * combines two distance fields that were each produced by a complete, independent DE
 * evaluation: union, intersection, subtraction and morph are all pointwise functions
 * of the two finished distances. A hybrid instead composes the <em>maps</em>: the
 * orbit of a point is taken under g(f(z)) rather than under f and g separately, so
 * the escape set it produces is generally not any pointwise function of the two
 * original shapes. Folding a box inside a spherical power map at every scale is not
 * something a union of a Mandelbox and a Mandelbulb can reach, because in the union
 * each shape keeps its own self-similarity all the way down.
 *
 * Steps are applied in order, once per iteration, and the sequence repeats. Every step
 * carries its own derivative update so the result stays a usable distance estimator.
 * A step can also be restricted to a range of iterations, or to every n-th one, which
 * is how the classic "formula A for three iterations, then formula B" hybrids of
 * Mandelbulber and Mandelbulb3D are written here.
 */
public class HybridNode extends GraphNode {

    /** Which kind of map a step is. Used to group the step menu and the documentation. */
    public enum Family {
        POWER("Power maps"),       // escape-time maps: z -> z^p and relatives
        FOLD("Folds"),             // piecewise isometries and IFS contractions
        TRANSFORM("Transforms"),   // rigid or near-rigid motions between steps
        SEED("Seed");              // z += c

        private final String displayName;
        Family(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    /** One map in the chain. Each knows how it scales the running derivative. */
    public enum StepType {
        // --- Power maps (escape-time; pair them with Add Seed and the log estimator) ---
        BULB(Family.POWER, "Bulb Power",
                "Spherical power map z -> z^p, the Mandelbulb step (sine convention)."),
        BULB_COSINE(Family.POWER, "Bulb (cosine)",
                "Nylander's cosine convention of the power map; a different bulb from the same power."),
        QUAT_SQUARE(Family.POWER, "Quaternion Square",
                "z^2 on the w = 0 slice of the quaternions; also the 3D slice of the Tetrabrot."),
        BRISTOR(Family.POWER, "Bristorbrot",
                "The Bristorbrot square: (x^2-y^2-z^2, 2xy, -2xz)."),
        BENESI_MAG(Family.POWER, "Benesi Mag",
                "Benesi's quadratic 'mag transform', the square behind the Pine Tree."),
        RIEMANN(Family.POWER, "Riemann Sphere",
                "Stereographic projection, a sine tiling in the plane, back to the sphere; radial power."),
        COMPLEX_POWER(Family.POWER, "Complex Power",
                "z^p in the plane perpendicular to the axis; the axis passes through. Stack it with a twist for JuliaMorph."),

        // --- Folds ---
        BOX_FOLD(Family.FOLD, "Box Fold",
                "The full Mandelbox step: box fold, sphere fold, then scale."),
        BOX_FOLD_ONLY(Family.FOLD, "Box Fold only",
                "Just the box fold, clamp(z)*2 - z, with no sphere fold or scale."),
        SPHERE_FOLD(Family.FOLD, "Sphere Fold",
                "Just the Mandelbox sphere fold between a minimum and a fixed radius."),
        AMAZING_SURF(Family.FOLD, "Amazing Surf",
                "Kali's Amazing Surf: box fold on X and Y only, sphere fold, scale. Sheets and shelves."),
        ABOX_MOD(Family.FOLD, "ABox Mod",
                "Mandelbox with a different fold limit per axis (the offset vector), sphere fold, scale."),
        KLEINIAN_FOLD(Family.FOLD, "Pseudo-Kleinian Fold",
                "Box fold with per-axis limits, then the interior inversion k = max(size / r^2, 1)."),
        MENGER_FOLD(Family.FOLD, "Menger Fold",
                "abs, sort the axes, scale and offset: the Menger sponge step."),
        SIERPINSKI_FOLD(Family.FOLD, "Tetra Fold",
                "Tetrahedral fold across three planes, then scale and offset."),
        OCTA_FOLD(Family.FOLD, "Octa Fold",
                "Octahedral fold across four planes, then scale and offset."),
        ICOSA_FOLD(Family.FOLD, "Icosa Fold",
                "Icosahedral fold (golden-ratio planes), then scale and offset."),
        ABS_FOLD(Family.FOLD, "Abs Fold",
                "Mirror into the positive octant around an offset: abs(z + o) - o."),
        PLANE_FOLD(Family.FOLD, "Plane Fold",
                "Reflect what lies below one plane (normal = offset vector, at a distance) to above it."),
        ROTATIONAL_FOLD(Family.FOLD, "Kaleido Fold",
                "N-fold kaleidoscope around one axis: the angle is folded into a wedge."),
        BENESI_FOLD(Family.FOLD, "Benesi Fold",
                "Benesi T1: abs in the frame whose axis is the body diagonal, scale, offset."),
        KALI_FOLD(Family.FOLD, "Kaliset",
                "abs(z) / r^2 - c: the Kaliset step, an inversion of the positive octant."),
        SPHERE_INVERT(Family.FOLD, "Sphere Invert",
                "Inversion in a sphere of the given radius."),

        // --- Transforms ---
        ROTATE(Family.TRANSFORM, "Rotate",
                "Rigid rotation between steps."),
        ROTATE_ITER(Family.TRANSFORM, "Rotate per Iteration",
                "Rotation whose angle grows with the iteration index: the n-th pass turns n times as far."),
        TWIST(Family.TRANSFORM, "Twist",
                "Rotate around an axis by an angle proportional to the height along it."),
        SCALE(Family.TRANSFORM, "Scale + Offset",
                "Similarity: z * s + offset."),

        // --- Seed ---
        ADD_C(Family.SEED, "Add Seed",
                "z += c, the term that makes it Mandelbrot or Julia.");

        private final Family family;
        private final String displayName;
        private final String hint;

        StepType(Family family, String displayName, String hint) {
            this.family = family;
            this.displayName = displayName;
            this.hint = hint;
        }

        public Family getFamily() { return family; }
        public String getDisplayName() { return displayName; }
        /** One line on what the step does and which of its parameters matter. */
        public String getHint() { return hint; }
    }

    /** Distance estimator used once the orbit escapes. */
    public enum DEMode {
        LOG("Escape-time (log)"),   // 0.5 * log(r) * r / dr — power maps
        LINEAR("Linear (IFS)"),     // r / |dr| — folds and similarities
        PLANE("Plane trap (Kleinian)"); // 0.5 * |z.z + 0.1| / |dr| — Knighty's pseudo-Kleinian estimator

        private final String displayName;
        DEMode(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    /** Iterations are 0-based; a step runs when {@code start <= i < end} and
     *  {@code (i - start) % every == 0}. These are the defaults: every iteration. */
    public static final int ITER_ALL = 64;

    /** A single step and its parameters. Unused fields are simply ignored per type. */
    public static class Step {
        private StepType type;
        private float power = 8f;          // BULB, BULB_COSINE, RIEMANN, COMPLEX_POWER
        private float scale = 2f;          // BOX_FOLD family, MENGER/TETRA/OCTA/ICOSA/BENESI, SCALE, RIEMANN (plane frequency)
        private float minRadius = 0.25f;   // sphere fold inner radius
        private float fixedRadius = 1f;    // sphere fold outer radius
        private float foldLimit = 1f;      // box fold limit (scalar)
        private float offsetX = 1f, offsetY = 1f, offsetZ = 1f;   // fold / scale offset, per-axis limits, plane normal, Kali c
        private float rotX = 0f, rotY = 0f, rotZ = 0f;            // ROTATE, ROTATE_ITER (per iteration), TWIST (rotX per unit), degrees
        private float radius = 1f;         // SPHERE_INVERT, KALI_FOLD, KLEINIAN_FOLD (size)
        private float dist = 0f;           // PLANE_FOLD distance of the plane from the origin
        private int axis = 2;              // ROTATIONAL_FOLD, TWIST: 0 = X, 1 = Y, 2 = Z
        private int count = 5;             // ROTATIONAL_FOLD symmetry order
        private int iterStart = 0;         // gating: first iteration the step runs on
        private int iterEnd = ITER_ALL;    // gating: exclusive end
        private int iterEvery = 1;         // gating: run every n-th iteration from iterStart

        public Step(StepType type) {
            this.type = type;
            // Type-specific starting values, so a freshly added step does something
            // sensible instead of sitting at the generic defaults. The original nine
            // types keep the generic defaults: the two control chains and every saved
            // .frac depend on them.
            switch (type) {
                case BENESI_FOLD -> { scale = 2f; offsetX = 2f; offsetY = 0f; offsetZ = 0f; }
                case KALI_FOLD -> { offsetX = 0.5f; offsetY = 0.5f; offsetZ = 0.5f; radius = 1f; }
                case KLEINIAN_FOLD -> { offsetX = 0.92f; offsetY = 0.92f; offsetZ = 0.92f; radius = 1f; }
                case AMAZING_SURF -> { scale = 1.5f; minRadius = 0.5f; fixedRadius = 1f; foldLimit = 1f; }
                case PLANE_FOLD -> { offsetX = 1f; offsetY = 1f; offsetZ = 0f; dist = 0f; }
                case ROTATE_ITER -> { rotZ = 12f; }
                case TWIST -> { rotX = 30f; axis = 2; }
                case RIEMANN -> { power = 2f; scale = 1f; }
                case COMPLEX_POWER -> { power = 2f; }
                case ICOSA_FOLD -> { offsetX = 1f; offsetY = 0f; offsetZ = 0f; }
                default -> { }
            }
        }

        public StepType getType() { return type; }
        public void setType(StepType type) { this.type = type; }

        public float getPower() { return power; }
        public void setPower(float v) { this.power = clamp(v, 1f, 24f); }

        public float getScale() { return scale; }
        public void setScale(float v) { this.scale = clamp(v, -5f, 5f); }

        public float getMinRadius() { return minRadius; }
        public void setMinRadius(float v) { this.minRadius = clamp(v, 0.01f, 2f); }

        public float getFixedRadius() { return fixedRadius; }
        public void setFixedRadius(float v) { this.fixedRadius = clamp(v, 0.05f, 4f); }

        public float getFoldLimit() { return foldLimit; }
        public void setFoldLimit(float v) { this.foldLimit = clamp(v, 0.1f, 4f); }

        public float getOffsetX() { return offsetX; }
        public void setOffsetX(float v) { this.offsetX = clamp(v, -4f, 4f); }
        public float getOffsetY() { return offsetY; }
        public void setOffsetY(float v) { this.offsetY = clamp(v, -4f, 4f); }
        public float getOffsetZ() { return offsetZ; }
        public void setOffsetZ(float v) { this.offsetZ = clamp(v, -4f, 4f); }

        public float getRotX() { return rotX; }
        public void setRotX(float v) { this.rotX = clamp(v, -180f, 180f); }
        public float getRotY() { return rotY; }
        public void setRotY(float v) { this.rotY = clamp(v, -180f, 180f); }
        public float getRotZ() { return rotZ; }
        public void setRotZ(float v) { this.rotZ = clamp(v, -180f, 180f); }

        public float getRadius() { return radius; }
        public void setRadius(float v) { this.radius = clamp(v, 0.05f, 4f); }

        public float getDist() { return dist; }
        public void setDist(float v) { this.dist = clamp(v, -4f, 4f); }

        public int getAxis() { return axis; }
        public void setAxis(int v) { this.axis = Math.max(0, Math.min(2, v)); }

        public int getCount() { return count; }
        public void setCount(int v) { this.count = Math.max(1, Math.min(24, v)); }

        public int getIterStart() { return iterStart; }
        public void setIterStart(int v) { this.iterStart = Math.max(0, Math.min(ITER_ALL - 1, v)); }

        public int getIterEnd() { return iterEnd; }
        public void setIterEnd(int v) { this.iterEnd = Math.max(1, Math.min(ITER_ALL, v)); }

        public int getIterEvery() { return iterEvery; }
        public void setIterEvery(int v) { this.iterEvery = Math.max(1, Math.min(8, v)); }

        /** True when the step does not run on every iteration. Gating is baked into the
         *  emitted GLSL (it decides which code runs, not a value), so changing it is a
         *  structural edit. */
        public boolean isGated() {
            return iterStart != 0 || iterEnd != ITER_ALL || iterEvery != 1;
        }

        /** "3-6", "every 2nd", "from 4", or "" when the step runs on every iteration. */
        public String describeGate() {
            if (!isGated()) return "";
            StringBuilder sb = new StringBuilder();
            if (iterStart != 0 || iterEnd != ITER_ALL) {
                sb.append(iterEnd == ITER_ALL ? "from " + iterStart : iterStart + "-" + (iterEnd - 1));
            }
            if (iterEvery != 1) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("every ").append(iterEvery).append(iterEvery == 2 ? "nd" : iterEvery == 3 ? "rd" : "th");
            }
            return sb.toString();
        }

        private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

        public Step copy() {
            Step s = new Step(type);
            s.power = power; s.scale = scale; s.minRadius = minRadius; s.fixedRadius = fixedRadius;
            s.foldLimit = foldLimit;
            s.offsetX = offsetX; s.offsetY = offsetY; s.offsetZ = offsetZ;
            s.rotX = rotX; s.rotY = rotY; s.rotZ = rotZ; s.radius = radius;
            s.dist = dist; s.axis = axis; s.count = count;
            s.iterStart = iterStart; s.iterEnd = iterEnd; s.iterEvery = iterEvery;
            return s;
        }
    }

    private final List<Step> steps = new ArrayList<>();
    private int maxIterations = 12;
    private float bailout = 8f;
    private DEMode deMode = DEMode.LOG;

    // Julia mode, same convention as the stand-alone formulas: (0,0,0) = Mandelbrot.
    private float juliaCx = 0f, juliaCy = 0f, juliaCz = 0f;

    public HybridNode() {
        // A chain that is already a hybrid rather than a plain formula: a box fold
        // nested inside a spherical power map, which neither node alone can produce.
        steps.add(new Step(StepType.BULB));
        steps.add(new Step(StepType.BOX_FOLD));
        steps.add(new Step(StepType.ADD_C));
    }

    public HybridNode(List<Step> steps, int maxIterations, float bailout, DEMode deMode) {
        this.steps.addAll(steps);
        this.maxIterations = maxIterations;
        this.bailout = bailout;
        this.deMode = deMode;
    }

    public List<Step> getSteps() { return steps; }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int v) { this.maxIterations = Math.max(1, Math.min(64, v)); }

    public float getBailout() { return bailout; }
    public void setBailout(float v) { this.bailout = Math.max(1f, Math.min(1000f, v)); }

    public DEMode getDeMode() { return deMode; }
    public void setDeMode(DEMode m) { this.deMode = m; }

    public float getJuliaCx() { return juliaCx; }
    public void setJuliaCx(float v) { this.juliaCx = v; }
    public float getJuliaCy() { return juliaCy; }
    public void setJuliaCy(float v) { this.juliaCy = v; }
    public float getJuliaCz() { return juliaCz; }
    public void setJuliaCz(float v) { this.juliaCz = v; }

    /** Short signature of the chain, e.g. "Bulb -> Box Fold -> Add Seed". A gated step
     *  carries its range in brackets: "Box Fold [0-2]". */
    public String describeChain() {
        if (steps.isEmpty()) return "(empty)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) sb.append(" -> ");
            Step s = steps.get(i);
            sb.append(s.getType().getDisplayName());
            if (s.isGated()) sb.append(" [").append(s.describeGate()).append("]");
        }
        return sb.toString();
    }

    @Override
    public List<GraphNode> getChildren() {
        return Collections.emptyList();
    }
}
