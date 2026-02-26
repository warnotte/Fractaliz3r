package org.fractalizer.graph;

import org.fractalizer.engine.ShaderPreprocessor;
import org.fractalizer.fractals.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Compiles a tree of {@link GraphNode}s into a single composite GLSL shader.
 * The output replaces the [fractal.glsl] slot in the standard assembly:
 * {@code #version 430 + common.glsl + [COMPOSITE] + raytracer.glsl}.
 *
 * <p>Uses {@link ShaderPreprocessor} to rename symbols in each fractal shader
 * with a unique prefix (n0_, n1_, ...) so they coexist without conflicts.</p>
 */
public class GraphCompiler {

    private int fractalCounter;
    private int transformCounter;
    private int csgCounter;
    private final List<LeafInfo> leaves = new ArrayList<>();
    private final List<TransformInfo> transforms = new ArrayList<>();
    private final List<CSGInfo> csgNodes = new ArrayList<>();

    private record LeafInfo(FractalNode node, String prefix) {}
    private record TransformInfo(TransformNode node, String id) {}
    private record CSGInfo(CSGNode node, String id) {}

    /**
     * Compile a node graph into composite GLSL source code.
     * Assigns IDs to all nodes (accessible via {@link GraphNode#getId()}).
     *
     * @param root the root of the graph tree
     * @return GLSL source to be placed between common.glsl and raytracer.glsl
     */
    public String compile(GraphNode root) {
        fractalCounter = 0;
        transformCounter = 0;
        csgCounter = 0;
        leaves.clear();
        transforms.clear();
        csgNodes.clear();

        // Phase 1: DFS to assign IDs and collect metadata
        assignIds(root);

        StringBuilder sb = new StringBuilder();

        // Phase 2: Load, strip, and preprocess each fractal shader
        for (LeafInfo leaf : leaves) {
            String source;
            if (leaf.node.getFractalType() == FractalType.CUSTOM_SHADER
                    && leaf.node.getFractalParams() instanceof CustomShaderParams csp) {
                source = csp.getShaderSource();
            } else {
                source = loadFractalShader(leaf.node.getFractalType().getKernelName());
            }
            String stripped = source.replaceAll("#version\\s+\\d+\\s*\\w*", "").trim();
            String preprocessed = ShaderPreprocessor.renameLocalSymbols(stripped, leaf.prefix + "_");
            sb.append("// === ").append(leaf.node.getFractalType().getDisplayName())
              .append(" (").append(leaf.prefix).append(") ===\n");
            sb.append(preprocessed).append("\n\n");
        }

        // Phase 3: smin/smax helpers + CSG blend uniforms (only if CSG nodes exist)
        if (csgCounter > 0) {
            sb.append(generateHelpers());
            for (CSGInfo ci : csgNodes) {
                sb.append("uniform float ").append(ci.id).append("_blend;\n");
            }
            sb.append("\n");
        }

        // Phase 4: Transform functions
        sb.append(generateTransformFunctions());

        // Phase 5: Composite OrbitTrap struct
        sb.append(generateOrbitTrap());

        // Phase 6: Composite DE()
        sb.append(generateDE(root));

        // Phase 7: Composite DE_simple()
        sb.append(generateDESimple(root));

        // Phase 8: Composite getFactors()
        sb.append(generateGetFactors());

        return sb.toString();
    }

    /**
     * Returns uniform name->value map for the compiled graph.
     * Must be called after {@link #compile(GraphNode)} (uses assigned IDs).
     */
    public Map<String, Object> getUniforms(GraphNode root) {
        Map<String, Object> uniforms = new LinkedHashMap<>();
        collectUniformsFromNode(root, uniforms);
        return uniforms;
    }

    /**
     * Emit fractal-specific uniforms for a given type with a prefix.
     * Uses default parameter values. For per-node params, use the overload with AbstractFractalParams.
     */
    public static void emitFractalUniforms(Map<String, Object> uniforms, FractalType type, String prefix) {
        AbstractFractalParams defaults = FractalNode.createDefaultParams(type);
        if (defaults != null) emitFractalUniforms(uniforms, prefix, defaults);
    }

    /**
     * Emit fractal-specific uniforms from a concrete params instance.
     * Reads actual values from the provided params object (no defaults created).
     */
    public static void emitFractalUniforms(Map<String, Object> uniforms, String prefix, AbstractFractalParams params) {
        if (params instanceof MandelbulbParams p) {
            uniforms.put(prefix + "power", p.getPower());
            uniforms.put(prefix + "maxIterations", p.getMaxIterations());
            uniforms.put(prefix + "bailout", p.getBailout());
            uniforms.put(prefix + "radiolaria", p.getRadiolaria());
            uniforms.put(prefix + "radiolariaFactor", p.getRadiolariaFactor());
        } else if (params instanceof MandelboxParams p) {
            uniforms.put(prefix + "scale", p.getScale());
            uniforms.put(prefix + "minRadius", p.getMinRadius());
            uniforms.put(prefix + "fixedRadius", p.getFixedRadius());
            uniforms.put(prefix + "foldingLimit", p.getFoldingLimit());
            uniforms.put(prefix + "maxIterations", p.getMaxIterations());
        } else if (params instanceof MengerSpongeParams p) {
            uniforms.put(prefix + "maxIterations", p.getMaxIterations());
            uniforms.put(prefix + "scale", p.getScale());
            uniforms.put(prefix + "offset", new float[]{p.getOffsetX(), p.getOffsetY(), p.getOffsetZ()});
            uniforms.put(prefix + "rotAngle", p.getRotAngle());
        } else if (params instanceof KaleidoscopicIFSParams p) {
            uniforms.put(prefix + "maxIterations", p.getMaxIterations());
            uniforms.put(prefix + "scale", p.getScale());
            uniforms.put(prefix + "foldAngleX", (float) Math.toRadians(p.getFoldAngleX()));
            uniforms.put(prefix + "foldAngleY", (float) Math.toRadians(p.getFoldAngleY()));
            uniforms.put(prefix + "ifsOffset", p.getOffsetX());
        } else if (params instanceof PolyhedralIFSParams p) {
            uniforms.put(prefix + "polyType", p.getPolyType().ordinal());
            uniforms.put(prefix + "maxIterations", p.getMaxIterations());
            uniforms.put(prefix + "scale", p.getScale());
            uniforms.put(prefix + "offset", new float[]{p.getOffsetX(), p.getOffsetY(), p.getOffsetZ()});
            uniforms.put(prefix + "shift", new float[]{p.getShiftX(), p.getShiftY(), p.getShiftZ()});
            uniforms.put(prefix + "fractalRotation1", createRotationMatrix(p.getRot1X(), p.getRot1Y(), p.getRot1Z()));
            uniforms.put(prefix + "fractalRotation2", createRotationMatrix(p.getRot2X(), p.getRot2Y(), p.getRot2Z()));
        } else if (params instanceof SierpinskiParams p) {
            uniforms.put(prefix + "maxIterations", p.getMaxIterations());
            uniforms.put(prefix + "scale", p.getScale());
        } else if (params instanceof PseudoKleinianParams p) {
            uniforms.put(prefix + "maxIterations", p.getMaxIterations());
            uniforms.put(prefix + "CSize", new float[]{p.getCSizeX(), p.getCSizeY(), p.getCSizeZ()});
            uniforms.put(prefix + "Size", p.getSize());
            uniforms.put(prefix + "DEoffset", p.getDEOffset());
            uniforms.put(prefix + "foldC", new float[]{p.getFoldCx(), p.getFoldCy(), p.getFoldCz()});
        } else if (params instanceof ApollonianParams p) {
            uniforms.put(prefix + "maxIterations", p.getMaxIterations());
            uniforms.put(prefix + "scale", p.getScale());
            uniforms.put(prefix + "foldRadius", p.getFoldRadius());
        } else if (params instanceof BristorbrotParams p) {
            uniforms.put(prefix + "maxIterations", p.getMaxIterations());
            uniforms.put(prefix + "bailout", p.getBailout());
            uniforms.put(prefix + "juliaC", new float[]{p.getJuliaCx(), p.getJuliaCy(), p.getJuliaCz()});
        } else if (params instanceof QuaternionJulia4DParams p) {
            uniforms.put(prefix + "maxIterations", p.getMaxIterations());
            uniforms.put(prefix + "bailout", p.getBailout());
            uniforms.put(prefix + "juliaC", new float[]{p.getJuliaCx(), p.getJuliaCy(), p.getJuliaCz(), p.getJuliaCw()});
            uniforms.put(prefix + "sliceW", p.getSliceW());
            uniforms.put(prefix + "rotXW", (float) Math.toRadians(p.getRotXW()));
            uniforms.put(prefix + "rotYW", (float) Math.toRadians(p.getRotYW()));
            uniforms.put(prefix + "rotZW", (float) Math.toRadians(p.getRotZW()));
        } else if (params instanceof CustomShaderParams csp) {
            for (Map.Entry<String, Object> entry : csp.getUniformValues().entrySet()) {
                Object val = entry.getValue();
                if (val instanceof Number n) {
                    uniforms.put(prefix + entry.getKey(), n.floatValue());
                } else if (val instanceof float[] arr) {
                    uniforms.put(prefix + entry.getKey(), arr.clone());
                }
            }
        }
    }

    // ========================================================================
    // ID assignment
    // ========================================================================

    private void assignIds(GraphNode node) {
        if (node instanceof FractalNode fn) {
            String prefix = "n" + fractalCounter++;
            fn.id = prefix;
            leaves.add(new LeafInfo(fn, prefix));
        } else if (node instanceof TransformNode tn) {
            String tid = "t" + transformCounter++;
            tn.id = tid;
            transforms.add(new TransformInfo(tn, tid));
            assignIds(tn.getChild());
        } else if (node instanceof CSGNode csn) {
            String cid = "c" + csgCounter++;
            csn.id = cid;
            csgNodes.add(new CSGInfo(csn, cid));
            assignIds(csn.getLeft());
            assignIds(csn.getRight());
        }
    }

    // ========================================================================
    // GLSL generation
    // ========================================================================

    private String generateHelpers() {
        return """
            // === Graph smin/smax (self-contained) ===
            float smin_graph(float a, float b, float k) {
                if (k <= 0.0) return min(a, b);
                float h = max(k - abs(a - b), 0.0) / k;
                return min(a, b) - h * h * k * 0.25;
            }
            float smax_graph(float a, float b, float k) {
                return -smin_graph(-a, -b, k);
            }

            """;
    }

    private String generateTransformFunctions() {
        if (transforms.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("// === Transform functions ===\n");
        for (TransformInfo ti : transforms) {
            switch (ti.node.getMode()) {
                case STANDARD -> emitStandardTransform(sb, ti.id);
                case MIRROR   -> emitMirrorTransform(sb, ti.id);
                case TWIST    -> emitTwistTransform(sb, ti.id);
                case BEND     -> emitBendTransform(sb, ti.id);
                case TAPER    -> emitTaperTransform(sb, ti.id);
                case REPETITION -> emitRepetitionTransform(sb, ti.id);
                case REPETITION_1D -> emitRepetition1DTransform(sb, ti.id);
            }
        }
        return sb.toString();
    }

    private void emitStandardTransform(StringBuilder sb, String id) {
        sb.append("uniform vec3 ").append(id).append("_offset;\n");
        sb.append("uniform float ").append(id).append("_rotX;\n");
        sb.append("uniform float ").append(id).append("_rotY;\n");
        sb.append("uniform float ").append(id).append("_rotZ;\n");
        sb.append("uniform float ").append(id).append("_scale;\n");
        sb.append("vec3 applyTransform_").append(id).append("(vec3 pos) {\n");
        sb.append("    vec3 p = pos - ").append(id).append("_offset;\n");
        sb.append("    float cx = cos(").append(id).append("_rotX), sx = sin(").append(id).append("_rotX);\n");
        sb.append("    p = vec3(p.x, cx * p.y - sx * p.z, sx * p.y + cx * p.z);\n");
        sb.append("    float cy = cos(").append(id).append("_rotY), sy = sin(").append(id).append("_rotY);\n");
        sb.append("    p = vec3(cy * p.x + sy * p.z, p.y, -sy * p.x + cy * p.z);\n");
        sb.append("    float cz = cos(").append(id).append("_rotZ), sz = sin(").append(id).append("_rotZ);\n");
        sb.append("    p = vec3(cz * p.x - sz * p.y, sz * p.x + cz * p.y, p.z);\n");
        sb.append("    return p / ").append(id).append("_scale;\n");
        sb.append("}\n\n");
    }

    private void emitMirrorTransform(StringBuilder sb, String id) {
        sb.append("uniform vec3 ").append(id).append("_mirrorAxis;\n");
        sb.append("uniform float ").append(id).append("_mirrorOffset;\n");
        sb.append("vec3 applyTransform_").append(id).append("(vec3 pos) {\n");
        sb.append("    float comp = dot(pos, ").append(id).append("_mirrorAxis);\n");
        sb.append("    float mirrored = abs(comp - ").append(id).append("_mirrorOffset) + ").append(id).append("_mirrorOffset;\n");
        sb.append("    return pos + ").append(id).append("_mirrorAxis * (mirrored - comp);\n");
        sb.append("}\n\n");
    }

    private void emitTwistTransform(StringBuilder sb, String id) {
        sb.append("uniform int ").append(id).append("_axis;\n");
        sb.append("uniform float ").append(id).append("_strength;\n");
        sb.append("uniform float ").append(id).append("_frequency;\n");
        sb.append("uniform float ").append(id).append("_offset;\n");
        // DE correction function
        sb.append("float deCorr_").append(id).append("(vec3 pos) {\n");
        sb.append("    float axisVal;\n");
        sb.append("    float perpA, perpB;\n");
        sb.append("    if (").append(id).append("_axis == 0) { axisVal = pos.x; perpA = pos.y; perpB = pos.z; }\n");
        sb.append("    else if (").append(id).append("_axis == 1) { axisVal = pos.y; perpA = pos.x; perpB = pos.z; }\n");
        sb.append("    else { axisVal = pos.z; perpA = pos.x; perpB = pos.y; }\n");
        sb.append("    float sf = ").append(id).append("_strength * ").append(id).append("_frequency;\n");
        sb.append("    return 1.0 / max(1.0, abs(sf) * length(vec2(perpA, perpB)));\n");
        sb.append("}\n");
        // Transform function
        sb.append("vec3 applyTransform_").append(id).append("(vec3 pos) {\n");
        sb.append("    float axisVal;\n");
        sb.append("    float perpA, perpB;\n");
        sb.append("    if (").append(id).append("_axis == 0) { axisVal = pos.x; perpA = pos.y; perpB = pos.z; }\n");
        sb.append("    else if (").append(id).append("_axis == 1) { axisVal = pos.y; perpA = pos.x; perpB = pos.z; }\n");
        sb.append("    else { axisVal = pos.z; perpA = pos.x; perpB = pos.y; }\n");
        sb.append("    float sf = ").append(id).append("_strength * ").append(id).append("_frequency;\n");
        sb.append("    float angle = (axisVal + ").append(id).append("_offset) * sf;\n");
        sb.append("    float c = cos(angle), s = sin(angle);\n");
        sb.append("    float newA = c * perpA - s * perpB;\n");
        sb.append("    float newB = s * perpA + c * perpB;\n");
        sb.append("    if (").append(id).append("_axis == 0) return vec3(axisVal, newA, newB);\n");
        sb.append("    else if (").append(id).append("_axis == 1) return vec3(newA, axisVal, newB);\n");
        sb.append("    else return vec3(newA, newB, axisVal);\n");
        sb.append("}\n\n");
    }

    private void emitBendTransform(StringBuilder sb, String id) {
        sb.append("uniform int ").append(id).append("_axis;\n");
        sb.append("uniform float ").append(id).append("_strength;\n");
        sb.append("uniform float ").append(id).append("_frequency;\n");
        sb.append("uniform float ").append(id).append("_offset;\n");
        // DE correction function
        sb.append("float deCorr_").append(id).append("(vec3 pos) {\n");
        sb.append("    float axisVal;\n");
        sb.append("    if (").append(id).append("_axis == 0) axisVal = pos.x;\n");
        sb.append("    else if (").append(id).append("_axis == 1) axisVal = pos.y;\n");
        sb.append("    else axisVal = pos.z;\n");
        sb.append("    float sf = ").append(id).append("_strength * ").append(id).append("_frequency;\n");
        sb.append("    return 1.0 / max(1.0, abs(sf) * abs(axisVal));\n");
        sb.append("}\n");
        // Transform function: rotate in axis-perpA plane
        sb.append("vec3 applyTransform_").append(id).append("(vec3 pos) {\n");
        sb.append("    float axisVal;\n");
        sb.append("    float perpA, perpB;\n");
        sb.append("    if (").append(id).append("_axis == 0) { axisVal = pos.x; perpA = pos.y; perpB = pos.z; }\n");
        sb.append("    else if (").append(id).append("_axis == 1) { axisVal = pos.y; perpA = pos.x; perpB = pos.z; }\n");
        sb.append("    else { axisVal = pos.z; perpA = pos.x; perpB = pos.y; }\n");
        sb.append("    float sf = ").append(id).append("_strength * ").append(id).append("_frequency;\n");
        sb.append("    float angle = (axisVal + ").append(id).append("_offset) * sf;\n");
        sb.append("    float c = cos(angle), s = sin(angle);\n");
        sb.append("    float newAxis = c * axisVal - s * perpA;\n");
        sb.append("    float newPerp = s * axisVal + c * perpA;\n");
        sb.append("    if (").append(id).append("_axis == 0) return vec3(newAxis, newPerp, perpB);\n");
        sb.append("    else if (").append(id).append("_axis == 1) return vec3(newPerp, newAxis, perpB);\n");
        sb.append("    else return vec3(newPerp, perpB, newAxis);\n");
        sb.append("}\n\n");
    }

    private void emitTaperTransform(StringBuilder sb, String id) {
        sb.append("uniform int ").append(id).append("_axis;\n");
        sb.append("uniform float ").append(id).append("_strength;\n");
        sb.append("uniform float ").append(id).append("_frequency;\n");
        sb.append("uniform float ").append(id).append("_offset;\n");
        // DE correction function
        sb.append("float deCorr_").append(id).append("(vec3 pos) {\n");
        sb.append("    float axisVal;\n");
        sb.append("    if (").append(id).append("_axis == 0) axisVal = pos.x;\n");
        sb.append("    else if (").append(id).append("_axis == 1) axisVal = pos.y;\n");
        sb.append("    else axisVal = pos.z;\n");
        sb.append("    float sf = ").append(id).append("_strength * ").append(id).append("_frequency;\n");
        sb.append("    float scale = 1.0 + (axisVal + ").append(id).append("_offset) * sf;\n");
        sb.append("    return 1.0 / max(abs(scale), 0.01);\n");
        sb.append("}\n");
        // Transform function: scale perpendicular plane
        sb.append("vec3 applyTransform_").append(id).append("(vec3 pos) {\n");
        sb.append("    float axisVal;\n");
        sb.append("    float perpA, perpB;\n");
        sb.append("    if (").append(id).append("_axis == 0) { axisVal = pos.x; perpA = pos.y; perpB = pos.z; }\n");
        sb.append("    else if (").append(id).append("_axis == 1) { axisVal = pos.y; perpA = pos.x; perpB = pos.z; }\n");
        sb.append("    else { axisVal = pos.z; perpA = pos.x; perpB = pos.y; }\n");
        sb.append("    float sf = ").append(id).append("_strength * ").append(id).append("_frequency;\n");
        sb.append("    float scale = 1.0 + (axisVal + ").append(id).append("_offset) * sf;\n");
        sb.append("    float invScale = 1.0 / max(abs(scale), 0.01);\n");
        sb.append("    perpA = perpA * invScale;\n");
        sb.append("    perpB = perpB * invScale;\n");
        sb.append("    if (").append(id).append("_axis == 0) return vec3(axisVal, perpA, perpB);\n");
        sb.append("    else if (").append(id).append("_axis == 1) return vec3(perpA, axisVal, perpB);\n");
        sb.append("    else return vec3(perpA, perpB, axisVal);\n");
        sb.append("}\n\n");
    }

    private void emitRepetition1DTransform(StringBuilder sb, String id) {
        sb.append("uniform int ").append(id).append("_axis;\n");
        sb.append("uniform float ").append(id).append("_period;\n");
        sb.append("vec3 applyTransform_").append(id).append("(vec3 pos) {\n");
        sb.append("    float p = max(").append(id).append("_period, 0.001);\n");
        sb.append("    if (").append(id).append("_axis == 0) pos.x = mod(pos.x + p * 0.5, p) - p * 0.5;\n");
        sb.append("    else if (").append(id).append("_axis == 1) pos.y = mod(pos.y + p * 0.5, p) - p * 0.5;\n");
        sb.append("    else pos.z = mod(pos.z + p * 0.5, p) - p * 0.5;\n");
        sb.append("    return pos;\n");
        sb.append("}\n\n");
    }

    private void emitRepetitionTransform(StringBuilder sb, String id) {
        sb.append("uniform vec3 ").append(id).append("_period;\n");
        sb.append("vec3 applyTransform_").append(id).append("(vec3 pos) {\n");
        sb.append("    vec3 p = max(").append(id).append("_period, vec3(0.001));\n");
        sb.append("    return mod(pos + p * 0.5, p) - p * 0.5;\n");
        sb.append("}\n\n");
    }

    private String generateOrbitTrap() {
        return """
            // === Composite OrbitTrap ===
            struct OrbitTrap {
                float factorX;
                float factorY;
                float factorZ;
                float reserved;
                int iterations;
            };

            """;
    }

    private String generateDE(GraphNode root) {
        StringBuilder sb = new StringBuilder("// === Composite DE ===\n");
        sb.append("float DE(vec3 pos, out OrbitTrap trap) {\n");

        DEResult result = emitDEBody(root, "pos", sb, true);

        // Compute coloring factors from the winning leaf (or blended for morph)
        sb.append("    vec3 _gf;\n");
        if (result.factorsExpr() != null) {
            // Morph at root: use pre-computed blended factors
            sb.append("    _gf = ").append(result.factorsExpr()).append(";\n");
        } else if (leaves.size() == 1) {
            LeafInfo leaf = leaves.get(0);
            sb.append("    _gf = ").append(leaf.prefix).append("_getFactors(").append(leaf.prefix).append("_t);\n");
        } else {
            for (int i = 0; i < leaves.size(); i++) {
                LeafInfo leaf = leaves.get(i);
                String cond = (i == 0) ? "if" : "else if";
                if (i == leaves.size() - 1) {
                    sb.append("    else { _gf = ").append(leaf.prefix)
                      .append("_getFactors(").append(leaf.prefix).append("_t); }\n");
                } else {
                    sb.append("    ").append(cond).append(" (").append(result.winnerExpr)
                      .append(" == ").append(i).append(") { _gf = ").append(leaf.prefix)
                      .append("_getFactors(").append(leaf.prefix).append("_t); }\n");
                }
            }
        }

        sb.append("    trap = OrbitTrap(_gf.x, _gf.y, _gf.z, ").append(result.distVar).append(", 0);\n");
        sb.append("    return ").append(result.distVar).append(";\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private String generateDESimple(GraphNode root) {
        StringBuilder sb = new StringBuilder("// === Composite DE_simple ===\n");
        sb.append("float DE_simple(vec3 pos) {\n");

        DEResult result = emitDEBody(root, "pos", sb, false);

        sb.append("    return ").append(result.distVar).append(";\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private String generateGetFactors() {
        return """
            // === Composite getFactors ===
            vec3 getFactors(OrbitTrap trap) {
                return vec3(trap.factorX, trap.factorY, trap.factorZ);
            }

            """;
    }

    // ========================================================================
    // Recursive DE code emission
    // ========================================================================

    /**
     * @param distVar     GLSL variable holding the distance
     * @param winnerExpr  GLSL int expression selecting winning leaf (null in DE_simple)
     * @param factorsExpr Optional GLSL vec3 expression for blended factors (morph). Null = use winner.
     */
    private record DEResult(String distVar, String winnerExpr, String factorsExpr) {
        DEResult(String distVar, String winnerExpr) {
            this(distVar, winnerExpr, null);
        }
    }

    private DEResult emitDEBody(GraphNode node, String posVar, StringBuilder sb, boolean full) {
        if (node instanceof FractalNode fn) {
            return emitFractalDE(fn, posVar, sb, full);
        } else if (node instanceof TransformNode tn) {
            return emitTransformDE(tn, posVar, sb, full);
        } else if (node instanceof CSGNode csn) {
            return emitCSGDE(csn, posVar, sb, full);
        }
        throw new IllegalArgumentException("Unknown node type: " + node.getClass().getSimpleName());
    }

    private DEResult emitFractalDE(FractalNode fn, String posVar, StringBuilder sb, boolean full) {
        String prefix = fn.id;
        int leafIdx = leafIndex(fn);
        String dVar = prefix + "_d";

        if (full) {
            String tVar = prefix + "_t";
            sb.append("    ").append(prefix).append("_OrbitTrap ").append(tVar).append(";\n");
            sb.append("    float ").append(dVar).append(" = ").append(prefix)
              .append("_DE(").append(posVar).append(", ").append(tVar).append(");\n");
        } else {
            sb.append("    float ").append(dVar).append(" = ").append(prefix)
              .append("_DE_simple(").append(posVar).append(");\n");
        }
        return new DEResult(dVar, String.valueOf(leafIdx));
    }

    private DEResult emitTransformDE(TransformNode tn, String posVar, StringBuilder sb, boolean full) {
        String tid = tn.id;
        String newPos = "pos_" + tid;
        sb.append("    vec3 ").append(newPos).append(" = applyTransform_").append(tid)
          .append("(").append(posVar).append(");\n");

        DEResult child = emitDEBody(tn.getChild(), newPos, sb, full);

        String scaledVar = "d_" + tid;
        switch (tn.getMode()) {
            case STANDARD -> {
                // Scale correction: DE(scaled_pos) * scale
                sb.append("    float ").append(scaledVar).append(" = ").append(child.distVar)
                  .append(" * ").append(tid).append("_scale;\n");
            }
            case TWIST, BEND, TAPER -> {
                // Non-isometric: apply DE correction factor
                String corrVar = "corr_" + tid;
                sb.append("    float ").append(corrVar).append(" = deCorr_").append(tid)
                  .append("(").append(posVar).append(");\n");
                sb.append("    float ").append(scaledVar).append(" = ").append(child.distVar)
                  .append(" * ").append(corrVar).append(";\n");
            }
            default -> {
                // Mirror, Repetition, Repetition 1D: isometric — no correction
                sb.append("    float ").append(scaledVar).append(" = ").append(child.distVar).append(";\n");
            }
        }
        return new DEResult(scaledVar, child.winnerExpr);
    }

    private DEResult emitCSGDE(CSGNode csn, String posVar, StringBuilder sb, boolean full) {
        DEResult left = emitDEBody(csn.getLeft(), posVar, sb, full);
        DEResult right = emitDEBody(csn.getRight(), posVar, sb, full);

        String cid = csn.id;
        String dVar = "d_" + cid;
        String blendUniform = cid + "_blend";

        String leftD = left.distVar;
        String rightD = right.distVar;

        switch (csn.getOp()) {
            case UNION -> {
                sb.append("    float ").append(dVar).append(" = smin_graph(")
                  .append(leftD).append(", ").append(rightD).append(", ").append(blendUniform).append(");\n");
                if (full) {
                    String wVar = "w_" + cid;
                    sb.append("    int ").append(wVar).append(" = (").append(leftD)
                      .append(" <= ").append(rightD).append(") ? ")
                      .append(left.winnerExpr).append(" : ").append(right.winnerExpr).append(";\n");
                    return new DEResult(dVar, wVar);
                }
                return new DEResult(dVar, null);
            }
            case INTERSECT -> {
                sb.append("    float ").append(dVar).append(" = smax_graph(")
                  .append(leftD).append(", ").append(rightD).append(", ").append(blendUniform).append(");\n");
                if (full) {
                    String wVar = "w_" + cid;
                    sb.append("    int ").append(wVar).append(" = (").append(leftD)
                      .append(" >= ").append(rightD).append(") ? ")
                      .append(left.winnerExpr).append(" : ").append(right.winnerExpr).append(";\n");
                    return new DEResult(dVar, wVar);
                }
                return new DEResult(dVar, null);
            }
            case SUBTRACT -> {
                sb.append("    float ").append(dVar).append(" = smax_graph(")
                  .append(leftD).append(", -").append(rightD).append(", ").append(blendUniform).append(");\n");
                if (full) {
                    String wVar = "w_" + cid;
                    sb.append("    int ").append(wVar).append(" = (").append(leftD)
                      .append(" >= -").append(rightD).append(") ? ")
                      .append(left.winnerExpr).append(" : ").append(right.winnerExpr).append(";\n");
                    return new DEResult(dVar, wVar);
                }
                return new DEResult(dVar, null);
            }
            case MORPH -> {
                // mix(d1, d2, blend) — blend 0 = left, 1 = right
                sb.append("    float ").append(dVar).append(" = mix(")
                  .append(leftD).append(", ").append(rightD).append(", clamp(").append(blendUniform).append(", 0.0, 1.0));\n");
                if (full) {
                    // Blend coloring factors from both children proportionally
                    String leftFactors = resolveFactorsExpr(left);
                    String rightFactors = resolveFactorsExpr(right);
                    String morphGF = "gf_" + cid;
                    sb.append("    vec3 ").append(morphGF).append(" = mix(")
                      .append(leftFactors).append(", ").append(rightFactors)
                      .append(", clamp(").append(blendUniform).append(", 0.0, 1.0));\n");
                    // Winner still needed for non-morph ancestor CSG nodes
                    String wVar = "w_" + cid;
                    sb.append("    int ").append(wVar).append(" = (").append(blendUniform)
                      .append(" < 0.5) ? ").append(left.winnerExpr).append(" : ").append(right.winnerExpr).append(";\n");
                    return new DEResult(dVar, wVar, morphGF);
                }
                return new DEResult(dVar, null);
            }
        }
        throw new IllegalStateException("Unknown CSG op: " + csn.getOp());
    }

    // ========================================================================
    // Uniform collection
    // ========================================================================

    private static void collectUniformsFromNode(GraphNode node, Map<String, Object> uniforms) {
        if (node instanceof FractalNode fn) {
            AbstractFractalParams stored = fn.getFractalParams();
            if (stored != null) {
                emitFractalUniforms(uniforms, fn.id + "_", stored);
            } else {
                emitFractalUniforms(uniforms, fn.getFractalType(), fn.id + "_");
            }
        } else if (node instanceof TransformNode tn) {
            String id = tn.id;
            switch (tn.getMode()) {
                case STANDARD -> {
                    uniforms.put(id + "_offset", tn.getOffset().clone());
                    uniforms.put(id + "_rotX", (float) Math.toRadians(tn.getRotation()[0]));
                    uniforms.put(id + "_rotY", (float) Math.toRadians(tn.getRotation()[1]));
                    uniforms.put(id + "_rotZ", (float) Math.toRadians(tn.getRotation()[2]));
                    uniforms.put(id + "_scale", tn.getScale());
                }
                case MIRROR -> {
                    float[] axisVec = new float[3];
                    axisVec[tn.getAxis()] = 1.0f;
                    uniforms.put(id + "_mirrorAxis", axisVec);
                    uniforms.put(id + "_mirrorOffset", tn.getOffset()[tn.getAxis()]);
                }
                case TWIST, BEND, TAPER -> {
                    uniforms.put(id + "_axis", tn.getAxis());
                    uniforms.put(id + "_strength", tn.getScale());
                    uniforms.put(id + "_frequency", tn.getFrequency());
                    uniforms.put(id + "_offset", tn.getOffset()[0]);
                }
                case REPETITION -> {
                    uniforms.put(id + "_period", tn.getOffset().clone());
                }
                case REPETITION_1D -> {
                    uniforms.put(id + "_axis", tn.getAxis());
                    uniforms.put(id + "_period", tn.getOffset()[tn.getAxis()]);
                }
            }
            collectUniformsFromNode(tn.getChild(), uniforms);
        } else if (node instanceof CSGNode csn) {
            uniforms.put(csn.id + "_blend", csn.getBlend());
            collectUniformsFromNode(csn.getLeft(), uniforms);
            collectUniformsFromNode(csn.getRight(), uniforms);
        }
    }

    /**
     * Collect uniform values from an already-ID-assigned graph tree.
     * Use this for parameter-only updates (no recompilation needed).
     * Node IDs must have been assigned by a prior {@link #compile(GraphNode)} call.
     */
    public static Map<String, Object> collectUniformsStatic(GraphNode root) {
        Map<String, Object> uniforms = new LinkedHashMap<>();
        collectUniformsFromNode(root, uniforms);
        return uniforms;
    }

    // ========================================================================
    // Utilities
    // ========================================================================

    /**
     * Resolve a DEResult into a GLSL vec3 expression for coloring factors.
     * If the result has a factorsExpr (morph), use it directly.
     * Otherwise, if the result comes from a single leaf, call that leaf's getFactors.
     */
    private String resolveFactorsExpr(DEResult result) {
        if (result.factorsExpr() != null) return result.factorsExpr();
        // Single leaf: winnerExpr is a literal int index
        try {
            int idx = Integer.parseInt(result.winnerExpr());
            LeafInfo leaf = leaves.get(idx);
            return leaf.prefix + "_getFactors(" + leaf.prefix + "_t)";
        } catch (NumberFormatException e) {
            // winnerExpr is a runtime variable — can't resolve statically.
            // Fall back to first leaf (shouldn't happen in practice for morph children).
            return leaves.get(0).prefix + "_getFactors(" + leaves.get(0).prefix + "_t)";
        }
    }

    private int leafIndex(FractalNode fn) {
        for (int i = 0; i < leaves.size(); i++) {
            if (leaves.get(i).node == fn) return i;
        }
        throw new IllegalStateException("FractalNode not found in leaves: " + fn.getFractalType());
    }

    private String loadFractalShader(String kernelName) {
        String path = "/shaders/fractals/" + kernelName + ".glsl";
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) throw new RuntimeException("Shader not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load shader: " + path, e);
        }
    }

    private static float[] createRotationMatrix(double rx, double ry, double rz) {
        double ax = Math.toRadians(rx), ay = Math.toRadians(ry), az = Math.toRadians(rz);
        double cx = Math.cos(ax), sx = Math.sin(ax);
        double cy = Math.cos(ay), sy = Math.sin(ay);
        double cz = Math.cos(az), sz = Math.sin(az);
        return new float[]{
            (float)(cy*cz), (float)(sx*sy*cz - cx*sz), (float)(cx*sy*cz + sx*sz),
            (float)(cy*sz), (float)(sx*sy*sz + cx*cz), (float)(cx*sy*sz - sx*cz),
            (float)(-sy),   (float)(sx*cy),             (float)(cx*cy)
        };
    }
}
