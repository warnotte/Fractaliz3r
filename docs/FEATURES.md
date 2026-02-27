# Features Reference

Detailed documentation of advanced features in Fractaliz3r.

---

## Advanced UI Features

### EnhancedSlider Component

A standard component used for **all** numeric parameters across the application:
- **Mouse Wheel Support**: Scroll over any slider to adjust value.
- **Modifier Keys** (Required for value adjustment to avoid ScrollPane conflicts):
  - `ALT + Scroll`: Normal speed adjustment.
  - `SHIFT + Scroll`: Fine/Precision control (10x slower).
  - `CTRL + Scroll`: Fast movement (10x faster).
- **Automatic Labels**: Displays title and value with configurable precision (default 3 decimals).
- **Inline Value Entry**: Double-click on the label or slider track to open an inline text field. Shows the real (unrounded) value. Enter to confirm, Escape to cancel, focus-loss auto-commits. Value is clamped to slider min/max. Styled via `.inline-edit-field` CSS class.
- **Lock Button**: Each slider has a lock toggle that protects it from the dice randomizer.
- **Tooltip Help**: Explains shortcuts when hovering.

### Dice Randomizer (FractalPanel)

A parameter randomization system with undo/redo history:
- Randomizes all unlocked fractal-specific sliders and ComboBoxes using their own min/max ranges.
- Navigate through dice history (up to 50 snapshots).
- **Auto-discovery**: Uses recursive tree traversal of the visible fractal controls VBox — adding a new slider or fractal type requires zero changes to the randomizer.
- **Lock integration**: Locked sliders are preserved during randomization.
- **Snapshots**: Captured via `IdentityHashMap<Object, Object>` before each roll; restored by setting slider/combo values directly (no suppressRender, so param callbacks fire correctly).

### Morph Crossfade (FractalPanel)

Smooth interpolation between two parameter snapshots:
- **Set A / Set B**: Capture current fractal params as morph endpoints.
- **Morph Slider** (0→1): Linearly interpolates all slider values between A and B. ComboBoxes snap at 0.5.
- **Type safety**: Morph is disabled (slider grayed out) if A and B are different fractal types, with a warning label.
- Uses the same recursive `IdentityHashMap` snapshot mechanism as the dice history.

### Parameter Persistence

The `GLSLFractalizerController` maintains a cache of fractal parameters. Switching between fractal types preserves your specific settings for each fractal, while common settings (Path Tracing, Palette, Lighting) are synchronized across all types.

---

## Boolean Operations (Legacy/Core)

Constructive Solid Geometry between fractal distance fields. This logic now powers the `CSGNode` in the node graph.

---

## Custom Shader Editor

Write custom fractal DE shaders and compile them on the fly. Built on the same runtime GLSL compilation pipeline as Boolean Operations.

- **User writes fractal part only** — `common.glsl` + `raytracer.glsl` are assembled automatically by `GLSLEngine.loadCustomFractalShader()`.
- **Shader contract**: User must define `OrbitTrap` struct, `DE()`, `DE_simple()`, `getFactors()` — same as any built-in fractal shader.
- **`@param` annotations** for automatic slider UI:
  ```glsl
  uniform float power;      // @param min:2 max:16 default:8
  uniform int maxIterations; // @param min:3 max:30 default:15
  uniform vec3 offset;       // @param min:-2 max:2 default:0,0,0
  ```
  Supported types: `float`, `int`, `vec2`, `vec3`, `vec4`. Uniforms without `@param` get no slider (hardcoded in GLSL).
- **Dynamic sliders**: `CustomShaderEditor` (in `ui/components/`) parses `@param` annotations on successful compile and generates `EnhancedSlider` controls. Values stored in `CustomShaderParams.uniformValues` map, emitted via `buildUniforms()`.
- **Async compilation**: Runs on a daemon thread to avoid blocking the FX thread. UI shows "Compiling..." state with disabled controls.
- **6 templates**: Sphere, Torus, Gyroid, Infinite Spheres, Menger Sponge, Mandelbulb (Simple) — each demonstrates a different SDF technique.
- **Auto-compile on load**: When loading a `.frac` file with a custom shader, `loadParams()` triggers compilation via `Platform.runLater` so saved shaders render immediately.
- **Serialization**: `shaderSource` (String) + `uniformValues` (Map) in `FractalConfig`. Gson `List<Double>` → `float[]` conversion handled in `applyFractalParams()`.
- **Not usable as boolean secondary** — excluded from the boolean ops combo alongside `TEST_SCENE`, `CORNELL_BOX`, `FRACTAL_TERRAIN`.
- **Files**: `CustomShaderParams.java` (model), `CustomShaderEditor.java` (UI component in `ui/components/`), `FractalType.CUSTOM_SHADER` enum value.

---

## Multi-Fractal Nesting (Experimental)

Tiles a secondary fractal as micro-geometry on the surface of a primary fractal. E.g., a Menger Sponge covered in mini-Mandelbulbs. Extends the Boolean Operations system with `boolOp == 4` ("Nesting").

- **Reuses 100% of the boolean pipeline**: `ShaderPreprocessor` + `loadBooleanFractalShader` + `#ifdef BOOLEAN_OPS`. No new shader files or compilation paths.
- **raytracer.glsl**: `nestEvalCell()` (shared by `boolDE` and `boolDE_simple`) implements the nesting logic:
  1. Early-out when `d1 > nestThreshold` (far from primary surface — skip secondary eval)
  2. **Domain warp**: 3x `fbmLow` displace position before tiling — breaks regular grid, organic cell boundaries
  3. `fract()` tiles warped space into repeating cells
  4. **Edge fade**: `smoothstep(0.6, 1.0, edgeDist)` fades micro-fractals to zero at cell boundaries — eliminates visible seams
  5. Per-cell random rotation via `nestCellRotate()`: hashes `cellId` to produce a unique axis + angle per cell (Rodrigues rotation). `nestRotation` controls max amplitude (0 = all aligned, 360 = fully random).
  6. Evaluate `b_DE_simple(cellPos)` in the tiled/rotated cell, scale result back to world space
  7. Final blend: `smoothstep(threshold proximity) * fade * nestMix` — combines surface proximity, edge fade, and global mix
- **All 8 existing boolean DE call sites** (calcNormal, calcShadow, calcAO, calcSSS, rayMarch, rayMarchSimple, 2x glass) work automatically — they already dispatch through `boolDE`/`boolDE_simple`.
- **Parameters** (in `AbstractFractalParams`, serialized in `EffectsConfig`):
  - `nestThreshold` (float, 0.01-1.0, default 0.1) — shell thickness
  - `nestRepeatScale` (float, 0.5-50, default 5.0) — repetition density
  - `nestRotation` (float, 0-360, default 0) — max per-cell random rotation
  - `nestMix` (float, 0-1, default 1) — crossfade: 0 = pure primary, 1 = full nesting
  - Also uses existing `boolScale` for secondary fractal size within each cell
- **UI**: "Nesting" option in the Boolean Operations operation ComboBox. Mix, Threshold, Repeat Scale, and Rotation sliders appear only when Nesting is selected.
- **Zero overhead when not Nesting**: `boolOp != 4` takes the normal boolean path with zero extra cost.
