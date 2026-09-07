# Node Graph System

Composable fractal trees: combine multiple fractals with CSG operations and coordinate transforms, compiled into a single GPU shader.

**Shader assembly context:** [SHADER_PIPELINE.md](SHADER_PIPELINE.md) (Mode 3 — Node Graph)

---

## Architecture

The node graph uses a **composite pattern** — a tree of `GraphNode` objects compiled into a single GLSL shader that replaces the standard fractal slot.

```
                    GraphNode (abstract)
                    ├── id: String          (compile-time: n0, t0, c0, m0)
                    ├── name: String        (stable, for animation tracks)
                    └── getChildren(): List<GraphNode>
                         │
          ┌──────────┼──────────┼──────────┼──────────┼──────────┐
          │          │          │          │          │          │
     FractalNode  CSGNode  TransformNode EffectNode MaterialNode PrimitiveNode
     (leaf)       (binary) (unary)       (unary)    (unary)      (leaf)
     ├── type     ├── op   ├── mode (7)  ├── effect ├── matType  ├── type (11)
     ├── params   ├── blend├── child     ├── child  ├── colorMode└── size/shell
     └── (leaf)   ├── left └── (per-mode)└── params ├── color    └── (leaf)
                  └── right                         ├── ior/emit
                                                    └── child
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
- `e0_strength/time/scale` — Erosion uniforms (the erosion type is baked into the GLSL as a literal)
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

### MaterialNode — Per-Node Material Overrides

Wraps a single child with per-node material properties. Uses SSBO-based material lookup: each MaterialNode gets a unique index at compile time. Material data is stored in a Shader Storage Buffer Object (binding 6), indexed by `matId` in the OrbitTrap.

```java
public class MaterialNode extends GraphNode {
    public static final int TYPE_GLOBAL = -1;   // Use global material
    public static final int TYPE_LAMBERTIAN = 0;
    public static final int TYPE_METALLIC = 1;
    public static final int TYPE_GLASS = 2;

    public static final int COLOR_PALETTE = 0;  // Keep fractal palette colors (default)
    public static final int COLOR_SOLID = 1;    // Replace with solid albedo color
    public static final int COLOR_TINT = 2;     // Multiply palette by tint color

    private GraphNode child;
    private int materialType;   // -1 = global, 0-2 = override
    private int colorMode;      // 0 = palette, 1 = solid, 2 = tint
    private float colorR, colorG, colorB;  // Albedo color (used by solid/tint modes)
    private float roughness;    // -1 = global, 0-1 = override
    private float metallic;     // -1 = global, 0-1 = override
    private float ior;          // -1 = global, 1-3 = override
    private float emission;     // -1 = global, 0-50 = override
}
```

#### How It Works

Each MaterialNode is assigned a unique `int matId` (SSBO index) during compilation. The generated `DE()` function tracks a single `int _matId = -1` local variable. When a MaterialNode is encountered, it sets `_matId` to its index. The OrbitTrap carries this single ID:

```glsl
struct OrbitTrap {
    float factorX, factorY, factorZ, reserved;
    int iterations;
    int matId;  // -1 = use global, >= 0 = SSBO index
};
```

Material properties are stored in an SSBO (binding 6):

```glsl
struct MaterialData {
    float type;       // -1=global, 0=Lambertian, 1=Metallic, 2=Glass
    float colorMode;  // 0=palette, 1=solid, 2=tint
    float albedoR, albedoG, albedoB;
    float roughness, metallic, ior, emission;
    float _pad0, _pad1, _pad2;  // std430 alignment (12 floats = 48 bytes)
};
layout(std430, binding = 6) readonly buffer MaterialBuffer {
    MaterialData materials[];
};
```

**CSG material propagation:**
- Before evaluating the right subtree, left-side `_matId` is saved, then reset to `-1`.
- After both subtrees are evaluated:
  - **Union/Intersect/Subtract:** Winner-takes-all — `_matId` picks from the winning side.
  - **Morph:** `_matId` snaps at blend = 0.5.

**Shader injection:** `raytracer.glsl` uses `#ifdef HAS_MATERIALS` blocks in 5 shading functions (shade, shadeSimple, pathTrace, pathTraceClassic, renderByMode) to look up the SSBO:

```glsl
#ifdef HAS_MATERIALS
    if (trap.matId >= 0) {
        MaterialData mat = materials[trap.matId];
        int mColorMode = int(mat.colorMode);
        if (mColorMode == 1) baseColor = vec3(mat.albedoR, mat.albedoG, mat.albedoB);
        else if (mColorMode == 2) baseColor *= vec3(mat.albedoR, mat.albedoG, mat.albedoB);
        if (int(mat.type) >= 0) localMatType = int(mat.type);
        if (mat.roughness >= 0.0) safeRoughness = max(mat.roughness, 0.02);
        // ... etc
    }
#endif
```

**Zero overhead when unused:** `#define HAS_MATERIALS` is only emitted when at least one MaterialNode exists. Without it, OrbitTrap stays slim (5 fields) and no SSBO is created.

#### Color Modes

| Mode | Value | Behavior |
|------|-------|----------|
| `COLOR_PALETTE` | 0 | Keep fractal palette colors (default for new nodes) |
| `COLOR_SOLID` | 1 | Replace color with solid albedo from `colorR/G/B` |
| `COLOR_TINT` | 2 | Multiply palette color by `colorR/G/B` tint |

#### UI

- **Color:** Purple (`#9C27B0`) in tree canvas
- **Detail panel:** Material Type ComboBox, Color Mode ComboBox (Fractal Colors/Solid Color/Color Tint), ColorPicker, CheckBox+Slider pairs for roughness/metallic/ior/emission
- **Toolbar:** "Wrap Material" button
- **Context menu:** "Wrap in Material" option

#### Animation

7 animatable parameters: `colorR`, `colorG`, `colorB`, `roughness`, `metallic`, `ior`, `emission`. Color: purple in timeline.

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

// DE emission (Phase 6/7) — with proximity gating
float n0_d = n0_DE(pos, n0_t);  // child DE
{ float _emaxD = erosionMaxDisplacementP(e0_strength, e0_time, e0_scale);
  if (n0_d < _emaxD + 0.1)
    n0_d += getErosionDisplacementP(pos, e0_strength, e0_time, e0_scale, 2); }   // 2 = the node's erosionType, a literal
```

- `DE()` (full) uses full-quality displacement functions
- `DE_simple()` calls the **same** `*P()` functions. It used to call the `*LightP()` variants of common.glsl, a cheaper noise (fewer octaves, other offsets, no hydraulic warp) that describes a different surface, so shadows, AO and normals were computed on a surface the eye ray never hit: a golf-ball sheen on smooth surfaces next to eroded ones, dark fringes wherever a shadow ray started inside the other surface. `DE_simple` is the same geometry without orbit traps, nothing less. The light variants still exist for the legacy global effects of the built-in fractal shaders, where the mismatch remains: making them full there pushed every built-in shader (7-10 s to compile each, already) past the NVIDIA compiler's limit, a fatal `C9999` at startup.
- **The erosion type is a literal, not a uniform.** With `pType` known at compile time the compiler drops the two noise families a node does not use, so a graph with twenty erosion nodes compiles in the same ~13 s as one with thirteen did before. Changing the type in the editor is a structural change and recompiles (it already went through `onStructuralChange`).
- Effects can stack: Erosion wrapping Crystal wrapping a FractalNode

#### Coloring

Effects modify geometry only (distance field). Coloring factors pass through from the child unchanged. Moss coloring (`getMossFactor`) remains global in `raytracer.glsl`.

---

### HybridNode (leaf) — composing maps, not distances

**This is the one operation CSG cannot express, and the distinction is the whole point.**

A `CSGNode` combines two distance fields that were each produced by a complete,
independent DE evaluation. Every one of its operations is a pointwise function of the
two finished distances:

| Op | GLSL emitted |
|----|--------------|
| Union | `smin_graph(d1, d2, k)` |
| Intersect | `smax_graph(d1, d2, k)` |
| Subtract | `smax_graph(-d1, d2, k)` |
| Morph | `mix(d1, d2, blend)` |

A hybrid instead composes the **maps**. The orbit of a point is taken under `g(f(z))`
rather than under `f` and `g` separately, so the escape set it produces is generally not
any pointwise function of the two original shapes: `g∘f` has its own fixed points, its
own symmetry group and its own self-similarity ratio, none of which need resemble either
input. Concretely, a box fold nested inside a spherical power map **at every scale** is
unreachable by union or morph, because in a union each shape keeps its own
self-similarity all the way down. Cross-fading two photographs can only ever show what
is present in one of them; running an image through two filters in alternation,
repeatedly, cannot.

**Steps.** Twenty-eight maps in four families, applied in order, once per iteration, the
sequence repeating. The step is the unit of the library: Mandelbulb3D's catalogue of
formulas is, for the most part, these maps in different orders with different constants.

| Family | Step | What it does | Parameters it reads |
|--------|------|--------------|---------------------|
| Power | `BULB` | spherical power map, sine convention (the Mandelbulb) | power |
| Power | `BULB_COSINE` | Nylander's cosine convention — same power, another bulb | power |
| Power | `QUAT_SQUARE` | `z²` on the `w = 0` quaternion slice (also the Tetrabrot's 3D section) | — |
| Power | `BRISTOR` | Bristorbrot square `(x²-y²-z², 2xy, -2xz)` | — |
| Power | `BENESI_MAG` | Benesi's quadratic mag transform, the square behind the Pine Tree | — |
| Power | `RIEMANN` | msltoe's Riemann sphere: stereographic projection, `|sin|` tiling, back, radial power | power, scale (plane frequency) |
| Power | `COMPLEX_POWER` | `z^p` in the plane perpendicular to one axis, which passes through | axis, power |
| Fold | `BOX_FOLD` | the whole Mandelbox step: box fold, sphere fold, scale | foldLimit, minRadius, fixedRadius, scale |
| Fold | `BOX_FOLD_ONLY` | `clamp(z)·2 - z` alone | foldLimit |
| Fold | `SPHERE_FOLD` | the Mandelbox sphere fold alone | minRadius, fixedRadius |
| Fold | `AMAZING_SURF` | Kali's Amazing Surf: Mandelbox step with no fold on Z | as `BOX_FOLD` |
| Fold | `ABOX_MOD` | Mandelbox step with one fold limit per axis | offset (limits), minRadius, fixedRadius, scale |
| Fold | `KLEINIAN_FOLD` | per-axis box fold, then the interior inversion `k = max(size/r², 1)` | offset (limits), radius (size) |
| Fold | `MENGER_FOLD` | abs, sort the axes, scale and offset | scale, offset |
| Fold | `SIERPINSKI_FOLD` | tetrahedral fold, three planes | scale, offset |
| Fold | `OCTA_FOLD` | octahedral fold, four planes (Knighty) | scale, offset |
| Fold | `ICOSA_FOLD` | icosahedral fold, golden-ratio planes (Knighty) | scale, offset |
| Fold | `ABS_FOLD` | `abs(z + o) - o` | offset |
| Fold | `PLANE_FOLD` | reflect what lies below one plane to above it — the generic conditional fold | offset (normal), dist |
| Fold | `ROTATIONAL_FOLD` | N-fold kaleidoscope: the angle around one axis folded into a wedge | axis, count |
| Fold | `BENESI_FOLD` | Benesi T1: abs in the frame whose Z is the body diagonal, scale, offset | scale, offset |
| Fold | `KALI_FOLD` | the Kaliset step `abs(z)/r² - c` | radius, offset (c) |
| Fold | `SPHERE_INVERT` | inversion in a sphere | radius |
| Transform | `ROTATE` | rigid rotation | rotX/Y/Z |
| Transform | `ROTATE_ITER` | rotation by `angle × iteration index` | rotX/Y/Z per iteration |
| Transform | `TWIST` | rotate the plane around an axis by `twist × height` | axis, rotX (degrees per unit) |
| Transform | `SCALE` | `z·s + offset` | scale, offset |
| Seed | `ADD_C` | `z += c` — the term that turns an IFS into an escape-time set | — |

**Iteration gating.** Every step carries `iterStart`, `iterEnd` (exclusive) and
`iterEvery`, defaulting to "every iteration". A gated step is wrapped in
`if (i >= start && i < end && ((i - start) % every) == 0)`, baked into the GLSL rather
than passed as a uniform: the gate decides which code runs on which pass, so the editor
treats it as a structural edit. This is how the classic "formula A for three iterations,
then formula B" and "A and B alternating" hybrids of Mandelbulber are written here — see
the *Box / bulb, alternating* and *Bulb, boxed early* chains. An ungated chain emits
exactly the code it did before gating existed, which is what keeps the controls exact.

**Derivatives.** Each step carries its own `dr` update, which is what keeps the result a
usable distance estimator: folds and rotations are piecewise isometries and leave `dr`
alone, scales multiply it, the power maps multiply by `p·r^(p-1)` (`2r` for the squares),
an inversion by `k/r²`, and `ADD_C` adds 1 (from `d(pos)/d(pos)` in Mandelbrot mode; kept
in Julia mode, where it only makes the estimate conservative). `TWIST` is the one
non-isometry among the transforms: it multiplies `dr` by `1 + |twist|·radius`, a bound on
its shear, so the estimate stays conservative. `RIEMANN`'s plane fold is conformal but
not isometric; only its radial power reaches `dr`, so its estimate is approximate.

**Three estimators.** `LOG` = `0.5·log(r)·r/dr` for power maps, using the radius at which
the orbit escaped, captured at the top of the loop. `LINEAR` = `r/|dr|` for folds and
similarities, using the radius of the **final** orbit point — when the loop ends by
exhausting its iterations rather than by escaping, those are different values, and using
the loop-top radius makes the estimator disagree with the formula it should reproduce.
`PLANE` = `|z.z + 0.1| / (3·|dr|)`, Knighty's pseudo-Kleinian estimator: orbits under that
fold never escape, so an escape radius means nothing to them, and the distance of the
final point to a plane is what carries the shape (the 3 is the stand-alone shader's
`0.5 / 1.5`, its safety margin on the stretch, folded into one constant).

**Validation.** `test/HybridLab` renders a set of chains, and its first entries are
controls: `BULB → ADD_C` must reproduce the plain Mandelbulb, `BOX_FOLD → ADD_C` the
plain Mandelbox, `BRISTOR → ADD_C` the plain Bristorbrot, and `ABOX_MOD → ADD_C` with the
limit `(1,1,1)` the Mandelbox again, since it is the same step written with a vector
limit. Compared on the depth AOV rather than on colour — a chain has no formula-specific
orbit traps, so the Mandelbox palette legitimately differs while the geometry must not —
all four are exact to the sub-pixel: mean depth difference 0.00000 on each.

Emission follows `PrimitiveNode`: inline GLSL, no `.glsl` file, prefix `h0_`, `h1_`, …
and the same leaf contract (`_OrbitTrap`, `_DE`, `_DE_simple`, `_getFactors`), so a
hybrid plugs into CSG, transforms, effects and materials like any other leaf.

**Chain library.** `HybridPresets` ships thirty-one named chains, offered as a "Load a
chain…" dropdown in the hybrid detail panel, and every step type appears in at least one of
them (a JUnit test insists on it: a step nobody can reach from the library is a step nobody
has rendered). The first three entries are the HybridLab controls, reproducing the
stand-alone Mandelbulb, Mandelbox and Bristorbrot exactly, and are the sane place to start
a chain of your own: begin from a shape you recognise, then add one step. Then the IDEAS.md
#14 wish-list entries that turned out to need no shader (BoxBulb, Buffalo, MarbleMarcher,
JuliaMorph, the Tetrabrot's 3D section), the Mandelbulb3D / Mandelbulber catalogue as
chains (Amazing Surf, Benesi Pine Tree, Kaliset, Pseudo-Kleinian, Riemann Sphere, Octa and
Icosa KIFS, the cosine bulb, Quaternion Julia), the maps that only exist inside a loop
(kaleidoscope, per-iteration rotation, twist, plane and sphere folds inside a power map),
and two iteration-gated chains.

A preset carries its `previewDist`, because these live in worlds of very different sizes —
a Mandelbox is several times a Mandelbulb — and, when it needs one, its Julia constant;
loading a preset sets or clears the node's constant accordingly. `HybridLab` renders the
library from the same source the editor offers (`-Dexec.args="out/hybrid_lib 480x270 12
presets"`), so the dropdown cannot drift away from what actually renders.

**Editing.** NodeGraphEditor has a `+ Hybrid` toolbar button and a step-list editor in the
detail panel: iterations, bailout, estimator family, Julia constant, and per step a type
combo with move-up / move-down / delete, a one-line hint on what the step does, the sliders
it actually reads, an axis choice where it works around one, and an iteration row (from /
to / every) for gating. Steps are added from a `+ Step` menu grouped by family. Adding,
removing, reordering or retyping a step, gating it or changing its axis changes which code
is emitted, so those recompile; moving a slider only updates a value and does not.

**Prospecting.** `explore/ChainProspector` searches the space the editor opens; the
browser's Discoveries tab runs it on the app's controller and `test/HybridProspector` from
the command line. What is baked into a chain's GLSL (step types and order, gating,
estimator, axes) is a *structure*; what is a uniform (every numeric parameter, the Julia
seed) is a *draw*. The prospector draws structures from four recipes that follow the grammar
above (escape-time map + seed under the log estimator, IFS folds under the linear one,
Mandelbox-like fold + seed, power map and fold in one loop; every recipe at least two shape
steps), compiles each once (~12 s), sweeps parameter draws through it at a few milliseconds
a frame via `NodeGraphParams.updateUniforms()`, auto-frames each on the depth AOV (closer
while empty, farther while filled or cut by the frame), scores it as FractalNavigator scores
a framing times a structure factor (`FrameScorer.structure`: the share of surface whose
neighbours are surface at a continuous depth, because detail alone rates a ball of dust as
high as a carved solid), and marks as known a chain whose shape steps are a library chain's
or number one (a turned Mandelbox is still a Mandelbox). It talks to a `ChainRenderer` (set a
chain, say its uniforms changed, render depth and colour); `ControllerChainRenderer` is the
GPU one. `ChainProspectorTest` keeps it honest without a GPU: every drawn structure compiles,
draws stay inside the clamps, a drawn chain survives its save, and the search itself run
against an analytic sphere frames, scores, reports and stops when told.

**Colouring is weaker than a stand-alone formula, by nature.** A formula ships orbit traps
tuned to its own orbit — the Mandelbox tracks fold amount and sphere-fold hits, the
Mandelbulb plane traps calibrated to a bailout of 2. A chain has no such thing, and generic
traps do not substitute well: taken as a minimum over a long orbit they saturate towards
zero for every point, taken as a mean they average the contrast away, and the final orbit
direction fared no better. Measured against a stand-alone Mandelbulb, the hybrid factor
field has a comparable spread but a higher mean (flow 0.60 vs 0.48), so it walks a narrower
part of the gradient. What helped, in order: softening `structural` (it drives the
white highlight mix in `applyMaterial`, and a high value washes the palette out regardless
of the hue chosen), then raising `colorStrength` and `paletteOffset` per preset. A
multi-hue gradient does *not* help — sub-pixel averaging turns it to mud.

## GraphCompiler — GLSL Code Generation

**File:** `graph/GraphCompiler.java`

Compiles a `GraphNode` tree into a composite GLSL block that satisfies the standard fractal shader contract (`OrbitTrap`, `DE()`, `DE_simple()`, `getFactors()`).

### Compilation Phases

#### Phase 1 — ID Assignment

DFS traversal assigns sequential IDs to every node:

| Node Type | ID Pattern | Example |
|-----------|-----------|---------|
| FractalNode | `n` + counter | `n0`, `n1`, `n2` |
| PrimitiveNode | `p` + counter | `p0`, `p1` |
| TransformNode | `t` + counter | `t0`, `t1` |
| CSGNode | `c` + counter | `c0`, `c1` |
| EffectNode | `e` + counter | `e0`, `e1` |
| MaterialNode | `m` + counter | `m0`, `m1` |

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
- EROSION: no extra uniform; the type is emitted as an integer literal in the displacement call
- CRYSTAL: `uniform float {eid}_sharpness;`

#### Phase 0 (top of generated code) — Material SSBO Declaration

When any MaterialNode exists, emits `#define HAS_MATERIALS` and the SSBO struct/buffer declaration at the very top. Material data flows through the SSBO (binding 6), not per-node uniforms.

#### Phase 4 — Transform Functions

For each TransformNode, emit:
- `vec3 applyTransform_{tid}(vec3 pos)` — the coordinate transform
- `float deCorr_{tid}(vec3 pos)` — DE correction factor (only for Twist, Bend, Taper)
- Uniforms specific to the transform mode

#### Phase 5 — Composite OrbitTrap

A unified struct that carries coloring factors (not per-fractal orbit traps). When MaterialNodes exist, also carries a material ID for SSBO lookup:

```glsl
struct OrbitTrap {
    float factorX;   // Coloring factor from winning leaf
    float factorY;
    float factorZ;
    float reserved;  // Stores final distance
    int iterations;
    // --- Only when #define HAS_MATERIALS ---
    int matId;             // -1 = use global, >= 0 = SSBO index
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
    n0_d += getErosionDisplacementP(pos, e0_strength, e0_time, e0_scale, 0); }   // erosionType baked as a literal
// DE_simple emits exactly the same call: same displacement, so normals, shadows and AO see the surface the eye ray hit
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
(erosionType is not a uniform: 0=All, 1=Hydraulic, 2=Thermal, 3=Cracks is baked into the GLSL)

// CRYSTAL only
e0_sharpness = 2.0
```

**MaterialNode:** No per-node uniforms. Material data flows through an SSBO (binding 6). Each MaterialNode gets a unique index assigned during compilation. The SSBO buffer is updated via `GLSLEngine.updateMaterialSSBO()` with 12 floats per material (type, colorMode, albedoRGB, roughness, metallic, ior, emission, 3x padding).

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

**EffectNode:** Parameters: `strength`, `time`, `scale` (+ `sharpness` for CRYSTAL). `erosionType` is structural (not animated, baked into the GLSL, recompiles on change). Color: red (`#F44336`).

**MaterialNode:** Parameters: `colorR`, `colorG`, `colorB`, `roughness`, `metallic`, `ior`, `emission`. `materialType` is structural (not animated). Color: purple (`#9C27B0`).

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
| `"primitive"` | `primitiveType` (enum name), `sizeX`, `sizeY`, `sizeZ`, `rounding`, `shell` |
| `"csg"` | `op` (enum name), `blend`, `left` (recursive), `right` (recursive) |
| `"transform"` | `mode` (enum name), `axis`, `offset` (3-array), `rotation` (3-array), `scale`, `frequency`, `child` (recursive) |
| `"effect"` | `effectType` (enum name), `strength`, `time`, `scale`, `erosionType`, `sharpness`, `child` (recursive) |
| `"material"` | `materialType` (int), `colorR`, `colorG`, `colorB`, `roughness`, `metallic`, `ior`, `emission`, `child` (recursive) |

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
┌─── Toolbar ──────────────────────────────────────────────────────────────────────────────────┐
│ [+Fractal] [Wrap CSG] [Wrap Transform ▼] [Wrap Effect ▼] [Wrap Material] [+] [Delete] [Undo] [Redo] │
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

- Nodes drawn as colored rounded rectangles (FractalNode=blue, CSG=orange, Transform=mode-specific color, Effect=red, Material=purple, Primitive=teal)
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
| `graph/PrimitiveNode.java` | Leaf: 11 analytic SDF shapes |
| `graph/CSGNode.java` | Binary: 4 operations + blend |
| `graph/TransformNode.java` | Unary: 7 transform modes |
| `graph/EffectNode.java` | Unary: 3 surface effect types (Erosion/Crystal/Moss) |
| `graph/MaterialNode.java` | Unary: per-node material overrides (type, colorMode, color, roughness, metallic, ior, emission) via SSBO |
| `graph/GraphCompiler.java` | Tree → composite GLSL (8 phases + Phase 3.5 effects + SSBO material declaration) |
| `graph/GraphNodeNamer.java` | Stable unique naming for animation tracks |
| `graph/NodeGraphAnimationHelper.java` | DFS parameter discovery for timeline integration |
| `fractals/NodeGraphParams.java` | `AbstractFractalParams` wrapper for graph tree |
| `ui/components/NodeGraphEditor.java` | Visual tree editor + detail panel |
| `config/FractalConfig.java` | `serializeGraphNode()` / `deserializeGraphNode()` |

---

## Architecture Notes & Future Direction

### Current Approach: Material ID + SSBO

The material system uses an SSBO-based lookup. Each MaterialNode gets a unique `int matId` at compile time. OrbitTrap carries only `int matId` (1 field). Material properties are stored in a Shader Storage Buffer Object (binding 6) indexed by ID.

**Key design points:**
- Zero overhead when no MaterialNode exists (`#define HAS_MATERIALS` only emitted when needed)
- OrbitTrap stays slim: 6 fields with materials (vs 12+ in the previous fat-trap approach)
- CSG propagation: save/reset/pick operates on a single `int _matId` — no save/restore of 6+ fields
- Adding a new property = add a field to the SSBO struct + update the Java buffer. No GraphCompiler changes.
- `colorMode` supports 3 modes: palette (keep fractal colors), solid (replace), tint (multiply)
- SSBO binding point 6 (0-4 are textures, 5 is variance image)
