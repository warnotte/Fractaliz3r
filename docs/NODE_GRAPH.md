# Node Graph System

Composable fractal trees: combine multiple fractals with CSG operations and coordinate transforms, compiled into a single GPU shader.

**Shader assembly context:** [SHADER_PIPELINE.md](SHADER_PIPELINE.md) (Mode 3 — Node Graph)

---

## Architecture

The node graph uses a **composite pattern** — a tree of `GraphNode` objects compiled into a single GLSL shader that replaces the standard fractal slot.

```
                    GraphNode (abstract)
                    ├── id: String          (compile-time: n0, t0, c0)
                    ├── name: String        (stable, for animation tracks)
                    └── getChildren(): List<GraphNode>
                         │
          ┌──────────────┼──────────────┼──────────────┼──────────────┐
          │              │              │              │              │
     FractalNode     CSGNode      TransformNode   EffectNode    PrimitiveNode
     (leaf)          (binary)     (unary)          (unary)       (leaf)
     ├── fractalType ├── op       ├── mode (7)     ├── effectType├── type (11)
     ├── fractalParams├── blend   ├── child        ├── child     └── size/shell
     └── (no children)├── left    └── (per-mode)   └── params    └── (no children)
                      └── right
```

### Example Tree

A CSG Union of a Twisted Mandelbulb and a Primitive Sphere with Moss:

```
    CSGNode (UNION, blend=0.1)
    ├── TransformNode (TWIST, axis=Y, strength=0.3)
    │   └── FractalNode (Mandelbulb, power=8)
    └── EffectNode (MOSS, strength=0.5, time=3.0)
        └── PrimitiveNode (SPHERE, size=1.5)
```

This compiles into a single GLSL block with:
- `n0_DE()` / `n0_DE_simple()` — Mandelbulb (prefixed)
- `n1_DE()` / `n1_DE_simple()` — Menger (prefixed)
- `applyTransform_t0()` — Twist function
- `e0_strength/time/scale/erosionType` — Erosion uniforms
- `DE()` — Composite: `smin_graph(n0_d * deCorr_t0(pos), eroded_n1_d, c0_blend)`

---

## Node Types

### FractalNode — Leaf (Resource-based)

Wraps a `FractalType` + per-node `AbstractFractalParams`. Loads distance field logic from external `.glsl` files.

```java
public class FractalNode extends GraphNode {
    private FractalType fractalType;
    private AbstractFractalParams fractalParams;
    // ...
    public List<GraphNode> getChildren() { return Collections.emptyList(); }
}
```

**`createDefaultParams(FractalType)`** — Factory supporting 10 fractal types + CustomShader. Returns `null` for non-composable types (NODE_GRAPH, FRACTAL_TERRAIN, CORNELL_BOX, TEST_SCENE).

**Per-node parameters:** Each FractalNode in the graph stores its own complete fractal parameter set.

---

### PrimitiveNode — Leaf (Inline)

Analytic SDF geometric primitives. Unlike `FractalNode`, these generate their own inline GLSL distance functions in `GraphCompiler`.

```java
public class PrimitiveNode extends GraphNode {
    public enum PrimitiveType { SPHERE, BOX, ROUNDED_BOX, PLANE, TORUS, CYLINDER, CAPSULE, CONE, OCTAHEDRON, PYRAMID, HEX_PRISM }
    private PrimitiveType primitiveType;
    private float sizeX, sizeY, sizeZ;
    private float rounding;  // For rounded corners
    private float shell;     // For hollow shell (abs(d) - shell)
}
```

**GLSL Generation:** `GraphCompiler` emits a full fractal-contract block (`OrbitTrap`, `DE`, `DE_simple`, `getFactors`) for each primitive node using standardized SDF formulas. Supporting coloring factors are computed from the primitive's distance field.

---

### CSGNode — Binary Combiner

Combines two children with a boolean operation.

```java
public class CSGNode extends GraphNode {
    public enum Op { UNION, INTERSECT, SUBTRACT, MORPH }
    private GraphNode left, right;
    private Op op;
    private float blend;  // 0 = hard edge, > 0 = smooth transition
}
```

| Operation | Distance Formula | Coloring Winner |
|-----------|-----------------|-----------------|
| UNION | `smin_graph(d_left, d_right, blend)` | Closest to surface (`d_left <= d_right ? left : right`) |
| INTERSECT | `smax_graph(d_left, d_right, blend)` | Farthest from surface (`d_left >= d_right ? left : right`) |
| SUBTRACT | `smax_graph(d_left, -d_right, blend)` | Left if `d_left >= -d_right`, else right |
| MORPH | `mix(d_left, d_right, clamp(blend, 0, 1))` | Blended factors from both children |

**Smooth blending** via `smin_graph(a, b, k)`:
```glsl
float h = max(k - abs(a - b), 0.0) / k;
return min(a, b) - h * h * k * 0.25;
```
When `k = 0`, reduces to hard `min(a, b)`.

**MORPH special case:** Both fractals' coloring factors are computed and linearly interpolated: `mix(leftFactors, rightFactors, blend)`. This produces smooth visual transitions between any two fractal types.

**`swapChildren()`** — Swap left/right. Important for SUBTRACT where operand order matters.

---

### TransformNode — Coordinate Transform

Wraps a single child with a spatial transformation applied to `pos` before DE evaluation.

```java
public class TransformNode extends GraphNode {
    public enum Mode {
        STANDARD, MIRROR, TWIST, BEND, TAPER, REPETITION, REPETITION_1D
    }
    private GraphNode child;
    private Mode mode;
    private float[] offset;     // Translation (STANDARD) or period (REPETITION)
    private float[] rotation;   // Euler angles in degrees (STANDARD only)
    private float scale;        // Scale (STANDARD) or strength (TWIST/BEND/TAPER)
    private int axis;           // 0=X, 1=Y, 2=Z
    private float frequency;    // For TWIST/BEND/TAPER
}
```

#### Transform Modes

| Mode | Parameters | GLSL Logic | DE Correction |
|------|-----------|------------|---------------|
| **STANDARD** | offset (vec3), rotation (3 Euler), scale | Translate → rotate XYZ → divide by scale | `child_d * scale` |
| **MIRROR** | axis, mirrorOffset | `abs(dot(pos, axis) - offset) + offset` | None (isometric) |
| **TWIST** | axis, strength, frequency, offset | Rotate perpendicular plane by `(axisVal + offset) * strength * frequency` | `1 / max(1, \|sf\| * perpDist)` |
| **BEND** | axis, strength, frequency, offset | Rotate in axis-perp plane by `(axisVal + offset) * strength * frequency` | `1 / max(1, \|sf\| * \|axisVal\|)` |
| **TAPER** | axis, strength, frequency, offset | Scale perp plane by `1 / max(\|1 + (axisVal + offset) * sf\|, 0.01)` | `1 / max(\|taperScale\|, 0.01)` |
| **REPETITION** | period (vec3) | `mod(pos + period/2, period) - period/2` | None (exact) |
| **REPETITION_1D** | axis, period | `mod(pos[axis] + p/2, p) - p/2` | None (exact) |

**DE correction** is required for non-isometric transforms (Twist, Bend, Taper) to prevent raymarching overshoot. For STANDARD mode, the correction is multiplication by the scale factor.

---

### EffectNode — Surface Effects

Wraps a single child with a per-node surface effect (Erosion, Crystal, Moss) applied to its distance field.

```java
public class EffectNode extends GraphNode {
    public enum EffectType { EROSION("Erosion"), CRYSTAL("Crystal"), MOSS("Moss") }
    private GraphNode child;
    private EffectType effectType;
    private float strength;     // 0-1, default 0.5
    private float time;         // 0-20, default 3.0
    private float scale;        // 0.1-5, default 1.0
    private int erosionType;    // EROSION only: 0=All, 1=Hydraulic, 2=Thermal, 3=Cracks
    private float sharpness;    // CRYSTAL only: 0.5-5, default 2.0
}
```

#### Effect Types

| Type | GLSL Function | Attenuation | Description |
|------|--------------|-------------|-------------|
| **EROSION** | `getErosionDisplacementP()` | ×0.05 | Weathering cracks, hydraulic channels, thermal rounding |
| **CRYSTAL** | `getCrystalDisplacementP()` | ×0.1 | Voronoi-based outward crystal growth |
| **MOSS** | `getMossDisplacementP()` | ×0.2 | Organic growth in crevices and on horizontal surfaces |

#### GLSL Generation

`GraphCompiler` emits per-node uniforms with `e{N}_` prefix and calls parameterized `*P()` functions from `common.glsl`:

```glsl
// Uniforms (Phase 3.5)
uniform float e0_strength;
uniform float e0_time;
uniform float e0_scale;
uniform int e0_erosionType;  // EROSION only

// DE emission (Phase 6/7) — with proximity gating
float n0_d = n0_DE(pos, n0_t);  // child DE
{ float _emaxD = erosionMaxDisplacementP(e0_strength, e0_time, e0_scale);
  if (n0_d < _emaxD + 0.1)
    n0_d += getErosionDisplacementP(pos, e0_strength, e0_time, e0_scale, e0_erosionType); }
```

- `DE()` (full) uses full-quality displacement functions
- `DE_simple()` uses lightweight `*LightP()` variants for shadows/AO
- Effects can stack: Erosion wrapping Crystal wrapping a FractalNode

#### Coloring

Effects modify geometry only (distance field). Coloring factors pass through from the child unchanged. Moss coloring (`getMossFactor`) remains global in `raytracer.glsl`.

---

## GraphCompiler — GLSL Code Generation

**File:** `graph/GraphCompiler.java`

Compiles a `GraphNode` tree into a composite GLSL block that satisfies the standard fractal shader contract (`OrbitTrap`, `DE()`, `DE_simple()`, `getFactors()`).

### Compilation Phases

#### Phase 1 — ID Assignment

DFS traversal assigns sequential IDs to every node:

| Node Type | ID Pattern | Example |
|-----------|-----------|---------|
| FractalNode | `n` + counter | `n0`, `n1`, `n2` |
| TransformNode | `t` + counter | `t0`, `t1` |
| CSGNode | `c` + counter | `c0`, `c1` |
| EffectNode | `e` + counter | `e0`, `e1` |

IDs are stored on each node via `node.id` and used as GLSL variable/function prefixes.

#### Phase 2 — Fractal Shader Loading & Preprocessing

For each FractalNode (leaf):
1. Load the fractal shader from resources (`/shaders/fractals/{kernelName}.glsl`)
2. Strip `#version` directive
3. Rename all symbols with `ShaderPreprocessor.renameLocalSymbols(source, prefix + "_")`

After preprocessing, `n0_`'s Mandelbulb defines `n0_DE()`, `n0_OrbitTrap`, `n0_power`, etc. while `n1_`'s Menger defines `n1_DE()`, `n1_OrbitTrap`, `n1_scale`, etc. No conflicts.

CustomShader nodes use the stored `shaderSource` string instead of loading from resources.

#### Phase 3 — CSG Helpers

If any CSG nodes exist, emit:
- `smin_graph(a, b, k)` and `smax_graph(a, b, k)` helper functions
- `uniform float {cid}_blend;` for each CSG node

#### Phase 3.5 — Effect Uniforms

For each EffectNode, emit per-node uniforms:
- Common: `uniform float {eid}_strength; {eid}_time; {eid}_scale;`
- EROSION: `uniform int {eid}_erosionType;`
- CRYSTAL: `uniform float {eid}_sharpness;`

#### Phase 4 — Transform Functions

For each TransformNode, emit:
- `vec3 applyTransform_{tid}(vec3 pos)` — the coordinate transform
- `float deCorr_{tid}(vec3 pos)` — DE correction factor (only for Twist, Bend, Taper)
- Uniforms specific to the transform mode

#### Phase 5 — Composite OrbitTrap

A unified struct that carries coloring factors (not per-fractal orbit traps):

```glsl
struct OrbitTrap {
    float factorX;   // Coloring factor from winning leaf
    float factorY;
    float factorZ;
    float reserved;  // Stores final distance
    int iterations;
};
```

#### Phase 6 — Composite DE()

Recursively emits code for the full tree, producing `float DE(vec3 pos, out OrbitTrap trap)`.

The recursive `emitDEBody()` method returns a `DEResult` record:

```java
record DEResult(String distVar, String winnerExpr, String factorsExpr) { }
```

- `distVar` — GLSL float variable holding the distance
- `winnerExpr` — GLSL int expression identifying which leaf is closest (for coloring)
- `factorsExpr` — (MORPH only) GLSL vec3 expression with pre-blended coloring factors

**Code emission by node type:**

**FractalNode:** Direct DE call
```glsl
n0_OrbitTrap n0_t;
float n0_d = n0_DE(pos, n0_t);
// winnerExpr = "0" (leaf index)
```

**TransformNode:** Transform pos, recurse, apply correction
```glsl
vec3 pos_t0 = applyTransform_t0(pos);
// ... child DE emission ...
float d_t0 = child_d * t0_scale;           // STANDARD: multiply by scale
float d_t0 = child_d * deCorr_t0(pos);     // TWIST/BEND/TAPER: correction factor
float d_t0 = child_d;                       // MIRROR/REPETITION: no correction
```

**EffectNode:** Evaluate child, then apply displacement with proximity gating
```glsl
// child DE emission → n0_d
{ float _emaxD = erosionMaxDisplacementP(e0_strength, e0_time, e0_scale);
  if (n0_d < _emaxD + 0.1)
    n0_d += getErosionDisplacementP(pos, e0_strength, e0_time, e0_scale, e0_erosionType); }
// DE_simple uses *LightP() variants instead
```

**CSGNode (UNION):**
```glsl
// ... left DE emission → leftD ...
// ... right DE emission → rightD ...
float d_c0 = smin_graph(leftD, rightD, c0_blend);
int w_c0 = (leftD <= rightD) ? leftWinner : rightWinner;
```

**CSGNode (MORPH):**
```glsl
float d_c0 = mix(leftD, rightD, clamp(c0_blend, 0.0, 1.0));
vec3 gf_c0 = mix(leftFactors, rightFactors, clamp(c0_blend, 0.0, 1.0));
int w_c0 = (c0_blend < 0.5) ? leftWinner : rightWinner;
```

**Winner selection at the end of DE():**

After the recursive emission, a cascade selects the winning leaf's coloring factors:

```glsl
vec3 _gf;
if (winner == 0) { _gf = n0_getFactors(n0_t); }
else if (winner == 1) { _gf = n1_getFactors(n1_t); }
else { _gf = n2_getFactors(n2_t); }

trap = OrbitTrap(_gf.x, _gf.y, _gf.z, distance, 0);
return distance;
```

For MORPH at root level, the pre-blended `gf_c0` is used directly instead of the winner cascade.

#### Phase 7 — Composite DE_simple()

Same recursive tree emission as Phase 6, but without orbit trap tracking. Uses `n0_DE_simple()` instead of `n0_DE()`. No winner selection, no coloring factors — just returns the combined distance.

#### Phase 8 — Composite getFactors()

Simple passthrough:

```glsl
vec3 getFactors(OrbitTrap trap) {
    return vec3(trap.factorX, trap.factorY, trap.factorZ);
}
```

The coloring factors were already computed and packed into the OrbitTrap by Phase 6.

---

## Uniforms

### Collection

`GraphCompiler.getUniforms(root)` / `collectUniformsStatic(root)` — DFS traversal that collects uniform values from all nodes.

**Per FractalNode:** Emits fractal-specific uniforms with `{nodeId}_` prefix via `emitFractalUniforms()`:
```
n0_power = 8.0
n0_maxIterations = 15
n0_bailout = 256.0
n1_maxIterations = 5
n1_scale = 3.0
n1_offset = [1.0, 1.0, 1.0]
```

**Per TransformNode:** Varies by mode:
```
// STANDARD
t0_offset = [0.0, 1.0, 0.0]
t0_rotX = 0.0   (radians)
t0_rotY = 0.0
t0_rotZ = 0.0
t0_scale = 1.0

// TWIST/BEND/TAPER
t0_axis = 1       (int: 0=X, 1=Y, 2=Z)
t0_strength = 0.3
t0_frequency = 1.0
t0_offset = 0.0   (float, not vec3)

// REPETITION
t0_period = [4.0, 4.0, 4.0]

// REPETITION_1D
t0_axis = 0
t0_period = 4.0

// MIRROR
t0_mirrorAxis = [0.0, 1.0, 0.0]
t0_mirrorOffset = 0.0
```

**Per CSGNode:**
```
c0_blend = 0.1
```

**Per EffectNode:** Varies by effect type:
```
// Common (all types)
e0_strength = 0.5
e0_time = 3.0
e0_scale = 1.0

// EROSION only
e0_erosionType = 0    (int: 0=All, 1=Hydraulic, 2=Thermal, 3=Cracks)

// CRYSTAL only
e0_sharpness = 2.0
```

### Recompile vs Update

| Change | Action |
|--------|--------|
| Add/remove/reorder nodes | `NodeGraphParams.setDirty(true)` → full recompile |
| Change fractal type on a node | Recompile (different shader source) |
| Change transform mode | Recompile (different GLSL function) |
| Change effect type on EffectNode | Recompile (different uniforms/functions) |
| Adjust slider values | `NodeGraphParams.updateUniforms()` → `collectUniformsStatic()` — uniforms only |
| Change CSG blend | Uniform update only |

`NodeGraphParams.isDirty()` is checked by `GLSLFractalizerController.activateCurrentProgram()` before each render.

---

## Animation Integration

**File:** `graph/NodeGraphAnimationHelper.java`

### Parameter Discovery

`discoverAnimatableParameters(GraphNode root)` performs a DFS traversal and returns a list of `NodeAnimInfo` records:

```java
record NodeAnimInfo(
    GraphNode node,
    String nodeName,              // Stable name for track prefix
    Color groupColor,             // Visual grouping in timeline
    List<AnimatableParameter> parameters
) { }
```

**FractalNode:** Parameters discovered via `@Animatable` reflection on the fractal params class. Color: blue (`#2196F3`).

**TransformNode:** Mode-specific parameters:
- STANDARD: `offsetX`, `offsetY`, `offsetZ`, `rotationX`, `rotationY`, `rotationZ`, `scale`
- MIRROR: `mirrorOffset`
- TWIST/BEND/TAPER: `strength`, `frequency`, `offset`
- REPETITION: `periodX`, `periodY`, `periodZ`
- REPETITION_1D: `period`

Color varies by mode (e.g., orange for Standard, purple for Twist).

**CSGNode:** Single `blend` parameter. Color: orange (`#FF9800`).

**EffectNode:** Parameters: `strength`, `time`, `scale` (+ `sharpness` for CRYSTAL). `erosionType` is structural (not animated). Color: red (`#F44336`).

### Track Naming

Tracks are named `{nodeName}.{paramName}`:
- `"Mandelbulb".power`
- `"Transform".scale`
- `"CSG Union".blend`
- `"Mandelbulb_2".maxIterations`

### Stable Naming (GraphNodeNamer)

**File:** `graph/GraphNodeNamer.java`

Ensures all nodes have unique, persistent names:

- **Base name:** Derived from type (`FractalType.displayName`, `"CSG"`, transform mode name)
- **Uniqueness:** Suffixed with `_2`, `_3`, etc. if duplicate
- **`ensureAllNamed(root)`:** DFS to assign names to unnamed nodes
- **`renameNode(root, target, newName)`:** User rename with uniqueness check

Names survive serialization/deserialization and are used to bind animation tracks across recompilations. When a node is renamed, tracks are re-discovered.

### Structural Change Callback

`NodeGraphEditor.setOnGraphStructureChanged(Runnable)` — fires when nodes are added, removed, or types changed. `AnimationManager` uses this to re-discover parameters and rebuild timeline tracks.

---

## Serialization

**File:** `config/FractalConfig.java`

### Format

The graph tree is serialized as a recursive JSON structure within the `.frac` save file:

```json
{
  "fractalType": "NODE_GRAPH",
  "params": {
    "graph": {
      "type": "csg",
      "name": "CSG Union",
      "op": "UNION",
      "blend": 0.1,
      "left": {
        "type": "transform",
        "name": "Twist",
        "mode": "TWIST",
        "axis": 1,
        "offset": [0.0, 0.0, 0.0],
        "rotation": [0.0, 0.0, 0.0],
        "scale": 0.3,
        "frequency": 1.0,
        "child": {
          "type": "fractal",
          "name": "Mandelbulb",
          "fractalType": "MANDELBULB",
          "params": {
            "power": 8.0,
            "maxIterations": 15,
            "bailout": 256.0
          }
        }
      },
      "right": {
        "type": "effect",
        "name": "Erosion",
        "effectType": "EROSION",
        "strength": 0.5,
        "time": 3.0,
        "scale": 1.0,
        "erosionType": 0,
        "sharpness": 2.0,
        "child": {
          "type": "fractal",
          "name": "Menger Sponge",
          "fractalType": "MENGER_SPONGE",
          "params": {
            "maxIterations": 5,
            "scale": 3.0,
            "offsetX": 1.0,
            "offsetY": 1.0,
            "offsetZ": 1.0
          }
        }
      }
    }
  }
}
```

### serializeGraphNode(GraphNode)

Recursive serialization:

| Node Type | Fields Stored |
|-----------|--------------|
| `"fractal"` | `fractalType` (enum name), `params` (fractal-specific map) |
| `"csg"` | `op` (enum name), `blend`, `left` (recursive), `right` (recursive) |
| `"transform"` | `mode` (enum name), `axis`, `offset` (3-array), `rotation` (3-array), `scale`, `frequency`, `child` (recursive) |
| `"effect"` | `effectType` (enum name), `strength`, `time`, `scale`, `erosionType`, `sharpness`, `child` (recursive) |

All nodes store `name` (stable identifier) and `type` (discriminator).

### deserializeGraphNode(Map)

Recursive deserialization with fallback safety:
- Unknown `fractalType` → defaults to `MANDELBULB`
- Missing `left`/`right`/`child` → creates default `FractalNode(MANDELBULB)` or `FractalNode(MENGER_SPONGE)`
- Gson `List<Double>` → `float[]` conversion handled automatically
- Enum values parsed via `.valueOf()` with fallback defaults

---

## UI — NodeGraphEditor

**File:** `ui/components/NodeGraphEditor.java` (~1525 lines)

### Layout

```
┌─── Toolbar ────────────────────────────────────────────────────────────────┐
│ [+Fractal] [Wrap CSG] [Wrap Transform ▼] [Wrap Effect ▼] [+] [Delete] [Undo] [Redo] │
├─── Canvas (visual tree) ──────────────┬─── Detail Panel (sliders) ────────┤
│                                        │                                   │
│    ┌──────────┐                        │ Name: [Mandelbulb________]       │
│    │    CSG   │                        │                                   │
│    │  UNION   │                        │ power:          [====] 8.0       │
│    └────┬─────┘                        │ maxIterations:  [====] 15        │
│    ┌────┴────┐                         │ bailout:        [====] 256       │
│ ┌──┴───┐ ┌──┴────┐                    │                                   │
│ │Twist │ │Menger │                    │                                   │
│ │ t0   │ │  n1   │                    │                                   │
│ └──┬───┘ └───────┘                    │                                   │
│ ┌──┴──────┐                           │                                   │
│ │Mandel-  │                           │                                   │
│ │bulb n0  │                           │                                   │
│ └─────────┘                           │                                   │
│                                        │                                   │
├─── Status: "Compiled successfully" ────┴───────────────────────────────────┘
```

### Interactions

| Action | Effect |
|--------|--------|
| Click node | Select → detail panel shows sliders |
| Right-click node | Context menu (rename, wrap, duplicate, delete, change type, swap children) |
| Scroll on canvas | Zoom (0.3x → 3.0x) |
| Double-click empty | Fit view |
| Ctrl+Z / Ctrl+Y | Undo / Redo |

### Detail Panel — Auto-Discovery

`buildFractalDetail(FractalNode)` uses `@Animatable` reflection to discover parameters and creates `EnhancedSlider` controls with per-type configurations:

1. Check `FRACTAL_SLIDER_CONFIGS` for per-type/per-field overrides (min, max, step)
2. Fall back to `DEFAULT_SLIDER_CONFIG` for common fields
3. Last resort: `[1, 50]` for int, `[0, 10]` for float

Enum fields (e.g., `PolyhedralIFS.polyType`) get ComboBox controls instead of sliders.

### Undo/Redo

30-snapshot history using `FractalConfig.serializeGraphNode()`/`deserializeGraphNode()`:

1. Before each structural change: `pushUndoSnapshot()` serializes the current graph tree
2. Forward history trimmed on new action
3. Undo: `graphHistoryIndex--`, restore from `graphHistory.get(index)`
4. Redo: `graphHistoryIndex++`, restore from `graphHistory.get(index)`
5. Slider changes (non-structural) do NOT push snapshots

### Canvas Rendering

- Nodes drawn as colored rounded rectangles (FractalNode=blue, CSG=orange, Transform=mode-specific color, Effect=red)
- Connections drawn with Bezier curves from parent to child
- Depth-first layout: children below parents (`V_GAP=50`), siblings side-by-side (`H_GAP=20`)
- Selected node highlighted in cyan
- Zoom level displayed as percentage in corner

---

## Adding a New Transform Mode

To add a new transform mode to TransformNode:

### 1. Add enum value

In `TransformNode.Mode`:
```java
MY_TRANSFORM("My Transform"),
```

### 2. Emit GLSL in GraphCompiler

In `generateTransformFunctions()`, add a case in the switch. Create a new method `emitMyTransform(StringBuilder sb, String id)` that emits:
- Uniform declarations: `uniform float {id}_myParam;`
- Transform function: `vec3 applyTransform_{id}(vec3 pos) { ... }`
- (Optional) DE correction: `float deCorr_{id}(vec3 pos) { ... }`

### 3. Collect uniforms in GraphCompiler

In `collectUniformsFromNode()`, add a case under the TransformNode switch:
```java
case MY_TRANSFORM -> {
    uniforms.put(id + "_myParam", tn.getMyParam());
}
```

### 4. Handle in emitTransformDE

In `emitTransformDE()`, add a case to the switch for DE correction:
```java
case MY_TRANSFORM -> {
    // If non-isometric:
    sb.append("    float ").append(scaledVar).append(" = ").append(child.distVar)
      .append(" * deCorr_").append(tid).append("(").append(posVar).append(");\n");
}
```

### 5. UI in NodeGraphEditor

In `buildTransformDetail(TransformNode)`, add sliders for the new mode's parameters.

### 6. Animation in NodeGraphAnimationHelper

In `discoverTransformParams(TransformNode)`, add parameter discovery for the new mode.

### 7. Serialization

`serializeGraphNode()` and `deserializeGraphNode()` in `FractalConfig.java` handle TransformNode generically — the `mode` enum name and common fields (offset, rotation, scale, frequency, axis) are serialized. If your new mode uses custom fields, add them to the serialization.

---

## Source Files

| File | Role |
|------|------|
| `graph/GraphNode.java` | Abstract base: `id`, `name`, `getChildren()` |
| `graph/FractalNode.java` | Leaf: `FractalType` + per-node `AbstractFractalParams` |
| `graph/CSGNode.java` | Binary: 4 operations + blend |
| `graph/TransformNode.java` | Unary: 7 transform modes |
| `graph/EffectNode.java` | Unary: 3 surface effect types (Erosion/Crystal/Moss) |
| `graph/GraphCompiler.java` | Tree → composite GLSL (8 phases + Phase 3.5 effects) |
| `graph/GraphNodeNamer.java` | Stable unique naming for animation tracks |
| `graph/NodeGraphAnimationHelper.java` | DFS parameter discovery for timeline integration |
| `fractals/NodeGraphParams.java` | `AbstractFractalParams` wrapper for graph tree |
| `ui/components/NodeGraphEditor.java` | Visual tree editor + detail panel |
| `config/FractalConfig.java` | `serializeGraphNode()` / `deserializeGraphNode()` |
