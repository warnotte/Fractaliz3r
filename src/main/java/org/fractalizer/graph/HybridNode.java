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
 */
public class HybridNode extends GraphNode {

    /** One map in the chain. Each knows how it scales the running derivative. */
    public enum StepType {
        BULB("Bulb Power"),             // spherical power map, z -> z^p
        BOX_FOLD("Box Fold"),           // Mandelbox box fold + sphere fold + scale
        MENGER_FOLD("Menger Fold"),     // abs + sort + scale/offset
        SIERPINSKI_FOLD("Tetra Fold"),  // tetrahedral fold + scale/offset
        ABS_FOLD("Abs Fold"),           // mirror into the positive octant
        ROTATE("Rotate"),               // rigid rotation between steps
        SCALE("Scale + Offset"),        // similarity
        SPHERE_INVERT("Sphere Invert"), // kleinian-style inversion
        ADD_C("Add Seed");              // z += c, the term that makes it Mandelbrot/Julia

        private final String displayName;
        StepType(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    /** Distance estimator used once the orbit escapes. */
    public enum DEMode {
        LOG("Escape-time (log)"),   // 0.5 * log(r) * r / dr — power maps
        LINEAR("Linear (IFS)");     // r / |dr| — folds and similarities

        private final String displayName;
        DEMode(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    /** A single step and its parameters. Unused fields are simply ignored per type. */
    public static class Step {
        private StepType type;
        private float power = 8f;          // BULB
        private float scale = 2f;          // BOX_FOLD / MENGER_FOLD / SIERPINSKI_FOLD / SCALE
        private float minRadius = 0.25f;   // BOX_FOLD sphere fold inner radius
        private float fixedRadius = 1f;    // BOX_FOLD sphere fold outer radius
        private float foldLimit = 1f;      // BOX_FOLD box fold limit
        private float offsetX = 1f, offsetY = 1f, offsetZ = 1f;   // fold / scale offset
        private float rotX = 0f, rotY = 0f, rotZ = 0f;            // ROTATE, degrees
        private float radius = 1f;         // SPHERE_INVERT

        public Step(StepType type) { this.type = type; }

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

        private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

        public Step copy() {
            Step s = new Step(type);
            s.power = power; s.scale = scale; s.minRadius = minRadius; s.fixedRadius = fixedRadius;
            s.foldLimit = foldLimit;
            s.offsetX = offsetX; s.offsetY = offsetY; s.offsetZ = offsetZ;
            s.rotX = rotX; s.rotY = rotY; s.rotZ = rotZ; s.radius = radius;
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

    /** Short signature of the chain, e.g. "Bulb -> Box Fold -> Add Seed". */
    public String describeChain() {
        if (steps.isEmpty()) return "(empty)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) sb.append(" -> ");
            sb.append(steps.get(i).getType().getDisplayName());
        }
        return sb.toString();
    }

    @Override
    public List<GraphNode> getChildren() {
        return Collections.emptyList();
    }
}
