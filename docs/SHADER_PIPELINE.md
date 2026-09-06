# Shader Assembly Pipeline

How GLSL shaders are assembled and sent to the GPU in Fractaliz3r.

## Overview

Every rendered frame is a fullscreen quad drawn by a fragment shader. That fragment shader is assembled at runtime from multiple GLSL source files concatenated into one string, compiled, and cached. There are 4 assembly modes depending on the active fractal type.

**Common concatenation pattern:**

```
#version 430 core
  + common.glsl          ← global uniforms, utilities, noise, materials
  + [FRACTAL SLOT]       ← one or more fractal DE shaders (varies by mode)
  + raytracer.glsl       ← raymarcher, lighting, path tracing, main()
```

The `[FRACTAL SLOT]` is the only part that changes between modes. Everything before and after is constant.

---

## Assembly Modes

### Mode 1 — Standard (Single Fractal)

**Method:** `GLSLEngine.loadFractalShader(String name, String fractalShaderPath)`

Loads one fractal shader from `src/main/resources/shaders/fractals/`.

```
#version 430 core
+ common.glsl
+ fractals/{kernelName}.glsl      (e.g., mandelbulb.glsl)
+ raytracer.glsl
```

`raytracer.glsl` calls `DE(pos, trap)` and `DE_simple(pos)` directly — the single fractal shader defines them.

**When:** Any of the 10 built-in fractal types (Mandelbulb, Mandelbox, Menger, etc.), and `FractalTerrain`, `CornellBox`, `TestScene`.

---

### Mode 2 — Boolean Operations (CSG)

**Method:** `GLSLEngine.loadBooleanFractalShader(String name, String primaryPath, String secondarySource)`

Combines two fractal shaders with CSG operations (Union, Intersect, Subtract, Morph, Nesting).

```
#version 430 core
#define BOOLEAN_OPS
+ common.glsl
+ fractals/{primary}.glsl           (original, unmodified)
+ fractals/{secondary}.glsl         (preprocessed with b_ prefix)
+ raytracer.glsl
```

**Symbol conflict resolution:** Both fractals define `DE()`, `DE_simple()`, `OrbitTrap`, etc. To avoid linker errors, `ShaderPreprocessor.renameLocalSymbols(source, "b_")` renames all symbols in the secondary shader with a `b_` prefix before concatenation. See [ShaderPreprocessor](#shaderpreprocessor) below.

**In raytracer.glsl:** `#ifdef BOOLEAN_OPS` blocks replace direct `DE()`/`DE_simple()` calls with `boolDE()`/`boolDE_simple()`, which evaluate both fractals and combine results based on `boolOp`. All 8 geometry DE call sites (rayMarch, calcNormal, calcShadow, calcAO, calcSSS, rayMarchSimple, 2x glass interior) are wrapped.

**When:** User enables Boolean Operations in FractalPanel and selects a secondary fractal type.

---

### Mode 3 — Node Graph (Composite)

**Method:** `GLSLEngine.loadCustomFractalShader("nodegraph", compositeGLSL)`

The node graph tree is compiled into a single GLSL block by `GraphCompiler` and injected into the standard fractal slot.

```
#version 430 core
+ common.glsl
+ [GraphCompiler output]            (multiple preprocessed fractals + transforms + CSG + composite DE)
+ raytracer.glsl
```

`GraphCompiler` produces a self-contained GLSL block that defines `OrbitTrap`, `DE()`, `DE_simple()`, and `getFactors()` — the same contract as any single fractal shader. The raytracer is unaware it's rendering a composite graph. EffectNodes emit calls to parameterized `*P()` functions from `common.glsl` with per-node uniforms (`e0_strength`, `e0_time`, etc.).

**Full documentation:** [NODE_GRAPH.md](NODE_GRAPH.md) (GraphCompiler section)

**When:** Fractal type is `NODE_GRAPH` (`NodeGraphParams`).

---

### Mode 4 — Custom Shader

**Method:** `GLSLEngine.loadCustomFractalShader(String name, String userSource)`

Same pipeline as Node Graph — user-written GLSL replaces the fractal slot.

```
#version 430 core
+ common.glsl
+ [user-written source]            (must define OrbitTrap, DE(), DE_simple(), getFactors())
+ raytracer.glsl
```

**Return value:** `null` on success, error message string on compilation failure.

**When:** Fractal type is `CUSTOM_SHADER` (`CustomShaderParams`).

---

### Mode 5 — Evaluator (Mesh Export)

**Method:** `GLSLEngine.loadEvaluatorShader(String fractalShaderPath)`

Used for GPU-accelerated 3D mesh export (Marching Cubes). Replaces `raytracer.glsl` with `evaluator.glsl`.

```
#version 430 core
+ common.glsl
+ fractals/{kernelName}.glsl
+ evaluator.glsl                    (grid evaluation, outputs vec4(color, distance))
```

**When:** User exports to OBJ/glTF/PLY via the mesh export feature.

---

## Shader File Roles

### `common.glsl` — Global Foundation

Prepended to every shader program. Defines:

| Section | Contents |
|---------|----------|
| **Resolution & Tiling** | `resolution`, `tileOffset`, `tileScale`, `fullResolution`, `sampleIndex` |
| **Camera** | `camPos` (vec3), `camQuat` (vec4), `fov`, `projectionMode` |
| **Lighting** | `lightDir/Color/Intensity`, `ambientColor/Intensity`, extra light (point/spot/area) |
| **Material & Coloring** | `baseHue`, `paletteIndex`, `colorStrength`, `paletteOffset`, `coloringMode` |
| **Quality** | `qualityMultiplier`, `maxRaySteps`, `baseEpsilon`, `shadowSoftness`, `aoSteps` |
| **Path Tracing** | `pathTracingEnabled`, `materialType`, `roughness`, `metallic`, `ior` |
| **Textures** | `envMap` (sampler2D, unit 0), `paletteTexture` (sampler2D, unit 1), `varianceImage` (image2D, binding 5) |
| **Effects** | Erosion, crystallization, moss (global uniforms + parameterized `*P()` functions for per-node effects), cross-section, DoF, fog, bloom params |
| **Audio** | `audioEnabled`, `audioLevel`, `audioBeat`, `audioOnset`, `audioBands[8]`, react params |
| **Utilities** | Noise functions (`hash`, `noise3D`, `fbm`, `fbmLow`, `voronoi3D`, `triplanarNoise`), rotation helpers, `DE_simple()` forward declaration |
| **Materials** | `applyMaterial(factors, hitPos, normal, rayDir)`, `getPresetPalette()`, `getSmoothPalette()`, 13 coloring modes (0–8 orbit-trap, 9–12 geometry-based) |
| **Erosion/Crystal/Moss** | Global: `getErosionDisplacement()`, `getCrystalDisplacement()`, `getMossFactor()`. Per-node parameterized: `*P()` variants (`getErosionDisplacementP()`, `getCrystalDisplacementP()`, `getMossDisplacementP()`, etc.) with attenuation factors (×0.05/×0.1/×0.2) |

### `raytracer.glsl` — Raymarching & Rendering

Appended after the fractal slot. Defines the full rendering pipeline:

| Function | Purpose |
|----------|---------|
| `getCameraRay(uv)` | Camera ray from screen UV (perspective, equirectangular, etc.) |
| `rayMarch(Ray)` | Core raymarching loop with adaptive epsilon |
| `calcNormal(pos)` | Tetrahedron gradient via 4x `DE_simple()` |
| `calcShadow(pos, dir, range)` | Soft shadow ray |
| `calcAO(pos, normal)` | Ambient occlusion |
| `shade(hit)` / `shadeSimple(hit)` | Direct lighting (Phong/GGX) |
| `pathTrace(ray)` / `pathTraceClassic(ray)` | Monte Carlo path tracing with NEE+MIS |
| `main()` | Fragment shader entry: camera ray → raymarch → shade → post-effects → output |

**Boolean Operations block:** Under `#ifdef BOOLEAN_OPS`, defines `boolDE(pos, trap)` and `boolDE_simple(pos)` that evaluate both primary and `b_`-prefixed secondary DEs, apply offset/rotation/scale to secondary, and combine via `boolCombine()`.

### `fractals/*.glsl` — Fractal Distance Estimators

Each fractal shader must define exactly 4 things:

```glsl
// 1. Fractal-specific uniforms
uniform float power;
uniform int maxIterations;

// 2. Orbit trap structure
struct OrbitTrap {
    float minDist, planeX, planeY, planeZ;
    int iterations;
};

// 3. Full DE with orbit trap tracking (for coloring)
float DE(vec3 pos, out OrbitTrap trap) { ... }

// 4. The same DE without orbit traps (for shadows, AO, normals). Same geometry, not a
//    cheaper approximation: a DE_simple that describes another surface gives normals of
//    a surface the eye ray never hit and shadow rays that start inside it (see
//    docs/RENDERING.md, Surface Effects, for the day it did).
float DE_simple(vec3 pos) { ... }

// 5. Convert traps to coloring factors
vec3 getFactors(OrbitTrap trap) { ... }
```

Available fractal shaders:

| File | Fractal |
|------|---------|
| `mandelbulb.glsl` | Mandelbulb |
| `mandelbox.glsl` | Mandelbox |
| `menger.glsl` | Menger Sponge |
| `kaleidoscopic.glsl` | Kaleidoscopic IFS |
| `quaternionjulia4d.glsl` | Quaternion Julia 4D |
| `polyhedral.glsl` | Polyhedral IFS |
| `sierpinski.glsl` | Sierpinski Tetrahedron |
| `pseudokleinian.glsl` | Pseudo-Kleinian |
| `apollonian.glsl` | Apollonian Gasket |
| `bristorbrot.glsl` | Bristorbrot |
| `fractalterrain.glsl` | Fractal Terrain |
| `cornellbox.glsl` | Cornell Box |

### `postprocess.glsl` — Post-Processing

Runs as a **separate** shader program (not concatenated with the fractal). Reads the accumulated render texture and applies:

1. Per-pixel sample count division (adaptive sampling)
2. Tone mapping (ACES, Reinhard, Filmic)
3. Color grading (procedural LUT styles)
4. Bloom blending
5. Chromatic aberration
6. Vignette
7. Film grain
8. Sharpening

Early return for `renderMode != 0` (depth/normal AOV passes skip post-processing).

---

## ShaderPreprocessor

**File:** `engine/ShaderPreprocessor.java`

**Method:** `static String renameLocalSymbols(String source, String prefix)`

Renames all locally-defined GLSL symbols with a prefix so multiple fractal shaders can coexist in one program.

### Algorithm

**Phase 1 — Symbol Collection** (4 regex passes):

| Pattern | Captures |
|---------|----------|
| `\buniform\s+\w+\s+(\w+)\s*[;=]` | Uniform names |
| `\bstruct\s+(\w+)\s*\{` | Struct names |
| `(?m)^\s*(?!uniform\|layout\|in\|out)(\w+)\s+(\w+)\s*\(` | Function names |
| `\bconst\s+\w+\s+(\w+)\s*=` | Constant names |

**Phase 2 — Filter builtins:** Remove GLSL builtins (types, qualifiers, math functions, component accessors, GL variables) from the symbol set.

**Phase 3 — Replace:** Sort symbols by length descending (prevents partial matches), then `\bsymbol\b` → `prefix + symbol` for each.

### What gets renamed

- Uniforms: `power` → `b_power`
- Structs: `OrbitTrap` → `b_OrbitTrap` (members stay unchanged)
- Functions: `DE()` → `b_DE()`, `DE_simple()` → `b_DE_simple()`
- Constants: `BAILOUT` → `b_BAILOUT`

### What does NOT get renamed

- GLSL builtins: `sin`, `cos`, `vec3`, `texture`, `imageStore`, etc.
- Struct member names (only struct name is prefixed)
- Swizzle components (`.xyz`, `.rgb`)

### Usage

Used in two contexts:
1. **Boolean Operations:** `ShaderPreprocessor.renameLocalSymbols(secondarySource, "b_")` — called in `GLSLFractalizerController.activateCurrentProgram()`
2. **Node Graph:** `ShaderPreprocessor.renameLocalSymbols(fractalSource, "n0_")` — called in `GraphCompiler.compile()` for each leaf node (prefixes: `n0_`, `n1_`, `n2_`, ...)

---

## Uniform Lifecycle

### 1. Build

`GLSLFractalizerController.buildUniforms()` constructs a `Map<String, Object>` containing every uniform value for the current frame:

- Camera: `camPos`, `camQuat`, `fov`, `projectionMode`
- Quality: `qualityMultiplier`, `maxRaySteps`, `baseEpsilon`
- Lighting: `lightDir`, `lightColor`, `lightIntensity`, ambient, extra lights
- Material & Coloring: `baseHue`, `paletteIndex`, `colorStrength`, `coloringMode`
- Effects: erosion, crystallization, moss (global disabled in node graph mode), distortion, cross-section
- Boolean ops: `boolOp`, `boolOffset`, `boolScale`, `boolBlend`, `b_`-prefixed secondary uniforms
- Node graph: per-node prefixed uniforms via `NodeGraphParams.getUniformValues()`
- Audio: `audioEnabled`, `audioLevel`, bands, react params
- DoF, fog, bloom, post-processing params

For **Node Graph** mode, `NodeGraphParams.getUniformValues()` returns the map from `GraphCompiler.collectUniformsStatic(root)` which traverses the node tree and collects all per-node prefixed uniforms (fractal: `n0_`, transform: `t0_`, CSG: `c0_`, effect: `e0_`).

### 2. Upload

`GLSLEngine.renderSample(Map<String, Object> uniforms)`:

1. Activate the shader program
2. Set built-in uniforms (`resolution`, `sampleIndex`, `time`)
3. Bind textures (`envMap` → unit 0, `paletteTexture` → unit 1)
4. Iterate through the uniforms map and call `setUniformValue()` for each entry
5. Draw fullscreen quad
6. Memory barrier if adaptive sampling is enabled

### 3. Type Dispatch

`ShaderProgram.setUniform()` dispatches by Java type:

| Java Type | GLSL Call |
|-----------|-----------|
| `Float` | `glUniform1f` |
| `Integer` | `glUniform1i` |
| `Boolean` | `glUniform1i` (0/1) |
| `float[2]` | `glUniform2f` |
| `float[3]` | `glUniform3f` |
| `float[4]` | `glUniform4f` |
| `float[9]` | `glUniformMatrix3fv` |

---

## Recompilation vs Uniform Update

### Recompilation triggers (new shader program compiled)

| Trigger | Happens when |
|---------|-------------|
| First use of a fractal type | `loadFractalShader()` — cached after first compile |
| Boolean: secondary type changed | New `primary_secondary` key → `loadBooleanFractalShader()` |
| Node graph: structural change | Node added/removed/type changed → `GraphCompiler.compile()` → `loadCustomFractalShader("nodegraph", ...)` |
| Custom shader: user clicks Compile | `loadCustomFractalShader()` |

### Uniform-only update (no recompilation)

| Change | Why no recompile |
|--------|-----------------|
| Slider adjustment (power, scale, iterations, ...) | Same shader, different uniform values |
| Camera movement | `camPos`/`camQuat` uniforms |
| Lighting change | Light uniforms |
| Node graph: slider change (no structural change) | `NodeGraphParams.updateUniforms()` → `collectUniformsStatic()` |
| Boolean: offset/scale/blend change | `boolOffset`/`boolScale`/`boolBlend` uniforms |

### Caching

- **Normal fractals:** Cached by `kernelName` in `GLSLEngine.programs` map. Never recompiled unless the map is cleared.
- **Boolean shaders:** Cached by `"primary_secondary"` key (e.g., `"mandelbulb_menger"`). Recompiled only when the combination changes.
- **Node graph:** Single program key `"nodegraph"`. Recompiled when `NodeGraphParams.isDirty()` returns true (structural change sets dirty flag).
- **Custom shaders:** Single program key per custom shader. Old program deleted before new one is compiled.

---

## Program Selection Flow

`GLSLFractalizerController.activateCurrentProgram()` runs before every render and follows this decision tree:

```
currentParams instanceof NodeGraphParams?
├── YES → isDirty() || !hasProgram("nodegraph")?
│         ├── YES → recompile() → loadCustomFractalShader("nodegraph", glsl)
│         └── setActiveProgram("nodegraph") → return
│
└── NO → (AbstractFractalParams)
         booleanEnabled && secondaryType != null?
         ├── YES → programKey = "primary_secondary"
         │         programKey changed?
         │         ├── YES → load secondary → ShaderPreprocessor → loadBooleanFractalShader()
         │         └── setActiveProgram(programKey)
         │
         └── NO → programName = kernelName
                  !hasProgram(programName)?
                  ├── YES → loadFractalShader(programName, path)
                  └── setActiveProgram(programName)
```

---

## Source Files

| File | Role |
|------|------|
| `engine/GLSLEngine.java` | Shader loading, compilation, program management, rendering |
| `engine/ShaderPreprocessor.java` | Symbol renaming for multi-fractal coexistence |
| `ui/GLSLFractalizerController.java` | Program selection (`activateCurrentProgram`), uniform building (`buildUniforms`) |
| `graph/GraphCompiler.java` | Node graph → composite GLSL compilation |
| `shaders/common.glsl` | Global uniforms, utilities, materials |
| `shaders/raytracer.glsl` | Raymarching, lighting, path tracing |
| `shaders/postprocess.glsl` | Tone mapping, bloom, color grading |
| `shaders/evaluator.glsl` | Grid evaluation for mesh export |
| `shaders/fractals/*.glsl` | Individual fractal distance estimators |
