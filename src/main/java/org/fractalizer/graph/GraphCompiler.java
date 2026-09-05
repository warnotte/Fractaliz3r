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
    private int primitiveCounter;
    private int hybridCounter;
    private int transformCounter;
    private int csgCounter;
    private int effectCounter;
    private int materialCounter;
    private final List<LeafInfo> leaves = new ArrayList<>();
    private final List<TransformInfo> transforms = new ArrayList<>();
    private final List<CSGInfo> csgNodes = new ArrayList<>();
    private final List<EffectInfo> effects = new ArrayList<>();
    private final List<MaterialInfo> materials = new ArrayList<>();

    private record LeafInfo(GraphNode node, String prefix) {}
    private record TransformInfo(TransformNode node, String id) {}
    private record CSGInfo(CSGNode node, String id) {}
    private record EffectInfo(EffectNode node, String id) {}
    private record MaterialInfo(MaterialNode node, String id, int index) {}

    /**
     * Compile a node graph into composite GLSL source code.
     * Assigns IDs to all nodes (accessible via {@link GraphNode#getId()}).
     *
     * @param root the root of the graph tree
     * @return GLSL source to be placed between common.glsl and raytracer.glsl
     */
    public String compile(GraphNode root) {
        fractalCounter = 0;
        primitiveCounter = 0;
        hybridCounter = 0;
        transformCounter = 0;
        csgCounter = 0;
        effectCounter = 0;
        materialCounter = 0;
        leaves.clear();
        transforms.clear();
        csgNodes.clear();
        effects.clear();
        materials.clear();

        // Phase 1: DFS to assign IDs and collect metadata
        assignIds(root);

        StringBuilder sb = new StringBuilder();

        // Emit material define + SSBO declaration if any MaterialNode present
        if (hasMaterialNodes()) {
            sb.append("#define HAS_MATERIALS\n\n");
            sb.append(generateMaterialSSBODeclaration());
        }

        // Phase 2: Load, strip, and preprocess each leaf shader
        for (LeafInfo leaf : leaves) {
            if (leaf.node instanceof PrimitiveNode pn) {
                // Inline GLSL generation for primitives (no .glsl file)
                sb.append("// === ").append(pn.getPrimitiveType().getDisplayName())
                  .append(" Primitive (").append(leaf.prefix).append(") ===\n");
                sb.append(generatePrimitiveGLSL(pn, leaf.prefix)).append("\n\n");
            } else if (leaf.node instanceof HybridNode hn) {
                sb.append("// === Hybrid chain (").append(leaf.prefix).append("): ")
                  .append(hn.describeChain()).append(" ===\n");
                sb.append(generateHybridGLSL(hn, leaf.prefix)).append("\n\n");
            } else if (leaf.node instanceof FractalNode fn) {
                String source;
                if (fn.getFractalType() == FractalType.CUSTOM_SHADER
                        && fn.getFractalParams() instanceof CustomShaderParams csp) {
                    source = csp.getShaderSource();
                } else {
                    source = loadFractalShader(fn.getFractalType().getKernelName());
                }
                String stripped = source.replaceAll("#version\\s+\\d+\\s*\\w*", "").trim();
                String preprocessed = ShaderPreprocessor.renameLocalSymbols(stripped, leaf.prefix + "_");
                sb.append("// === ").append(fn.getFractalType().getDisplayName())
                  .append(" (").append(leaf.prefix).append(") ===\n");
                sb.append(preprocessed).append("\n\n");
            }
        }

        // Phase 3: smin/smax helpers + CSG blend uniforms (only if CSG nodes exist)
        if (csgCounter > 0) {
            sb.append(generateHelpers());
            for (CSGInfo ci : csgNodes) {
                sb.append("uniform float ").append(ci.id).append("_blend;\n");
            }
            sb.append("\n");
        }

        // Phase 3.5: Effect uniforms
        if (!effects.isEmpty()) {
            sb.append(generateEffectUniforms());
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
            uniforms.put(prefix + "juliaC", new float[]{p.getJuliaCx(), p.getJuliaCy(), p.getJuliaCz()});
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
            uniforms.put(prefix + "basePrimitive", p.getBasePrimitive().ordinal());
        } else if (params instanceof PolyhedralIFSParams p) {
            uniforms.put(prefix + "polyType", p.getPolyType().ordinal());
            uniforms.put(prefix + "maxIterations", p.getMaxIterations());
            uniforms.put(prefix + "scale", p.getScale());
            uniforms.put(prefix + "offset", new float[]{p.getOffsetX(), p.getOffsetY(), p.getOffsetZ()});
            uniforms.put(prefix + "shift", new float[]{p.getShiftX(), p.getShiftY(), p.getShiftZ()});
            uniforms.put(prefix + "fractalRotation1", createRotationMatrix(p.getRot1X(), p.getRot1Y(), p.getRot1Z()));
            uniforms.put(prefix + "fractalRotation2", createRotationMatrix(p.getRot2X(), p.getRot2Y(), p.getRot2Z()));
            uniforms.put(prefix + "basePrimitive", p.getBasePrimitive().ordinal());
        } else if (params instanceof SierpinskiParams p) {
            uniforms.put(prefix + "maxIterations", p.getMaxIterations());
            uniforms.put(prefix + "scale", p.getScale());
            uniforms.put(prefix + "basePrimitive", p.getBasePrimitive().ordinal());
        } else if (params instanceof SphereflakeParams p) {
            uniforms.put(prefix + "maxIterations", p.getMaxIterations());
            uniforms.put(prefix + "childScale", p.getChildScale());
            uniforms.put(prefix + "spacing", p.getSpacing());
            uniforms.put(prefix + "rotAngleX", p.getRotAngleX());
            uniforms.put(prefix + "rotAngleY", p.getRotAngleY());
            uniforms.put(prefix + "rotAngleZ", p.getRotAngleZ());
            uniforms.put(prefix + "offsetY", p.getOffsetY());
            uniforms.put(prefix + "offsetZ", p.getOffsetZ());
            uniforms.put(prefix + "basePrimitive", p.getBasePrimitive().ordinal());
        } else if (params instanceof KochSurfaceParams p) {
            uniforms.put(prefix + "maxIterations", p.getMaxIterations());
            uniforms.put(prefix + "scale", p.getScale());
            uniforms.put(prefix + "basePrimitive", p.getBasePrimitive().ordinal());
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
            uniforms.put(prefix + "basePrimitive", p.getBasePrimitive().ordinal());
        } else if (params instanceof BristorbrotParams p) {
            uniforms.put(prefix + "maxIterations", p.getMaxIterations());
            uniforms.put(prefix + "bailout", p.getBailout());
            uniforms.put(prefix + "juliaC", new float[]{p.getJuliaCx(), p.getJuliaCy(), p.getJuliaCz()});
        } else if (params instanceof MandelorusParams p) {
            uniforms.put(prefix + "maxIterations", p.getMaxIterations());
            uniforms.put(prefix + "bailout", p.getBailout());
            uniforms.put(prefix + "ringRadius", p.getRingRadius());
            uniforms.put(prefix + "torusTwist", p.getTorusTwist());
            uniforms.put(prefix + "power", p.getPower());
            uniforms.put(prefix + "ringPhase", (float) Math.toRadians(p.getRingPhase()));
            uniforms.put(prefix + "crossPhase", (float) Math.toRadians(p.getCrossPhase()));
            uniforms.put(prefix + "vertScale", p.getVertScale());
        } else if (params instanceof QuaternionJulia4DParams p) {
            uniforms.put(prefix + "maxIterations", p.getMaxIterations());
            uniforms.put(prefix + "bailout", p.getBailout());
            uniforms.put(prefix + "juliaC", new float[]{p.getJuliaCx(), p.getJuliaCy(), p.getJuliaCz(), p.getJuliaCw()});
            uniforms.put(prefix + "sliceW", p.getSliceW());
            uniforms.put(prefix + "rotXW", (float) Math.toRadians(p.getRotXW()));
            uniforms.put(prefix + "rotYW", (float) Math.toRadians(p.getRotYW()));
            uniforms.put(prefix + "rotZW", (float) Math.toRadians(p.getRotZW()));
        } else if (params instanceof MengerAdvancedParams p) {
            uniforms.put(prefix + "maxIterations", p.getMaxIterations());
            uniforms.put(prefix + "scale", p.getScale());
            uniforms.put(prefix + "offset", p.getOffset());
            uniforms.put(prefix + "rotX", p.getRotX());
            uniforms.put(prefix + "rotZ", p.getRotZ());
            uniforms.put(prefix + "innerFold", p.getInnerFold());
            uniforms.put(prefix + "zScale", p.getZScale());
        } else if (params instanceof MengerSpongeTestParams p) {
            uniforms.put(prefix + "maxIterations", p.getMaxIterations());
            uniforms.put(prefix + "scale", p.getScale());
            uniforms.put(prefix + "offset", p.getOffset());
            uniforms.put(prefix + "rotX", p.getRotX());
            uniforms.put(prefix + "rotZ", p.getRotZ());
            uniforms.put(prefix + "zShift", p.getZShift());
            uniforms.put(prefix + "centerZ", p.getCenterZ());
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
        if (node instanceof PrimitiveNode pn) {
            String prefix = "p" + primitiveCounter++;
            pn.id = prefix;
            leaves.add(new LeafInfo(pn, prefix));
        } else if (node instanceof HybridNode hn) {
            String prefix = "h" + hybridCounter++;
            hn.id = prefix;
            leaves.add(new LeafInfo(hn, prefix));
        } else if (node instanceof FractalNode fn) {
            String prefix = "n" + fractalCounter++;
            fn.id = prefix;
            leaves.add(new LeafInfo(fn, prefix));
        } else if (node instanceof TransformNode tn) {
            String tid = "t" + transformCounter++;
            tn.id = tid;
            transforms.add(new TransformInfo(tn, tid));
            assignIds(tn.getChild());
        } else if (node instanceof EffectNode en) {
            String eid = "e" + effectCounter++;
            en.id = eid;
            effects.add(new EffectInfo(en, eid));
            assignIds(en.getChild());
        } else if (node instanceof MaterialNode mn) {
            String mid = "m" + materialCounter++;
            mn.id = mid;
            materials.add(new MaterialInfo(mn, mid, materials.size()));
            assignIds(mn.getChild());
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

    private boolean hasMaterialNodes() {
        return !materials.isEmpty();
    }

    private String generateOrbitTrap() {
        if (hasMaterialNodes()) {
            return """
                // === Composite OrbitTrap ===
                struct OrbitTrap {
                    float factorX;
                    float factorY;
                    float factorZ;
                    float reserved;
                    int iterations;
                    int matId;  // -1 = use global, >= 0 = SSBO index
                };

                """;
        }
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

        // Declare material ID local (used by MaterialNode overrides, indexes into SSBO)
        if (hasMaterialNodes()) {
            sb.append("    int _matId = -1;\n");
        }

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

        if (hasMaterialNodes()) {
            sb.append("    trap = OrbitTrap(_gf.x, _gf.y, _gf.z, ").append(result.distVar)
              .append(", 0, _matId);\n");
        } else {
            sb.append("    trap = OrbitTrap(_gf.x, _gf.y, _gf.z, ").append(result.distVar).append(", 0);\n");
        }
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
        if (node instanceof PrimitiveNode pn) {
            return emitPrimitiveDE(pn, posVar, sb, full);
        } else if (node instanceof HybridNode hn) {
            return emitLeafDE(hn.getId(), leafIndex(hn), posVar, sb, full);
        } else if (node instanceof FractalNode fn) {
            return emitFractalDE(fn, posVar, sb, full);
        } else if (node instanceof MaterialNode mn) {
            return emitMaterialDE(mn, posVar, sb, full);
        } else if (node instanceof EffectNode en) {
            return emitEffectDE(en, posVar, sb, full);
        } else if (node instanceof TransformNode tn) {
            return emitTransformDE(tn, posVar, sb, full);
        } else if (node instanceof CSGNode csn) {
            return emitCSGDE(csn, posVar, sb, full);
        }
        throw new IllegalArgumentException("Unknown node type: " + node.getClass().getSimpleName());
    }

    /** Every leaf kind exposes the same contract — {prefix}_DE / _DE_simple / _OrbitTrap —
     *  so the call site is identical whatever produced it. */
    private DEResult emitLeafDE(String prefix, int leafIdx, String posVar, StringBuilder sb, boolean full) {
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
        String cid = csn.id;
        boolean matNodes = full && hasMaterialNodes();

        DEResult left = emitDEBody(csn.getLeft(), posVar, sb, full);

        // Save left-side material ID before right subtree overwrites it
        if (matNodes) {
            sb.append("    int _matId_L_").append(cid).append(" = _matId;\n");
            sb.append("    _matId = -1;\n");
        }

        DEResult right = emitDEBody(csn.getRight(), posVar, sb, full);

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
                    if (matNodes) emitCSGMaterialPick(sb, cid, leftD + " <= " + rightD);
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
                    if (matNodes) emitCSGMaterialPick(sb, cid, leftD + " >= " + rightD);
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
                    if (matNodes) emitCSGMaterialPick(sb, cid, leftD + " >= -" + rightD);
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
                    // Blend material properties for morph (smooth interpolation)
                    if (matNodes) emitCSGMaterialMorph(sb, cid, blendUniform);
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
        if (node instanceof PrimitiveNode pn) {
            String id = pn.getId();
            uniforms.put(id + "_sizeX", pn.getSizeX());
            uniforms.put(id + "_sizeY", pn.getSizeY());
            uniforms.put(id + "_sizeZ", pn.getSizeZ());
            uniforms.put(id + "_rounding", pn.getRounding());
            uniforms.put(id + "_shell", pn.getShell());
        } else if (node instanceof HybridNode hn) {
            collectHybridUniforms(hn, uniforms);
        } else if (node instanceof FractalNode fn) {
            AbstractFractalParams stored = fn.getFractalParams();
            if (stored != null) {
                emitFractalUniforms(uniforms, fn.id + "_", stored);
            } else {
                emitFractalUniforms(uniforms, fn.getFractalType(), fn.id + "_");
            }
        } else if (node instanceof EffectNode en) {
            String id = en.getId();
            uniforms.put(id + "_strength", en.getStrength());
            uniforms.put(id + "_time", en.getTime());
            uniforms.put(id + "_scale", en.getScale());
            switch (en.getEffectType()) {
                case EROSION -> uniforms.put(id + "_erosionType", en.getErosionType());
                case CRYSTAL -> uniforms.put(id + "_sharpness", en.getSharpness());
                default -> {}
            }
            collectUniformsFromNode(en.getChild(), uniforms);
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
        } else if (node instanceof MaterialNode mn) {
            // Material data flows through SSBO, not uniforms
            collectUniformsFromNode(mn.getChild(), uniforms);
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

    /**
     * Collect material SSBO data from a graph tree (DFS order matching assignIds).
     * Returns a flat float array: 12 floats per MaterialNode, suitable for GL_SHADER_STORAGE_BUFFER.
     * Returns null if no MaterialNodes are present.
     */
    public static float[] collectMaterialSSBOData(GraphNode root) {
        List<MaterialNode> mats = new ArrayList<>();
        collectMaterialNodes(root, mats);
        if (mats.isEmpty()) return null;
        float[] data = new float[mats.size() * 12];
        for (int i = 0; i < mats.size(); i++) {
            MaterialNode mn = mats.get(i);
            int base = i * 12;
            data[base]     = mn.getMaterialType();
            data[base + 1] = mn.getColorMode();
            data[base + 2] = mn.getColorR();
            data[base + 3] = mn.getColorG();
            data[base + 4] = mn.getColorB();
            data[base + 5] = mn.getRoughness();
            data[base + 6] = mn.getMetallic();
            data[base + 7] = mn.getIor();
            data[base + 8] = mn.getEmission();
            // 9, 10, 11 = padding (0.0)
        }
        return data;
    }

    private static void collectMaterialNodes(GraphNode node, List<MaterialNode> result) {
        if (node instanceof MaterialNode mn) {
            result.add(mn);
            collectMaterialNodes(mn.getChild(), result);
        } else if (node instanceof TransformNode tn) {
            collectMaterialNodes(tn.getChild(), result);
        } else if (node instanceof EffectNode en) {
            collectMaterialNodes(en.getChild(), result);
        } else if (node instanceof CSGNode csn) {
            collectMaterialNodes(csn.getLeft(), result);
            collectMaterialNodes(csn.getRight(), result);
        }
        // FractalNode and PrimitiveNode are leaves — no children
    }

    // ========================================================================
    // Effect node support
    // ========================================================================

    private String generateEffectUniforms() {
        StringBuilder sb = new StringBuilder("// === Effect node uniforms ===\n");
        for (EffectInfo ei : effects) {
            String id = ei.id;
            sb.append("uniform float ").append(id).append("_strength;\n");
            sb.append("uniform float ").append(id).append("_time;\n");
            sb.append("uniform float ").append(id).append("_scale;\n");
            switch (ei.node.getEffectType()) {
                case EROSION -> sb.append("uniform int ").append(id).append("_erosionType;\n");
                case CRYSTAL -> sb.append("uniform float ").append(id).append("_sharpness;\n");
                default -> {}
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    private DEResult emitEffectDE(EffectNode en, String posVar, StringBuilder sb, boolean full) {
        DEResult child = emitDEBody(en.getChild(), posVar, sb, full);
        String eid = en.id;
        String dVar = child.distVar();

        // Emit displacement with proximity gating inside a { } scope
        sb.append("    { // Effect ").append(eid).append(" (").append(en.getEffectType()).append(")\n");

        switch (en.getEffectType()) {
            case EROSION -> {
                sb.append("      float _emaxD = erosionMaxDisplacementP(")
                  .append(eid).append("_strength, ")
                  .append(eid).append("_time, ")
                  .append(eid).append("_scale);\n");
                sb.append("      if (").append(dVar).append(" < _emaxD + 0.1) ");
                if (full) {
                    sb.append(dVar).append(" += getErosionDisplacementP(")
                      .append(posVar).append(", ")
                      .append(eid).append("_strength, ")
                      .append(eid).append("_time, ")
                      .append(eid).append("_scale, ")
                      .append(eid).append("_erosionType);\n");
                } else {
                    sb.append(dVar).append(" += getErosionDisplacementLightP(")
                      .append(posVar).append(", ")
                      .append(eid).append("_strength, ")
                      .append(eid).append("_time, ")
                      .append(eid).append("_scale, ")
                      .append(eid).append("_erosionType);\n");
                }
            }
            case CRYSTAL -> {
                sb.append("      float _cmaxD = crystalMaxDisplacementP(")
                  .append(eid).append("_strength, ")
                  .append(eid).append("_time, ")
                  .append(eid).append("_scale);\n");
                sb.append("      if (").append(dVar).append(" < _cmaxD + 0.1) ");
                if (full) {
                    sb.append(dVar).append(" += getCrystalDisplacementP(")
                      .append(posVar).append(", ")
                      .append(eid).append("_strength, ")
                      .append(eid).append("_time, ")
                      .append(eid).append("_scale, ")
                      .append(eid).append("_sharpness);\n");
                } else {
                    sb.append(dVar).append(" += getCrystalDisplacementLightP(")
                      .append(posVar).append(", ")
                      .append(eid).append("_strength, ")
                      .append(eid).append("_time, ")
                      .append(eid).append("_scale, ")
                      .append(eid).append("_sharpness);\n");
                }
            }
            case MOSS -> {
                sb.append("      float _mmaxD = mossMaxDisplacementP(")
                  .append(eid).append("_strength, ")
                  .append(eid).append("_time, ")
                  .append(eid).append("_scale);\n");
                sb.append("      if (").append(dVar).append(" < _mmaxD + 0.1) ");
                if (full) {
                    sb.append(dVar).append(" += getMossDisplacementP(")
                      .append(posVar).append(", ")
                      .append(eid).append("_strength, ")
                      .append(eid).append("_time, ")
                      .append(eid).append("_scale);\n");
                } else {
                    sb.append(dVar).append(" += getMossDisplacementLightP(")
                      .append(posVar).append(", ")
                      .append(eid).append("_strength, ")
                      .append(eid).append("_time, ")
                      .append(eid).append("_scale);\n");
                }
            }
        }

        sb.append("    }\n");

        // Effect doesn't change coloring — pass through child's winner/factors
        return new DEResult(dVar, child.winnerExpr(), child.factorsExpr());
    }

    // ========================================================================
    // Material node support
    // ========================================================================

    private String generateMaterialSSBODeclaration() {
        return """
            // === Material SSBO ===
            struct MaterialData {
                float type;       // int packed as float: -1=global, 0=Lambertian, 1=Metallic, 2=Glass
                float colorMode;  // 0=palette, 1=solid, 2=tint
                float albedoR, albedoG, albedoB;
                float roughness, metallic, ior, emission;
                float _pad0, _pad1, _pad2;
            };

            layout(std430, binding = 6) readonly buffer MaterialBuffer {
                MaterialData materials[];
            };

            """;
    }

    private DEResult emitMaterialDE(MaterialNode mn, String posVar, StringBuilder sb, boolean full) {
        DEResult child = emitDEBody(mn.getChild(), posVar, sb, full);
        if (full) {
            // Find this material's SSBO index
            int index = -1;
            for (MaterialInfo mi : materials) {
                if (mi.node == mn) { index = mi.index; break; }
            }
            sb.append("    _matId = ").append(index).append("; // ").append(mn.id).append("\n");
        }
        return new DEResult(child.distVar(), child.winnerExpr(), child.factorsExpr());
    }

    /**
     * Emit material pick for Union/Intersect/Subtract CSG: winner takes all.
     * Left-side matId was saved before right subtree ran. Current _matId holds right side.
     */
    private void emitCSGMaterialPick(StringBuilder sb, String cid, String leftWinsCond) {
        sb.append("    if (").append(leftWinsCond).append(") _matId = _matId_L_").append(cid).append(";\n");
    }

    /**
     * Emit material pick for Morph CSG: snap at blend=0.5.
     * Left-side matId was saved before right subtree ran. Current _matId holds right side.
     */
    private void emitCSGMaterialMorph(StringBuilder sb, String cid, String blendUniform) {
        sb.append("    _matId = (").append(blendUniform).append(" < 0.5) ? _matId_L_").append(cid).append(" : _matId;\n");
    }

    // ========================================================================
    // Primitive node support
    // ========================================================================

    private DEResult emitPrimitiveDE(PrimitiveNode pn, String posVar, StringBuilder sb, boolean full) {
        String prefix = pn.id;
        int leafIdx = leafIndex(pn);
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

    // ========================================================================
    // Hybrid chains
    // ========================================================================
    // A CSG node combines two finished distance fields. A hybrid composes the maps
    // themselves: one loop, the steps applied in order, so the orbit is taken under
    // the composition. Every step carries its own derivative update, which is what
    // keeps the result a usable distance estimator.

    private String generateHybridGLSL(HybridNode hn, String p) {
        StringBuilder sb = new StringBuilder();
        List<HybridNode.Step> steps = hn.getSteps();

        sb.append("uniform int ").append(p).append("_maxIterations;\n");
        sb.append("uniform float ").append(p).append("_bailout;\n");
        sb.append("uniform vec3 ").append(p).append("_juliaC;\n");
        for (int i = 0; i < steps.size(); i++) {
            String u = p + "_s" + i + "_";
            for (String decl : stepUniforms(steps.get(i).getType())) {
                sb.append("uniform ").append(decl.replace("$", u)).append(";\n");
            }
        }
        sb.append("\n");

        sb.append("struct ").append(p).append("_OrbitTrap {\n")
          .append("    float minDist;\n    float planeX;\n    float planeY;\n    float planeZ;\n")
          .append("    float escapeR;\n    int iterations;\n};\n\n");

        // One shared chain body, so DE and DE_simple cannot drift apart. The iteration
        // index is passed in for the steps that depend on it: per-iteration rotation,
        // and any step gated to a range of iterations.
        sb.append("void ").append(p).append("_chain(inout vec3 z, inout float dr, vec3 c, int i) {\n");
        for (int i = 0; i < steps.size(); i++) {
            HybridNode.Step st = steps.get(i);
            String body = stepBody(st, p + "_s" + i + "_");
            if (st.isGated()) {
                // Baked in rather than a uniform: the gate decides which code runs on
                // which pass, and the editor treats it as a structural edit.
                String cond = "i >= " + st.getIterStart() + " && i < " + st.getIterEnd();
                if (st.getIterEvery() != 1) {
                    cond += " && ((i - " + st.getIterStart() + ") % " + st.getIterEvery() + ") == 0";
                }
                body = "    if (" + cond + ") {\n" + body.indent(4) + "    }\n";
            }
            sb.append(body);
        }
        sb.append("}\n\n");

        String iterBound = p + "_maxIterations + gExtraIterations";
        String seed = "vec3 c = (dot(" + p + "_juliaC, " + p + "_juliaC) > 0.0001) ? " + p + "_juliaC : pos;\n";
        // Escape-time uses the radius at which the orbit escaped, captured at the top of
        // the loop exactly as the stand-alone shaders do. An IFS estimator instead needs
        // the radius of the final orbit point: when the loop ends by exhausting its
        // iterations rather than by escaping, those are different values, and using the
        // loop-top one makes the estimator disagree with the formula it should reproduce.
        String finalR = (hn.getDeMode() == HybridNode.DEMode.LOG) ? "" : "    r = length(z);\n";
        String deExpr = switch (hn.getDeMode()) {
            case LOG -> "0.5 * log(r) * r / dr";
            case LINEAR -> "r / max(abs(dr), 1e-9)";
            // Knighty's estimator for the pseudo-Kleinian: the distance of the final
            // orbit point to a plane, divided by the accumulated stretch. Orbits under
            // that fold never escape, so an escape radius means nothing to them. The
            // stand-alone pseudokleinian.glsl starts its stretch at 1.5 rather than 1
            // as a safety margin; the same margin is folded into the constant here.
            case PLANE -> "abs(z.z + 0.1) / (3.0 * max(abs(dr), 1e-9))";
        };

        sb.append("float ").append(p).append("_DE_simple(vec3 pos) {\n")
          .append("    vec3 z = pos;\n    ").append(seed)
          .append("    float dr = 1.0;\n    float r = 0.0;\n")
          .append("    for (int i = 0; i < ").append(iterBound).append("; i++) {\n")
          .append("        r = length(z);\n")
          .append("        if (r > ").append(p).append("_bailout) break;\n")
          .append("        ").append(p).append("_chain(z, dr, c, i);\n")
          .append("    }\n")
          .append(finalR)
          .append("    float de = ").append(deExpr).append(";\n")
          .append("    float rPos = length(pos);\n")
          .append("    if (rPos > 2.0 * ").append(p).append("_bailout) de = min(de, rPos - ").append(p).append("_bailout);\n")
          .append("    return de;\n}\n\n");

        sb.append("float ").append(p).append("_DE(vec3 pos, out ").append(p).append("_OrbitTrap trap) {\n")
          .append("    vec3 z = pos;\n    ").append(seed)
          .append("    float dr = 1.0;\n    float r = 0.0;\n")
          .append("    trap.minDist = 1e10;\n    trap.planeX = 0.0;\n")
          .append("    trap.planeY = 0.0;\n    trap.planeZ = 0.0;\n")
          .append("    trap.escapeR = 0.0;\n    trap.iterations = 0;\n")
          .append("    for (int i = 0; i < ").append(iterBound).append("; i++) {\n")
          .append("        r = length(z);\n")
          .append("        if (r > ").append(p).append("_bailout) break;\n")
          .append("        ").append(p).append("_chain(z, dr, c, i);\n")
          .append("        trap.minDist = min(trap.minDist, length(z));\n")
          .append("        vec3 _zd = normalize(z + vec3(1e-9));\n")
          .append("        trap.planeX = _zd.x;\n")
          .append("        trap.planeY = _zd.y;\n")
          .append("        trap.planeZ = _zd.z;\n")
          .append("        trap.iterations = i + 1;\n")
          .append("    }\n")
          .append("    trap.escapeR = r;\n")
          .append(finalR)
          .append("    float de = ").append(deExpr).append(";\n")
          .append("    float rPos = length(pos);\n")
          .append("    if (rPos > 2.0 * ").append(p).append("_bailout) de = min(de, rPos - ").append(p).append("_bailout);\n")
          .append("    return de;\n}\n\n");

        sb.append("vec3 ").append(p).append("_getFactors(").append(p).append("_OrbitTrap trap) {\n")
          .append("    float _n = float(max(trap.iterations, 1));\n")
          .append("    float _az = atan(trap.planeY, trap.planeX) / 6.2831853 + 0.5;\n")
          .append("    float _el = acos(clamp(trap.planeZ, -1.0, 1.0)) / 3.1415927;\n")
          .append("    float structural = 1.0 - exp(-trap.minDist * 0.22);\n")
          .append("    float _sm = _n;\n")
          .append("    if (trap.escapeR > 1.0001) {\n")
          .append("        float _lb = log(max(").append(p).append("_bailout, 1.0001));\n")
          .append("        _sm = _n - log2(max(log(trap.escapeR) / _lb, 1e-6));\n")
          .append("    }\n")
          .append("    float iterNorm = clamp(_sm / float(max(")
          .append(p).append("_maxIterations + gExtraIterations, 1)), 0.0, 1.0);\n")
          .append("    float flow = fract(_az + _el * 0.35 + iterNorm * 0.6);\n")
          .append("    return vec3(structural, flow, iterNorm);\n}\n");

        return sb.toString();
    }

    /** Uniform declarations a step type needs, with "$" standing for the per-step
     *  prefix. The same table drives {@link #collectHybridUniforms}, so a step cannot
     *  declare a uniform it never receives a value for. */
    static List<String> stepUniforms(HybridNode.StepType t) {
        return switch (t) {
            case BULB, BULB_COSINE, COMPLEX_POWER -> List.of("float $power");
            case RIEMANN -> List.of("float $power", "float $scale");
            case QUAT_SQUARE, BRISTOR, BENESI_MAG, ADD_C -> List.of();
            case BOX_FOLD, AMAZING_SURF -> List.of("float $foldLimit", "float $minRadius", "float $fixedRadius", "float $scale");
            case BOX_FOLD_ONLY -> List.of("float $foldLimit");
            case SPHERE_FOLD -> List.of("float $minRadius", "float $fixedRadius");
            case ABOX_MOD -> List.of("vec3 $offset", "float $minRadius", "float $fixedRadius", "float $scale");
            case KLEINIAN_FOLD -> List.of("vec3 $offset", "float $radius");
            case MENGER_FOLD, SIERPINSKI_FOLD, OCTA_FOLD, ICOSA_FOLD, BENESI_FOLD, SCALE -> List.of("float $scale", "vec3 $offset");
            case ABS_FOLD -> List.of("vec3 $offset");
            case PLANE_FOLD -> List.of("vec3 $normal", "float $dist");
            case ROTATIONAL_FOLD -> List.of("int $count");
            case KALI_FOLD -> List.of("float $radius", "vec3 $offset");
            case SPHERE_INVERT -> List.of("float $radius");
            case ROTATE -> List.of("mat3 $rot");
            case ROTATE_ITER -> List.of("vec3 $rotIter");
            case TWIST -> List.of("float $twist");
        };
    }

    /** The Mandelbox sphere fold, shared by every step that contains one. */
    private static final String SPHERE_FOLD_BODY = """
            float _r2 = dot(z, z);
            float _mn = %1$sminRadius * %1$sminRadius;
            float _fx = %1$sfixedRadius * %1$sfixedRadius;
            if (_r2 < _mn) { float _f = _fx / _mn; z = z * _f; dr = dr * _f; }
            else if (_r2 < _fx) { float _f = _fx / _r2; z = z * _f; dr = dr * _f; }
            """;

    /** Scale about the origin and pull towards the offset — the IFS contraction that
     *  ends every KIFS fold. */
    private static final String IFS_SCALE_BODY = """
            float _s = %1$sscale;
            vec3 _o = %1$soffset;
            z = z * _s - _o * (_s - 1.0);
            dr = dr * abs(_s);
            """;

    /** Plane coordinates (u, v) around one axis and the write-back, for the steps that
     *  act in the plane perpendicular to an axis. */
    private static String planeRead(int axis) {
        return switch (axis) {
            case 0 -> "vec2 _pq = z.yz;";
            case 1 -> "vec2 _pq = z.zx;";
            default -> "vec2 _pq = z.xy;";
        };
    }

    private static String planeWrite(int axis) {
        return switch (axis) {
            case 0 -> "z = vec3(z.x, _pq.x, _pq.y);";
            case 1 -> "z = vec3(_pq.y, z.y, _pq.x);";
            default -> "z = vec3(_pq.x, _pq.y, z.z);";
        };
    }

    private static String axisComponent(int axis) {
        return switch (axis) { case 0 -> "z.x"; case 1 -> "z.y"; default -> "z.z"; };
    }

    /** GLSL for one step. Local names are underscore-prefixed and block-scoped so
     *  repeated steps in a chain cannot collide. */
    private static String stepBody(HybridNode.Step st, String u) {
        return switch (st.getType()) {
            case BULB -> ("""
                {
                    float _r = length(z);
                    if (_r > 1e-8) {
                        float _p = %1$spower;
                        float _th = acos(clamp(z.z / _r, -1.0, 1.0)) * _p;
                        float _ph = atan(z.y, z.x) * _p;
                        float _zr = pow(_r, _p);
                        dr = pow(_r, _p - 1.0) * _p * dr;
                        z = _zr * vec3(sin(_th) * cos(_ph), sin(_th) * sin(_ph), cos(_th));
                    }
                }
                """).formatted(u).indent(4);
            // Nylander's other convention: the polar angle is measured from the XY plane
            // (asin) instead of from the Z axis (acos). Same power, a different bulb.
            case BULB_COSINE -> ("""
                {
                    float _r = length(z);
                    if (_r > 1e-8) {
                        float _p = %1$spower;
                        float _ph = atan(z.y, z.x) * _p;
                        float _th = asin(clamp(z.z / _r, -1.0, 1.0)) * _p;
                        float _zr = pow(_r, _p);
                        dr = pow(_r, _p - 1.0) * _p * dr;
                        z = _zr * vec3(cos(_th) * cos(_ph), cos(_th) * sin(_ph), sin(_th));
                    }
                }
                """).formatted(u).indent(4);
            // (x + yi + zj)^2 with the k part dropped — the w = 0 slice of the quaternion
            // square, which is also the 3D slice of the bicomplex Tetrabrot.
            case QUAT_SQUARE -> """
                {
                    float _r = length(z);
                    dr = 2.0 * _r * dr;
                    z = vec3(z.x * z.x - z.y * z.y - z.z * z.z, 2.0 * z.x * z.y, 2.0 * z.x * z.z);
                }
                """.indent(4);
            // Same as the stand-alone bristorbrot.glsl, minus the +1 that ADD_C supplies.
            case BRISTOR -> """
                {
                    float _r = length(z);
                    dr = 2.0 * _r * dr;
                    z = vec3(z.x * z.x - z.y * z.y - z.z * z.z, 2.0 * z.x * z.y, -2.0 * z.x * z.z);
                }
                """.indent(4);
            // Benesi's quadratic "mag transform": x^2 - y^2 - z^2 on X, and the YZ plane
            // gets its angle doubled with the 2*x*rho magnitude of a complex square.
            case BENESI_MAG -> """
                {
                    float _r = length(z);
                    vec3 _q = z * z;
                    float _yz = _q.y + _q.z;
                    float _t = (_yz > 0.0) ? 2.0 * z.x / sqrt(_yz) : 1.0;
                    dr = 2.0 * _r * dr;
                    z = vec3(_q.x - _q.y - _q.z, 2.0 * _t * z.y * z.z, _t * (_q.y - _q.z));
                }
                """.indent(4);
            // msltoe's Riemann sphere: project the direction stereographically, fold the
            // plane with |sin|, project back, and raise the radius to the power. The
            // plane frequency is the scale parameter.
            case RIEMANN -> ("""
                {
                    float _r = length(z);
                    if (_r > 1e-8) {
                        vec3 _u = z / _r;
                        float _q = %1$sscale / max(1.0 - _u.z, 1e-6);
                        float _s = abs(sin(3.1415927 * _u.x * _q));
                        float _t = abs(sin(3.1415927 * _u.y * _q));
                        float _d = 1.0 + _s * _s + _t * _t;
                        float _p = %1$spower;
                        dr = pow(_r, _p - 1.0) * _p * dr;
                        z = vec3(2.0 * _s, 2.0 * _t, _d - 2.0) * (pow(_r, _p) / _d);
                    }
                }
                """).formatted(u).indent(4);
            // z^p on the complex XY plane; Z passes through, so alone this extrudes a 2D
            // Julia set along Z. A Twist or Rotate per Iteration before it bends the stack.
            case COMPLEX_POWER -> String.join("\n",
                    "{",
                    "    " + planeRead(st.getAxis()),
                    "    float _rr = length(_pq);",
                    "    if (_rr > 1e-8) {",
                    "        float _p = " + u + "power;",
                    "        float _a = atan(_pq.y, _pq.x) * _p;",
                    "        float _rp = pow(_rr, _p);",
                    "        dr = pow(_rr, _p - 1.0) * _p * dr;",
                    "        _pq = vec2(_rp * cos(_a), _rp * sin(_a));",
                    "        " + planeWrite(st.getAxis()),
                    "    }",
                    "}", "").indent(4);
            case BOX_FOLD -> ("""
                {
                    float _L = %1$sfoldLimit;
                    z = clamp(z, -_L, _L) * 2.0 - z;
                    float _r2 = dot(z, z);
                    float _mn = %1$sminRadius * %1$sminRadius;
                    float _fx = %1$sfixedRadius * %1$sfixedRadius;
                    if (_r2 < _mn) { float _f = _fx / _mn; z = z * _f; dr = dr * _f; }
                    else if (_r2 < _fx) { float _f = _fx / _r2; z = z * _f; dr = dr * _f; }
                    float _s = %1$sscale;
                    z = z * _s;
                    dr = dr * abs(_s);
                }
                """).formatted(u).indent(4);
            case MENGER_FOLD -> ("""
                {
                    z = abs(z);
                    if (z.x < z.y) { float _t = z.x; z.x = z.y; z.y = _t; }
                    if (z.x < z.z) { float _t = z.x; z.x = z.z; z.z = _t; }
                    if (z.y < z.z) { float _t = z.y; z.y = z.z; z.z = _t; }
                    float _s = %1$sscale;
                    vec3 _o = %1$soffset;
                    z = z * _s - _o * (_s - 1.0);
                    float _lim = -0.5 * _o.z * (_s - 1.0);
                    if (z.z < _lim) { z.z = z.z + _o.z * (_s - 1.0); }
                    dr = dr * abs(_s);
                }
                """).formatted(u).indent(4);
            case SIERPINSKI_FOLD -> ("""
                {
                    if (z.x + z.y < 0.0) { float _t = -z.y; z.y = -z.x; z.x = _t; }
                    if (z.x + z.z < 0.0) { float _t = -z.z; z.z = -z.x; z.x = _t; }
                    if (z.y + z.z < 0.0) { float _t = -z.z; z.z = -z.y; z.y = _t; }
                    float _s = %1$sscale;
                    vec3 _o = %1$soffset;
                    z = z * _s - _o * (_s - 1.0);
                    dr = dr * abs(_s);
                }
                """).formatted(u).indent(4);
            case ABS_FOLD -> ("""
                {
                    vec3 _o = %1$soffset;
                    z = abs(z + _o) - _o;
                }
                """).formatted(u).indent(4);
            case BOX_FOLD_ONLY -> ("""
                {
                    float _L = %1$sfoldLimit;
                    z = clamp(z, -_L, _L) * 2.0 - z;
                }
                """).formatted(u).indent(4);
            case SPHERE_FOLD -> ("{\n" + SPHERE_FOLD_BODY + "}\n").formatted(u).indent(4);
            // Kali's Amazing Surf: the Mandelbox step with no fold on Z, which is what
            // turns the box's cells into open sheets and shelves.
            case AMAZING_SURF -> ("""
                {
                    float _L = %1$sfoldLimit;
                    z = vec3(clamp(z.xy, -_L, _L) * 2.0 - z.xy, z.z);
                """ + SPHERE_FOLD_BODY + """
                    float _s = %1$sscale;
                    z = z * _s;
                    dr = dr * abs(_s);
                }
                """).formatted(u).indent(4);
            // Mandelbulber's ABoxMod: the box fold limit is a vector, one per axis. With
            // (1,1,1) it is the plain Mandelbox step.
            case ABOX_MOD -> ("""
                {
                    vec3 _o = abs(%1$soffset);
                    z = clamp(z, -_o, _o) * 2.0 - z;
                """ + SPHERE_FOLD_BODY + """
                    float _s = %1$sscale;
                    z = z * _s;
                    dr = dr * abs(_s);
                }
                """).formatted(u).indent(4);
            // The pseudo-Kleinian step: a per-axis box fold, then the interior of the
            // sphere r^2 < size is inverted outwards. A sphere fold with no inner radius.
            case KLEINIAN_FOLD -> ("""
                {
                    vec3 _o = abs(%1$soffset);
                    z = clamp(z, -_o, _o) * 2.0 - z;
                    float _r2 = max(dot(z, z), 1e-8);
                    float _k = max(%1$sradius / _r2, 1.0);
                    z = z * _k;
                    dr = dr * _k;
                }
                """).formatted(u).indent(4);
            // Knighty's octahedral fold: four reflections leave x >= |y| and x >= |z|.
            case OCTA_FOLD -> ("""
                {
                    if (z.x + z.y < 0.0) { float _t = -z.y; z.y = -z.x; z.x = _t; }
                    if (z.x + z.z < 0.0) { float _t = -z.z; z.z = -z.x; z.x = _t; }
                    if (z.x - z.y < 0.0) { float _t = z.y; z.y = z.x; z.x = _t; }
                    if (z.x - z.z < 0.0) { float _t = z.z; z.z = z.x; z.x = _t; }
                """ + IFS_SCALE_BODY + "}\n").formatted(u).indent(4);
            // Knighty's icosahedral fold: abs and reflections in two golden-ratio planes,
            // n1 = normalize(-phi, phi-1, 1) and n2 = normalize(1, -phi, phi+1).
            case ICOSA_FOLD -> ("""
                {
                    const vec3 _n1 = vec3(-0.809017, 0.309017, 0.5);
                    const vec3 _n2 = vec3(0.309017, -0.5, 0.809017);
                    z = abs(z);
                    z = z - 2.0 * min(0.0, dot(z, _n1)) * _n1;
                    z = abs(z);
                    z = z - 2.0 * min(0.0, dot(z, _n2)) * _n2;
                    z = abs(z);
                    z = z - 2.0 * min(0.0, dot(z, _n1)) * _n1;
                    z = abs(z);
                """ + IFS_SCALE_BODY + "}\n").formatted(u).indent(4);
            // One reflection: whatever lies below the plane (n, dist) is mirrored above it.
            // The generic conditional fold — a mirror on one axis is normal (1,0,0), dist 0.
            case PLANE_FOLD -> ("""
                {
                    vec3 _n = %1$snormal;
                    float _d = dot(z, _n) - %1$sdist;
                    if (_d < 0.0) z = z - 2.0 * _d * _n;
                }
                """).formatted(u).indent(4);
            // Fold the angle around one axis into a wedge of 2*pi/N, mirrored at its
            // half: N-fold dihedral symmetry, the kaleidoscope.
            case ROTATIONAL_FOLD -> String.join("\n",
                    "{",
                    "    float _n = float(max(" + u + "count, 1));",
                    "    float _w = 6.2831853 / _n;",
                    "    " + planeRead(st.getAxis()),
                    "    float _l = length(_pq);",
                    "    float _a = atan(_pq.y, _pq.x);",
                    "    _a = abs(mod(_a + 0.5 * _w, _w) - 0.5 * _w);",
                    "    _pq = _l * vec2(cos(_a), sin(_a));",
                    "    " + planeWrite(st.getAxis()),
                    "}", "").indent(4);
            // Benesi's T1: rotate so the body diagonal becomes the Z axis, abs, scale,
            // rotate back, subtract the offset. The rotation is orthogonal (rows checked),
            // so only the scale touches the derivative.
            case BENESI_FOLD -> ("""
                {
                    float _t = z.x * 0.8164966 - z.z * 0.5773503;
                    z = vec3((_t - z.y) * 0.7071068, (_t + z.y) * 0.7071068, z.x * 0.5773503 + z.z * 0.8164966);
                    z = abs(z);
                    float _s = %1$sscale;
                    z = z * _s;
                    dr = dr * abs(_s);
                    _t = (z.y + z.x) * 0.7071068;
                    z = vec3(z.z * 0.5773503 + _t * 0.8164966, (z.y - z.x) * 0.7071068, z.z * 0.8164966 - _t * 0.5773503);
                    z = z - %1$soffset;
                }
                """).formatted(u).indent(4);
            // The Kaliset: abs, invert in a sphere, subtract c. Abs Fold + Sphere Invert +
            // Scale would build it; it is here by name because it is a formula people ask for.
            case KALI_FOLD -> ("""
                {
                    z = abs(z);
                    float _r2 = max(dot(z, z), 1e-8);
                    float _k = (%1$sradius * %1$sradius) / _r2;
                    z = z * _k - %1$soffset;
                    dr = dr * _k;
                }
                """).formatted(u).indent(4);
            case ROTATE -> "    z = " + u + "rot * z;\n";
            // X, then Y, then Z, each by (angle per iteration) * i. Isometry: dr untouched.
            case ROTATE_ITER -> ("""
                {
                    vec3 _a = %1$srotIter * float(i);
                    float _cx = cos(_a.x), _sx = sin(_a.x);
                    float _cy = cos(_a.y), _sy = sin(_a.y);
                    float _cz = cos(_a.z), _sz = sin(_a.z);
                    z = vec3(z.x, _cx * z.y - _sx * z.z, _sx * z.y + _cx * z.z);
                    z = vec3(_cy * z.x + _sy * z.z, z.y, -_sy * z.x + _cy * z.z);
                    z = vec3(_cz * z.x - _sz * z.y, _sz * z.x + _cz * z.y, z.z);
                }
                """).formatted(u).indent(4);
            // Rotate the plane perpendicular to the axis by (twist * height). Not an
            // isometry: the shear grows with the distance from the axis, so the
            // derivative takes the bound 1 + |twist| * radius to stay conservative.
            case TWIST -> String.join("\n",
                    "{",
                    "    float _k = " + u + "twist;",
                    "    float _a = " + axisComponent(st.getAxis()) + " * _k;",
                    "    float _c = cos(_a), _sn = sin(_a);",
                    "    " + planeRead(st.getAxis()),
                    "    float _l = length(_pq);",
                    "    _pq = vec2(_c * _pq.x - _sn * _pq.y, _sn * _pq.x + _c * _pq.y);",
                    "    " + planeWrite(st.getAxis()),
                    "    dr = dr * (1.0 + abs(_k) * _l);",
                    "}", "").indent(4);
            case SCALE -> ("""
                {
                    float _s = %1$sscale;
                    z = z * _s + %1$soffset;
                    dr = dr * abs(_s);
                }
                """).formatted(u).indent(4);
            case SPHERE_INVERT -> ("""
                {
                    float _r2 = max(dot(z, z), 1e-8);
                    float _k = (%1$sradius * %1$sradius) / _r2;
                    z = z * _k;
                    dr = dr * _k;
                }
                """).formatted(u).indent(4);
            // z += c is what turns an IFS into an escape-time set; the matching +1 on
            // the derivative comes from d(pos)/d(pos) in Mandelbrot mode, and is left
            // in place in Julia mode where it only makes the estimate conservative.
            case ADD_C -> "    z = z + c;\n    dr = dr + 1.0;\n";
        };
    }

    private static void collectHybridUniforms(HybridNode hn, Map<String, Object> uniforms) {
        String id = hn.getId();
        uniforms.put(id + "_maxIterations", hn.getMaxIterations());
        uniforms.put(id + "_bailout", hn.getBailout());
        uniforms.put(id + "_juliaC", new float[]{hn.getJuliaCx(), hn.getJuliaCy(), hn.getJuliaCz()});
        List<HybridNode.Step> steps = hn.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            HybridNode.Step st = steps.get(i);
            String u = id + "_s" + i + "_";
            for (String decl : stepUniforms(st.getType())) {
                String name = decl.substring(decl.indexOf('$') + 1);
                uniforms.put(u + name, stepUniformValue(st, name));
            }
        }
    }

    /** The value behind one of a step's uniforms, by the name used in {@link #stepUniforms}. */
    private static Object stepUniformValue(HybridNode.Step st, String name) {
        return switch (name) {
            case "power" -> st.getPower();
            case "scale" -> st.getScale();
            case "foldLimit" -> st.getFoldLimit();
            case "minRadius" -> st.getMinRadius();
            case "fixedRadius" -> st.getFixedRadius();
            case "radius" -> st.getRadius();
            case "dist" -> st.getDist();
            case "count" -> st.getCount();
            case "offset" -> new float[]{st.getOffsetX(), st.getOffsetY(), st.getOffsetZ()};
            case "normal" -> unitOrX(st.getOffsetX(), st.getOffsetY(), st.getOffsetZ());
            case "rot" -> eulerMatrix(st.getRotX(), st.getRotY(), st.getRotZ());
            case "rotIter" -> new float[]{
                    (float) Math.toRadians(st.getRotX()), (float) Math.toRadians(st.getRotY()),
                    (float) Math.toRadians(st.getRotZ())};
            case "twist" -> (float) Math.toRadians(st.getRotX());
            default -> throw new IllegalStateException("no value for hybrid step uniform " + name);
        };
    }

    /** Normalised, or the X axis when the vector is degenerate. */
    private static float[] unitOrX(float x, float y, float z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-6) return new float[]{1f, 0f, 0f};
        return new float[]{(float) (x / len), (float) (y / len), (float) (z / len)};
    }

    /** R = Rz * Ry * Rx, the same convention as the fractal rotation uniforms. */
    private static float[] eulerMatrix(float x, float y, float z) {
        float cx = (float) Math.cos(Math.toRadians(x)), sx = (float) Math.sin(Math.toRadians(x));
        float cy = (float) Math.cos(Math.toRadians(y)), sy = (float) Math.sin(Math.toRadians(y));
        float cz = (float) Math.cos(Math.toRadians(z)), sz = (float) Math.sin(Math.toRadians(z));
        return new float[]{
            cy * cz, -cy * sz, sy,
            sx * sy * cz + cx * sz, -sx * sy * sz + cx * cz, -sx * cy,
            -cx * sy * cz + sx * sz, cx * sy * sz + sx * cz, cx * cy
        };
    }

    /**
     * Generate complete inline GLSL for a primitive SDF node.
     * Emits uniforms, OrbitTrap struct, DE_simple, DE, and getFactors.
     */
    private String generatePrimitiveGLSL(PrimitiveNode pn, String p) {
        StringBuilder sb = new StringBuilder();

        // Uniforms
        sb.append("uniform float ").append(p).append("_sizeX;\n");
        sb.append("uniform float ").append(p).append("_sizeY;\n");
        sb.append("uniform float ").append(p).append("_sizeZ;\n");
        sb.append("uniform float ").append(p).append("_rounding;\n");
        sb.append("uniform float ").append(p).append("_shell;\n\n");

        // OrbitTrap struct
        sb.append("struct ").append(p).append("_OrbitTrap {\n");
        sb.append("    float minDist;\n");
        sb.append("    float planeX;\n");
        sb.append("    float planeY;\n");
        sb.append("    float planeZ;\n");
        sb.append("    int iterations;\n");
        sb.append("};\n\n");

        // DE_simple
        sb.append("float ").append(p).append("_DE_simple(vec3 pos) {\n");
        sb.append(getSdfBody(pn.getPrimitiveType(), p));
        sb.append("    if (").append(p).append("_shell > 0.0) d = abs(d) - ").append(p).append("_shell;\n");
        sb.append("    d -= ").append(p).append("_rounding;\n");
        sb.append("    return d;\n");
        sb.append("}\n\n");

        // DE (full, with orbit trap)
        sb.append("float ").append(p).append("_DE(vec3 pos, out ").append(p).append("_OrbitTrap trap) {\n");
        sb.append("    float d = ").append(p).append("_DE_simple(pos);\n");
        sb.append("    trap.minDist = abs(d);\n");
        sb.append("    trap.planeX = abs(pos.x);\n");
        sb.append("    trap.planeY = abs(pos.y);\n");
        sb.append("    trap.planeZ = abs(pos.z);\n");
        sb.append("    trap.iterations = 1;\n");
        sb.append("    return d;\n");
        sb.append("}\n\n");

        // getFactors
        sb.append("vec3 ").append(p).append("_getFactors(").append(p).append("_OrbitTrap trap) {\n");
        sb.append("    float structural = exp(-trap.minDist * 2.0);\n");
        sb.append("    float flowX = exp(-trap.planeX * 1.5);\n");
        sb.append("    float flowY = exp(-trap.planeY * 1.5);\n");
        sb.append("    float flowZ = exp(-trap.planeZ * 1.5);\n");
        sb.append("    return vec3(structural, (flowX + flowY) * 0.5, flowZ);\n");
        sb.append("}\n");

        return sb.toString();
    }

    private static String getSdfBody(PrimitiveNode.PrimitiveType type, String p) {
        return switch (type) {
            case SPHERE ->
                "    float d = length(pos) - " + p + "_sizeX;\n";
            case BOX ->
                "    vec3 _q = abs(pos) - vec3(" + p + "_sizeX, " + p + "_sizeY, " + p + "_sizeZ);\n" +
                "    float d = length(max(_q, 0.0)) + min(max(_q.x, max(_q.y, _q.z)), 0.0);\n";
            case ROUNDED_BOX ->
                "    vec3 _q = abs(pos) - vec3(" + p + "_sizeX, " + p + "_sizeY, " + p + "_sizeZ);\n" +
                "    float d = length(max(_q, 0.0)) + min(max(_q.x, max(_q.y, _q.z)), 0.0);\n";
            case PLANE ->
                "    float d = pos.y - " + p + "_sizeX;\n";
            case TORUS ->
                "    vec2 _q = vec2(length(pos.xz) - " + p + "_sizeX, pos.y);\n" +
                "    float d = length(_q) - " + p + "_sizeY;\n";
            case CYLINDER ->
                "    vec2 _dh = abs(vec2(length(pos.xz), pos.y)) - vec2(" + p + "_sizeX, " + p + "_sizeY);\n" +
                "    float d = min(max(_dh.x, _dh.y), 0.0) + length(max(_dh, 0.0));\n";
            case CAPSULE ->
                "    float _clampedY = clamp(pos.y, -" + p + "_sizeY, " + p + "_sizeY);\n" +
                "    float d = length(pos - vec3(0.0, _clampedY, 0.0)) - " + p + "_sizeX;\n";
            case CONE -> {
                // Capped cone with height (sizeX), bottom radius (sizeY), top radius (sizeZ)
                // Based on IQ sdCappedCone
                yield "    float _h = " + p + "_sizeX;\n" +
                    "    float _r1 = " + p + "_sizeY;\n" +
                    "    float _r2 = " + p + "_sizeZ;\n" +
                    "    vec2 _q = vec2(length(pos.xz), pos.y);\n" +
                    "    vec2 _k1 = vec2(_r2, _h);\n" +
                    "    vec2 _k2 = vec2(_r2 - _r1, 2.0 * _h);\n" +
                    "    vec2 _ca = vec2(_q.x - min(_q.x, (_q.y < 0.0) ? _r1 : _r2), abs(_q.y) - _h);\n" +
                    "    vec2 _cb = _q - _k1 + _k2 * clamp(dot(_k1 - _q, _k2) / dot(_k2, _k2), 0.0, 1.0);\n" +
                    "    float _s = (_cb.x < 0.0 && _ca.y < 0.0) ? -1.0 : 1.0;\n" +
                    "    float d = _s * sqrt(min(dot(_ca, _ca), dot(_cb, _cb)));\n";
            }
            case OCTAHEDRON -> {
                // IQ exact sdOctahedron (restructured to avoid early return)
                yield "    float _os = " + p + "_sizeX;\n" +
                    "    vec3 _op = abs(pos);\n" +
                    "    float _om = _op.x + _op.y + _op.z - _os;\n" +
                    "    vec3 _oq;\n" +
                    "    float d;\n" +
                    "    if (3.0 * _op.x < _om) _oq = _op.xyz;\n" +
                    "    else if (3.0 * _op.y < _om) _oq = _op.yzx;\n" +
                    "    else if (3.0 * _op.z < _om) _oq = _op.zxy;\n" +
                    "    else { d = _om * 0.57735027;\n" +
                    "           if (" + p + "_shell > 0.0) d = abs(d) - " + p + "_shell;\n" +
                    "           d -= " + p + "_rounding;\n" +
                    "           return d; }\n" +
                    "    float _ok = clamp(0.5 * (_oq.z - _oq.y + _os), 0.0, _os);\n" +
                    "    d = length(vec3(_oq.x, _oq.y - _os + _ok, _oq.z - _ok));\n";
            }
            case PYRAMID -> {
                // IQ sdPyramid (4-sided pyramid, base = 1, height = sizeX)
                yield "    float _h = " + p + "_sizeX;\n" +
                    "    float _m2 = _h * _h + 0.25;\n" +
                    "    vec3 _p = pos;\n" +
                    "    _p.xz = abs(_p.xz);\n" +
                    "    _p.xz = (_p.z > _p.x) ? _p.zx : _p.xz;\n" +
                    "    _p.xz -= 0.5;\n" +
                    "    vec3 _q = vec3(_p.z, _h * _p.y - 0.5 * _p.x, _h * _p.x + 0.5 * _p.y);\n" +
                    "    float _s = max(-_q.x, 0.0);\n" +
                    "    float _t = clamp((_q.y - 0.5 * _p.z) / (_m2 + 0.25), 0.0, 1.0);\n" +
                    "    float _a = _m2 * (_q.x + _s) * (_q.x + _s) + _q.y * _q.y;\n" +
                    "    float _b = _m2 * (_q.x + 0.5 * _t) * (_q.x + 0.5 * _t) + (_q.y - _m2 * _t) * (_q.y - _m2 * _t);\n" +
                    "    float d = min(_a, _b);\n" +
                    "    d = sqrt((d + _q.z * _q.z) / _m2) * sign(max(_q.z, -_p.y));\n";
            }
            case HEX_PRISM -> {
                // IQ sdHexPrism — hex cross-section in XZ, height in Y
                yield "    float _hr = " + p + "_sizeX;\n" +
                    "    float _hh = " + p + "_sizeY;\n" +
                    "    vec3 _hp = abs(pos);\n" +
                    "    float _hk = -0.8660254;\n" +  // -sqrt(3)/2
                    "    vec2 _hxz = vec2(_hp.x, _hp.z);\n" +
                    "    _hxz -= 2.0 * min(dot(vec2(_hk, 0.5), _hxz), 0.0) * vec2(_hk, 0.5);\n" +
                    "    vec2 _hd = vec2(length(_hxz - vec2(clamp(_hxz.x, -_hk * _hr, _hk * _hr), _hr)) * sign(_hxz.y - _hr), _hp.y - _hh);\n" +
                    "    float d = min(max(_hd.x, _hd.y), 0.0) + length(max(_hd, 0.0));\n";
            }
        };
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

    private int leafIndex(GraphNode node) {
        for (int i = 0; i < leaves.size(); i++) {
            if (leaves.get(i).node == node) return i;
        }
        throw new IllegalStateException("Leaf node not found in leaves: " + node.getClass().getSimpleName());
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
