# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## IMPORTANT Rules
- **NEVER** include `Co-Authored-By`, AI mentions, Claude mentions, or any reference to AI/LLM in commit messages, code comments, or any generated content. This rule is absolute and permanent.
- Creative feature ideas and roadmap: see **[IDEAS.md](IDEAS.md)**

## Build Commands

```bash
# Build the project
mvn compile

# Run tests
mvn test

# Run the application (JavaFX)
mvn javafx:run

# Package as JAR
mvn package

# Clean and rebuild
mvn clean install
```

## Project Architecture

### Core Components

```
org.fractalizer
├── GLSLFractalizerApp.java          # JavaFX entry point with FPS navigation
├── engine/
│   ├── GLSLEngine.java              # GPU compute abstraction (LWJGL/OpenGL)
│   ├── ShaderPreprocessor.java      # GLSL symbol renaming for shader concatenation (boolean ops)
│   ├── BlueNoiseGenerator.java      # 64x64 blue noise texture (Mitchell's best-candidate)
│   └── Camera.java                  # Quaternion-based FPS camera (no gimbal lock)
├── fractals/
│   ├── FractalParams.java           # Interface for fractal parameters
│   ├── FractalType.java             # Enum of available fractal types
│   ├── AbstractFractalParams.java   # Base class with common params (camera, lighting, gradient, etc.)
│   ├── GradientPalette.java         # Custom gradient model with ColorStops and GPU texture generation
│   ├── MandelbulbParams.java        # Mandelbulb: power, iterations, bailout
│   ├── MandelboxParams.java         # Mandelbox: scale, minRadius, fixedRadius, foldingLimit
│   ├── MengerSpongeParams.java      # Menger Sponge: iterations, scale, offset
│   ├── KaleidoscopicIFSParams.java  # KIFS: scale, offset, fold angles
│   ├── QuaternionJulia4DParams.java  # Quaternion Julia 4D: juliaC, sliceW, 4D rotations (XW/YW/ZW)
│   ├── PolyhedralIFSParams.java     # Polyhedral IFS: symmetry type, scale, rotations, offsets
│   ├── SierpinskiParams.java        # Sierpinski Tetrahedron: iterations, scale
│   ├── PseudoKleinianParams.java    # Pseudo-Kleinian: iterations, CSize vec3, Size, DEoffset
│   ├── ApollonianParams.java        # Apollonian Gasket: iterations, scale, foldRadius
│   ├── BristorbrotParams.java       # Bristorbrot: iterations, bailout
│   ├── FractalTerrainParams.java    # Fractal Terrain: fBm heightfield with octaves, lacunarity, warp, ridge
│   ├── CornellBoxParams.java        # Cornell Box: sceneScale (per-object materials in shader)
│   ├── CustomShaderParams.java      # Custom Shader: user GLSL source + dynamic uniform values
│   └── NodeGraphParams.java        # Node Graph: wraps GraphNode tree as fractal params
├── graph/
│   ├── GraphNode.java              # Abstract base for composable fractal operations
│   ├── FractalNode.java            # Leaf node: wraps FractalType + per-node params
│   ├── CSGNode.java                # Binary CSG: Union/Intersect/Subtract/Morph
│   ├── TransformNode.java          # Coordinate transform: 7 modes (Standard/Mirror/Twist/Bend/Taper/Rep/Rep1D)
│   ├── EffectNode.java             # Surface effects: Erosion/Crystal/Moss (unary, per-node)
│   ├── GraphCompiler.java          # Compiles node tree → composite GLSL shader (8 phases)
│   ├── GraphNodeNamer.java         # Stable node naming for animation tracks
│   └── NodeGraphAnimationHelper.java # Bridge: graph nodes → animatable timeline parameters
├── audio/
│   ├── AudioReactiveEngine.java     # Spectrum analysis, beat/onset detection
│   └── AudioPreAnalyzer.java        # Offline FFT pre-analysis (FFmpeg decode)
├── render/
│   ├── ProgressiveRenderer.java     # Progressive sample accumulation
│   └── FFmpegExporter.java          # MP4 video export via FFmpeg
├── animation/
│   ├── Timeline.java                # Animation timeline with tracks
│   ├── AnimationTrack.java          # Keyframe track with optional Catmull-Rom spline interpolation
│   └── Keyframe.java                # Single keyframe (time, value, easing)
└── ui/
    ├── GLSLFractalizerController.java  # Bridges UI with GLSL engine (includes parameter caching)
    ├── ViewportHUD.java                # 3D Compass, Speed Meter and Telemetry overlay
    ├── AnimationManager.java           # Manages timeline and keyframe editing
    ├── components/
    │   ├── EnhancedSlider.java         # Professional UI slider with mouse wheel & precision control
    │   ├── GradientEditor.java         # Visual gradient editor with draggable color stops
    │   ├── CustomShaderEditor.java     # GLSL editor with @param parsing, dynamic sliders, templates
    │   └── NodeGraphEditor.java      # Visual node graph editor: canvas + detail panel + undo/redo
    ├── panels/
    │   ├── FractalPanel.java           # Fractal type and parameters (uses EnhancedSlider)
    │   ├── MaterialPanel.java          # Material type, physical props, and artistic palettes (uses EnhancedSlider)
    │   ├── LightingPanel.java          # Light direction and colors (uses EnhancedSlider)
    │   ├── QualityPanel.java           # Ray steps, DoF, path tracing, preview samples, adaptive sampling (uses EnhancedSlider)
    │   ├── PostProcessingPanel.java    # Bloom, tone mapping, color correction (uses EnhancedSlider)
    │   ├── AudioPanel.java             # Audio-reactive controls + offline video export
    │   └── ExportPanel.java            # Image/animation export with motion blur & export samples
```

### GLSL Shaders

Located in `src/main/resources/shaders/`:

```
shaders/
├── raytracer.glsl         # Main raymarcher (fractal-agnostic)
├── common.glsl            # Common utilities, materials, and artistic palettes
├── postprocess.glsl       # Bloom, tone mapping, color correction
└── fractals/              # Individual fractal Distance Estimators
    ├── mandelbulb.glsl
    ├── mandelbox.glsl
    ├── menger.glsl
    ├── kaleidoscopic.glsl
    ├── quaternionjulia4d.glsl # Quaternion Julia 4D (4D slice + XW/YW/ZW rotations)
    ├── polyhedral.glsl    # Polyhedral IFS with Octa/Dodeca/Icosa/Tetra symmetries
    ├── sierpinski.glsl    # Sierpinski Tetrahedron (tetrahedral fold IFS)
    ├── pseudokleinian.glsl # Pseudo-Kleinian (box fold + sphere fold, tubular caves)
    ├── apollonian.glsl    # Apollonian Gasket (tetrahedral fold + sphere inversion)
    ├── bristorbrot.glsl   # Bristorbrot (component-wise 3D Mandelbrot)
    ├── fractalterrain.glsl # Fractal Terrain (fBm noise heightfield)
    └── cornellbox.glsl    # Cornell Box with per-object materials (#define HAS_PER_OBJECT_MATERIAL)
```

## Shader Assembly Pipeline

Shaders are assembled at runtime by concatenating GLSL source files. There are 4 assembly modes:

| Mode | Concatenation | Trigger |
|------|---------------|---------|
| **Standard** | `#version 430` + `common.glsl` + `fractal.glsl` + `raytracer.glsl` | Single fractal type |
| **Boolean Ops** | Above + `#define BOOLEAN_OPS` + preprocessed secondary (b_ prefix) | Boolean Operations enabled |
| **Node Graph** | `#version 430` + `common.glsl` + GraphCompiler output + `raytracer.glsl` | FractalType.NODE_GRAPH |
| **Custom Shader** | `#version 430` + `common.glsl` + user source + `raytracer.glsl` | FractalType.CUSTOM_SHADER |

Uniform changes never trigger recompilation — only structural changes (different fractal type, different secondary, node graph topology change) require a new shader compile. Compiled programs are cached in `GLSLEngine.programs`.

Full documentation: **[docs/SHADER_PIPELINE.md](docs/SHADER_PIPELINE.md)**

## Node Graph System

Composable fractal trees using a composite pattern. Combine multiple fractals with CSG operations and coordinate transforms, compiled into a single GPU shader.

**Architecture:** `GraphNode` (abstract) → `FractalNode` (leaf: wraps FractalType + per-node params) / `CSGNode` (binary: Union/Intersect/Subtract/Morph) / `TransformNode` (unary: 7 modes) / `EffectNode` (unary: Erosion/Crystal/Moss).

**GraphCompiler** compiles the tree into a composite GLSL block in 8 phases: ID assignment → shader loading/preprocessing → CSG helpers → effect uniforms → transform functions → OrbitTrap struct → composite DE() → composite DE_simple() → getFactors(). Each fractal gets a unique prefix (n0_, n1_, ...) and each effect gets `e0_`, `e1_`, ... via `ShaderPreprocessor` to avoid symbol conflicts.

**Animation:** `NodeGraphAnimationHelper` discovers animatable parameters via DFS traversal. Tracks named `{nodeName}.{paramName}` (e.g., "Mandelbulb.power", "Erosion.strength"). Stable naming via `GraphNodeNamer`.

**UI:** `NodeGraphEditor` — visual tree canvas (left) + detail panel with auto-discovered sliders (right). Undo/redo (30 snapshots). Context menu for add/delete/wrap/rename operations. "Wrap in Effect" submenu for Erosion/Crystal/Moss.

Full documentation: **[docs/NODE_GRAPH.md](docs/NODE_GRAPH.md)**

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
- **Lock Button**: Each slider has a lock toggle (🔓/🔒) that protects it from the dice randomizer.
- **Tooltip Help**: Explains shortcuts when hovering.

### Dice Randomizer (FractalPanel)
A parameter randomization system with undo/redo history:
- **🎲 Button**: Randomizes all unlocked fractal-specific sliders and ComboBoxes using their own min/max ranges.
- **◀ / ▶ Buttons**: Navigate through dice history (up to 50 snapshots).
- **Auto-discovery**: Uses recursive tree traversal of the visible fractal controls VBox — adding a new slider or fractal type requires zero changes to the randomizer.
- **Lock integration**: Locked sliders (🔒) are preserved during randomization.
- **Snapshots**: Captured via `IdentityHashMap<Object, Object>` before each roll; restored by setting slider/combo values directly (no suppressRender, so param callbacks fire correctly).

### Morph Crossfade (FractalPanel)
Smooth interpolation between two parameter snapshots:
- **Set A / Set B**: Capture current fractal params as morph endpoints.
- **Morph Slider** (0→1): Linearly interpolates all slider values between A and B. ComboBoxes snap at 0.5.
- **Type safety**: Morph is disabled (slider grayed out) if A and B are different fractal types, with a warning label.
- Uses the same recursive `IdentityHashMap` snapshot mechanism as the dice history.

### Parameter Persistence
The `GLSLFractalizerController` maintains a cache of fractal parameters. Switching between fractal types preserves your specific settings for each fractal, while common settings (Path Tracing, Palette, Lighting) are synchronized across all types.

## Rendering & Coloring

### Visual Gradient Editor
The coloring system uses a GPU-based 1D texture (256x1, RGB32F) driven by a visual gradient editor with draggable color stops.

**Architecture**: `GradientPalette` (model) → `toTextureData()` → `GLSLEngine.updatePaletteTexture()` (GL_TEXTURE1) → shader sampling via `getPresetPalette(t)` (fract, cyclic) and `getSmoothPalette(t)` (clamp, for environments).

**10 Built-in Presets**: Magma, Ice, Forest, Neon, Spectral, Sunset, Ocean, Aurora, Pastel, Monochrome.

**Parameters**:
- `Color Strength`: Multiplier for color intensity and contrast.
- `Palette Shift`: Global offset to slide colors across the fractal structure.

### Improved Orbit Traps
Major fractals use cumulative "Plane Traps" (weighted sum of absolute coordinates) to ensure rich, non-uniform coloring that reacts dynamically to fractal parameters.

### Sample Controls
Three separate sample counts control rendering quality at different stages:
- **Preview Samples** (QualityPanel, 16-4096, default 64): Controls `fullSamples` in the controller — the number of iterations for Auto Full Quality preview rendering.
- **Export Samples** (ExportPanel Image section, 16-1024, default 128): Passed directly to `exportToPNG(file, samples, progress)` for single image export.
- **Animation Samples** (ExportPanel Animation section, 1-128, default 16): Per-frame samples for animation sequence export.

### Blue Noise Sampling

AA jitter and DoF aperture sampling use a 64x64 blue noise texture instead of PCG white noise. Blue noise is spatially uniform — at equal sample counts, the image looks 2-3x cleaner to the human eye (no random clumping).

- **Texture**: 64x64 RG32F, generated at startup by `BlueNoiseGenerator` (Mitchell's best-candidate algorithm). Bound to `GL_TEXTURE2` during raytracer pass. `GL_NEAREST` filter, `GL_REPEAT` wrap (seamless tiling).
- **Temporal animation**: `fract(bn + sampleIndex * φ)` (golden ratio) decorrelates each frame while preserving the blue noise spectrum.
- **AA jitter**: `texelFetch(blueNoiseTex, ivec2(gl_FragCoord.xy) % 64, 0).rg` replaces `random2(seed)`.
- **DoF aperture**: Same texture, offset texel `(+37, +17)` for decorrelation from jitter. Drives the disk sample (`r`, `theta`) with polygon bokeh shaping preserved.
- **PCG seed consistency**: 2 dummy `random(seed)` calls at each replacement site keep the downstream PCG chain identical — zero regression on path tracing, volumetric fog, soft shadows.
- **Not used for**: path tracing bounces, volumetric fog, SSS, GGX — per-bounce randomness doesn't benefit from spatial blue noise.

### Raymarcher Improvements (Cone Tracing)

Four independently toggleable raymarcher enhancements in QualityPanel "Raymarcher" TitledPane. Each feature gates on its own condition — zero overhead when disabled.

- **Cone Tracing** (`pixelRadius`): Pixel-aware adaptive epsilon. `epsilon = max(MIN_EPSILON, pixelRadius * distance)` where `pixelRadius = tan(fov/2) / (height/2)`. Replaces legacy `computeAdaptiveEpsilon()` in `rayMarch`, `rayMarchSimple`, and `calcNormal`. Tiled export paths override `pixelRadius` using full image height (not tile height).
- **Fudge Factor** (0.1–2.0, default 1.0): DE step multiplier. `step *= fudgeFactor`. Applied in `rayMarch`, `rayMarchSimple`, and `calcShadow`. Values >1 = faster but riskier, <1 = more conservative.
- **Surface Refinement** (0–8 steps, default 4): Binary search bisection of the last step interval after hit. `refineSurface()` uses `DE_simple` to avoid overwriting orbit traps. Re-evaluates full `sceneDE` at refined position for correct coloring.
- **Step Relaxation** (0.0–1.0, default 0.0): Keinert 2014 over-relaxation. `omega = 1 + stepRelaxation`. On overshoot (`prevD + d < candidateStep`): backstep, reset to conservative stepping. Applied in `rayMarch` and `rayMarchSimple`.

**Parameters** (in `AbstractFractalParams`, serialized in `EffectsConfig`): `coneTracingEnabled` (bool, default true), `fudgeFactor` (float), `refinementSteps` (int), `stepRelaxation` (float).

### Cornell Box & Glass Refraction
The Cornell Box scene (`cornellbox.glsl`) uses `#define HAS_PER_OBJECT_MATERIAL` for per-object material assignment via `getObjectMaterial(OrbitTrap)`. Glass refraction in path tracing uses a two-surface approach: entry refraction + interior march using `abs(DE_simple)` + exit refraction, solving the SDF negative-distance problem inside glass bodies.

## Cinematic Rendering Pipeline

Fractaliz3r features a full cinematic rendering pipeline:
1. **Volumetric Fog & God Rays**: Physically-based scattering with Henyey-Greenstein phase function and shadow-aware light accumulation.
2. **Procedural Environments**: Dynamic sky types (Clouds, Space, Ocean, Studio) with spatial parallax based on camera movement.
3. **Optics (Lens Effects)**: Realistic camera imperfections including Lens Dirt (dust/spots) and JJ Abrams style anamorphic horizontal flares.
4. **Color Grading**: Procedural LUT styles (Cinema, Vintage, Matrix, Neon, Noir) for instant professional looks.

## Spline Camera Paths (Catmull-Rom)

AnimationTrack supports an opt-in `splineInterpolation` mode. When enabled, `getValue()` uses Catmull-Rom interpolation (4 control points) instead of linear (2 points), producing smooth curved trajectories through keyframes.

- **Enabled by default** on `camPos` and `camQuat` tracks (camera position and rotation).
- **Formula**: Standard Catmull-Rom: `q(t) = 0.5 * ((2*P1) + (-P0+P2)*t + (2*P0-5*P1+4*P2-P3)*t^2 + (-P0+3*P1-3*P2+P3)*t^3)`
- **Easing + Spline**: Easing modulates `t` before spline evaluation. LINEAR = constant speed along curve, EASE_IN_OUT = decelerate at keyframes.
- **Boundary clamping**: P0 = P1 when no prior keyframe, P3 = P2 when no next keyframe.
- **Quaternion normalization**: float[] of length 4 are auto-normalized after spline to prevent drift.
- **Serialization**: `splineInterpolation` boolean in `TrackConfig` (default false = backward compatible).
- **UI**: Green "S" indicator on each track in TimelineWidget. Click to toggle. Dim gray when off.

## Depth/Normal AOV Export

Export auxiliary render passes (AOVs) for compositing in After Effects, Nuke, etc.

- **Render modes** already exist in shader: `RENDER_MODE_NORMALS` (1) and `RENDER_MODE_DEPTH` (2).
- **postprocess.glsl**: Early return for `renderMode != 0` — raw data passes through without any post-processing.
- **Export**: `RenderController.exportAOV(File, int renderMode)` — 1 sample only (deterministic), supports tiled rendering.
  - Depth (mode 2): 16-bit grayscale PNG (`TYPE_USHORT_GRAY`)
  - Normals (mode 1): 8-bit RGB PNG
- **UI**: "Depth Map" and "Normal Map" checkboxes in ExportPanel. Files saved as `{name}_depth.png` / `{name}_normal.png`.
- **Animation**: Per-frame AOV passes exported alongside beauty frames (`frame_00000_depth.png`, etc.).

## Adaptive Sampling

Variance-based convergence detection that skips already-converged pixels during progressive rendering. Concentrates GPU effort on noisy regions (fractal detail, path-traced reflections) while skipping smooth areas (background, sky, flat surfaces). Best gains on fractal scenes with visible sky/background (~30-50% speedup). Minimal gain on closed scenes like Cornell Box.

- **Variance texture**: RGBA32F (`image2D`, binding 5). Per-pixel: R=sumLum, G=sumSqLum, B=count.
- **Convergence criterion**: Variance-of-mean (`popVariance / count < threshold`). This measures actual noise in the pixel average, not raw sample dispersion.
- **raytracer.glsl**: Early exit at top of `main()` if converged → `FragColor = vec4(0)` (additive blend adds nothing). Variance stats updated via `imageStore` after shading.
- **postprocess/bloom_extract/display**: Per-pixel sample count division (`texture(varianceTex, uv).b`) instead of global `sampleCount` when adaptive is on.
- **GLSLEngine**: Variance texture + FBO lifecycle, `glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)` between samples, `GL_TEXTURE_FETCH_BARRIER_BIT` before postprocess read.
- **Zero overhead when OFF**: No imageLoad/imageStore, no memory barriers.
- **Parameters** (in `AbstractFractalParams`, serialized in `EffectsConfig`):
  - `adaptiveSampling` (bool, default false)
  - `varianceThreshold` (float, default 0.0005 — stddev ~2.2% noise)
  - `minAdaptiveSamples` (int, default 16 — minimum passes before convergence check)
- **UI**: QualityPanel "Adaptive Sampling" TitledPane (checkbox + Threshold slider + Min Samples slider).
- **Interaction with sample counts**: Min Adaptive Samples is a floor before checking, Preview/Export Samples is the ceiling. A pixel renders between `minAdaptiveSamples` and `maxSamples` passes.
- **Render timer**: Status bar shows elapsed time after full quality render completes (e.g., "Rendered 64 samples in 3.2s").

## Surface Effects (Per-Node via EffectNode)

Procedural surface effects (Erosion, Crystallization, Moss) applied per-node in the node graph via `EffectNode`. Each effect wraps a child node and modifies its distance field, making effects composable and stackable.

> **Note:** Surface effects are exclusively per-node via the node graph system. The global QualityPanel controls have been removed. Global effect uniforms are forced to disabled in `GLSLFractalizerController` when in Node Graph mode.

### Architecture

`EffectNode` is a unary graph node (like `TransformNode`) with 3 effect types:

| EffectType | Displacement | Attenuation | Type-Specific Params |
|------------|-------------|-------------|---------------------|
| **EROSION** | Weathering cracks, hydraulic channels, thermal rounding | ×0.05 | `erosionType` (0=All, 1=Hydraulic, 2=Thermal, 3=Cracks) |
| **CRYSTAL** | Voronoi-based outward crystal growth | ×0.1 | `sharpness` (0.5-5) |
| **MOSS** | Organic growth in crevices/horizontal surfaces | ×0.2 | (none) |

Common parameters: `strength` (0-1), `time` (0-20), `scale` (0.1-5).

### GLSL Implementation

`common.glsl` provides parameterized functions (`*P()`) that accept parameters instead of reading global uniforms. Global functions are wrappers. `GraphCompiler` emits calls to `*P()` functions with per-node prefixed uniforms (`e0_strength`, `e0_time`, etc.):

```glsl
// GraphCompiler emits (example: erosion, full DE):
{ float _emaxD = erosionMaxDisplacementP(e0_strength, e0_time, e0_scale);
  if (n0_d < _emaxD + 0.1) n0_d += getErosionDisplacementP(pos, e0_strength, e0_time, e0_scale, e0_erosionType); }
```

- **Proximity gating**: Displacement only computed when `DE < maxDisplacement + 0.1` — skips 80-90% of ray steps
- **Full vs Light**: `DE()` uses full-quality functions, `DE_simple()` uses lightweight `*LightP()` variants
- **Stacking**: Effects can wrap other effects (e.g., Erosion wrapping Crystal wrapping Mandelbulb)

### Files

| File | Role |
|------|------|
| `graph/EffectNode.java` | Unary node: EffectType enum, strength/time/scale + type-specific params |
| `shaders/common.glsl` | 9 parameterized `*P()` functions + 9 global wrappers |
| `graph/GraphCompiler.java` | Phase 3.5 (effect uniforms), `emitEffectDE()`, `collectUniformsFromNode()` |
| `ui/components/NodeGraphEditor.java` | "Wrap in Effect" menu, `buildEffectDetail()` panel, red color |

### Animation

Animatable per-node: `strength`, `time`, `scale` (+ `sharpness` for CRYSTAL). `erosionType` is structural (not animated). Color: red (`#F44336`) in timeline.

### NVIDIA GLSL Pitfall

Avoid `+=`/`*=` on swizzled components (e.g., `flowP.y *= 0.25`) — causes `C9999: Unhandled expr op assign+` fatal error. Use explicit assignments.

## Domain Distortion (Legacy — Superseded by Node Graph TransformNode)

> **Note:** The global domain distortion system (QualityPanel) still works for single-fractal modes but is functionally superseded by the Node Graph's `TransformNode`, which provides the same 5 transform types (Twist, Bend, Taper, Repetition, Repetition 1D) plus Standard and Mirror — applied per-node with composable stacking. See **[docs/NODE_GRAPH.md](docs/NODE_GRAPH.md)** (TransformNode section).

The legacy system applies space-warping globally to `pos` BEFORE DE evaluation via `applyDomainDistortion()` in `common.glsl`. Parameters in `AbstractFractalParams` (`distortionEnabled`, `distortionType`, `distortionAxis`, `distortionStrength`, `distortionFrequency`, `distortionOffset`), serialized in `EffectsConfig`. UI in QualityPanel "Domain Distortion" TitledPane.

## Boolean Operations (CSG)

Constructive Solid Geometry between two fractal distance fields: Union, Intersect, Subtract, Nesting, or Morph. Combines any two fractal types (excluding Fractal Terrain, Cornell Box, Test Scene) with optional smooth blending.

- **GLSL symbol conflict solution**: `ShaderPreprocessor.java` renames all local symbols (uniforms, structs, functions, consts) in the secondary fractal shader with a `b_` prefix before concatenation. Zero changes to existing fractal shaders.
- **Shader assembly**: `GLSLEngine.loadBooleanFractalShader()` compiles `#version 430` + `#define BOOLEAN_OPS` + `common.glsl` + primary fractal + preprocessed secondary + `raytracer.glsl`. On-demand compilation with caching (avoids combinatorial explosion at startup).
- **raytracer.glsl**: `#ifdef BOOLEAN_OPS` block defines `boolDE(pos, trap)` and `boolDE_simple(pos)` — evaluate both DEs, transform secondary by offset/scale, combine via `boolCombine()`. 8 geometry DE call sites wrapped with `#ifdef` (calcNormal, calcShadow, calcAO, calcSSS, rayMarch, rayMarchSimple, 2× glass interior). Coloring-only DE calls use primary only.
- **Smooth boolean**: `smin_bool(a,b,k)` / `smax_bool(a,b,k)` with polynomial smooth min/max. `boolBlend=0` = hard boolean, `boolBlend>0` = organic transitions.
- **Morph** (`boolOp == 5`): `mix(d1, d2, boolBlend)` continuous DE blend. `morphFactors()` evaluates both fractals' orbit traps (`DE` + `b_DE`) and blends coloring factors at all 6 shading sites. `boolBlend` 0→1 = primary→secondary. Offset/rotation/scale of secondary work naturally.
- **Controller**: `activateCurrentProgram()` replaces all 11 `setActiveProgram()` calls — transparently switches between boolean and normal shader programs. `buildSecondaryUniforms()` emits `b_`-prefixed uniforms for the secondary fractal using default params.
- **Parameters** (in `AbstractFractalParams`, serialized in `EffectsConfig`):
  - `booleanEnabled` (bool), `booleanOp` (1=Union, 2=Intersect, 3=Subtract, 4=Nesting, 5=Morph)
  - `boolSecondaryType` (String, kernelName of secondary fractal)
  - `boolOffsetX/Y/Z` (float, -5 to 5), `boolRotX/Y/Z` (float, -180 to 180°), `boolScale` (float, 0.01 to 10), `boolBlend` (float, 0 to 2)
- **Secondary rotation**: Euler XYZ rotation applied to secondary position via `boolRotateSecondary()` (3× Rodrigues). Available in Union/Intersect/Subtract modes.
- **UI**: FractalPanel "Boolean Operations" TitledPane (enable checkbox, secondary fractal combo, operation combo, offset/rotation/scale/blend sliders).
- **Foundation for custom shaders**: The runtime GLSL compilation pipeline (`ShaderPreprocessor` + `loadBooleanFractalShader`) can be extended to compile user-written DE shaders on the fly.
- **Zero overhead when OFF**: Without `#define BOOLEAN_OPS`, all `#ifdef` blocks compile away — identical code to before.

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

## Multi-Fractal Nesting (Experimental)

Tiles a secondary fractal as micro-geometry on the surface of a primary fractal. E.g., a Menger Sponge covered in mini-Mandelbulbs. Extends the Boolean Operations system with `boolOp == 4` ("Nesting").

- **Reuses 100% of the boolean pipeline**: `ShaderPreprocessor` + `loadBooleanFractalShader` + `#ifdef BOOLEAN_OPS`. No new shader files or compilation paths.
- **raytracer.glsl**: `nestEvalCell()` (shared by `boolDE` and `boolDE_simple`) implements the nesting logic:
  1. Early-out when `d1 > nestThreshold` (far from primary surface — skip secondary eval)
  2. **Domain warp**: 3× `fbmLow` displace position before tiling — breaks regular grid, organic cell boundaries
  3. `fract()` tiles warped space into repeating cells
  4. **Edge fade**: `smoothstep(0.6, 1.0, edgeDist)` fades micro-fractals to zero at cell boundaries — eliminates visible seams
  5. Per-cell random rotation via `nestCellRotate()`: hashes `cellId` to produce a unique axis + angle per cell (Rodrigues rotation). `nestRotation` controls max amplitude (0 = all aligned, 360° = fully random).
  6. Evaluate `b_DE_simple(cellPos)` in the tiled/rotated cell, scale result back to world space
  7. Final blend: `smoothstep(threshold proximity) * fade * nestMix` — combines surface proximity, edge fade, and global mix
- **All 8 existing boolean DE call sites** (calcNormal, calcShadow, calcAO, calcSSS, rayMarch, rayMarchSimple, 2× glass) work automatically — they already dispatch through `boolDE`/`boolDE_simple`.
- **Parameters** (in `AbstractFractalParams`, serialized in `EffectsConfig`):
  - `nestThreshold` (float, 0.01-1.0, default 0.1) — shell thickness: how close to the primary surface the nesting appears
  - `nestRepeatScale` (float, 0.5-50, default 5.0) — repetition density of the secondary fractal
  - `nestRotation` (float, 0-360°, default 0) — max per-cell random rotation amplitude (converted to radians in controller)
  - `nestMix` (float, 0-1, default 1) — crossfade: 0 = pure primary fractal, 1 = full nesting effect
  - Also uses existing `boolScale` for secondary fractal size within each cell
- **UI**: "Nesting" option in the Boolean Operations operation ComboBox. Mix, Threshold, Repeat Scale, and Rotation sliders appear only when Nesting is selected.
- **Zero overhead when not Nesting**: `boolOp != 4` takes the normal boolean path with zero extra cost.

## GPU-Accelerated 3D Mesh Export

Export fractal geometry as 3D meshes (OBJ, glTF/GLB, PLY) using GPU-accelerated distance field evaluation. Uses the same GLSL fractal shaders as the renderer — 100% fidelity, zero CPU DE code.

- **Architecture**: `evaluator.glsl` renders each Z-slice as a fullscreen quad, outputting `vec4(color, distance)` per grid point. Colors are computed GPU-side via `applyMaterial(getFactors(trap))` — same palette/coloring pipeline as the renderer. `MarchingCubes.java` processes slices via a `SliceProvider` functional interface.
- **Grid alignment**: `evaluator.glsl` uses `gl_FragCoord` integer coordinates with a `gridResolution` uniform to map pixels to exact grid positions (`i/(gridResolution-1)`), matching the Marching Cubes grid formula.
- **Normals from distance grid**: Computed via central differences on already-evaluated distance values (4-slice sliding window: z-1, z, z+1, z+2). No CPU DE calls.
- **Colors**: Vertex colors computed entirely GPU-side (palette lookup + coloring modes in `evaluator.glsl`). Interpolated between cube corners in `MarchingCubes.java`. Zero CPU coloring code.
- **Formats**: OBJ (vertex colors), glTF 2.0 binary (.glb), PLY binary (point cloud, 28 bytes/vertex).
- **Adding a new fractal**: GPU mesh export works automatically — no Java code needed. `FractalEvaluator.java` has been deleted.

## VR & Export Features

- **360\u00B0 Equirectangular Projection**: Render full spherical panoramas compatible with VR headsets.
- **Automatic Metadata Injection**: 
    - **Photos**: Injects Google Photo Sphere (XMP) tags into JPEG and PNG files via `ImageWriterHelper`.
    - **Videos**: 
        - Uses **ExifTool** (if available in PATH) to automatically inject spherical metadata into MP4 files.
        - Provides a fallback guidance system with links to official Google tools if ExifTool is missing.
        - Includes a silent audio track to ensure YouTube/Facebook VR processing.
- **Multi-format Export**: Support for high-quality PNG and JPEG (95% quality).
- **Standard VR Presets**: 2048x1024 (2K) and 4096x2048 (4K) 2:1 aspect ratio presets.
- **Tiled Rendering**: Automatic tile-based rendering for exports exceeding 4096px in any dimension. The image is split into MAX_TILE_SIZE (4096px) tiles, each rendered independently with proper UV remapping via `tileOffset`, `tileScale`, and `fullResolution` shader uniforms, then assembled CPU-side. Transparent to the user — same export UI, progress bar spans all tiles linearly. Presets up to 16K (15360x8640) and 360° 8K (8192x4096). Bloom may have minor seams at tile boundaries (invisible at 4096px tiles).
- **Video Encoding**: Automatic MP4 creation via FFmpeg (H.265 HEVC) with `+faststart` for web optimization.

## Navigation Controls

- **Arrow keys**: Move forward/backward/strafe
- **Mouse drag**: Look around
- **Q/E**: Roll left/right
- **Page Up/Down**: Move up/down
- **R**: Reset camera
- **Space**: Render full quality
- **Scroll wheel**: Adjust movement speed (when not hovering a slider)

## Visual Regression Test

A headless rendering test (`TiledRenderTest.java`) that exports the same Mandelbulb scene at multiple resolutions. **Run this after any shader or rendering pipeline change** to verify no visual regression.

```bash
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.TiledRenderTest"
```

Outputs PNGs to `test_output/` (gitignored). After running, **read the images with the Read tool** to visually verify:
- `01_1920x1080_reference.png` — baseline, no tiling
- `02_4096x4096_boundary.png` — boundary case (exactly MAX_TILE_SIZE)
- `03_5000x2000_2x1tiles.png` — 2x1 tiles (X exceeds threshold)
- `04_8192x4096_2x1tiles.png` — 2x1 tiles (360° 8K preset size)
- `05_7680x4320_8K.png` — 2x2 tiles (8K preset)

What to check: fractal centered, correct aspect ratio, no tile seams, no Y-flip, background continuous across tiles.

## Building a Release (jlink)

```bash
mvn clean javafx:jlink package -DskipTests
```

This creates a self-contained runtime in `target/image/` with launcher script `bin/fractaliz3r.bat`. The `package` phase automatically extracts LWJGL native DLLs (glfw.dll, lwjgl.dll, etc.) into `bin/` and patches the launcher to set `-Dorg.lwjgl.librarypath`. Without this step, the app crashes on machines that never ran LWJGL before (no native cache in temp).

Distribute: zip `target/image/` — no JDK needed on the target machine.

## How to Add a New Fractal Type

Adding a fractal requires **2 new files** and **4 existing file edits**. Follow this checklist exactly.

### Step 1: Choose names

Pick a `kernelName` (lowercase, no spaces — used for shader filename and animation track prefix) and a `displayName` (human-readable). Example: `kernelName = "mandelbulb"`, `displayName = "Mandelbulb"`.

### Step 2: Create the Params class

**File:** `src/main/java/org/fractalizer/fractals/{Name}Params.java`

Follow `MandelbulbParams.java` as the minimal template:
- Extend `AbstractFractalParams`
- Declare fractal-specific fields with `@Animatable(display = "...")` annotation
- Set defaults in constructor (including `camera.setPosition(...)` if needed)
- Override `getType()` → return matching `FractalType` enum
- Override `withReducedQuality()` → copy specific params + reduce iterations
- Add getters/setters for each field

### Step 3: Create the GLSL shader

**File:** `src/main/resources/shaders/fractals/{kernelName}.glsl`

Follow `mandelbulb.glsl` as the template. Must define exactly these 4 things:
1. **Uniforms** — matching the names you'll bind in `buildUniforms()`
2. **`struct OrbitTrap`** — always has: `float minDist, planeX, planeY, planeZ; int iterations;`
3. **`float DE(vec3 pos, out OrbitTrap trap)`** — full DE with orbit trap tracking
4. **`float DE_simple(vec3 pos)`** — same math, no orbit traps (used for shadows/AO/normals)
5. **`vec3 getFactors(OrbitTrap trap)`** — converts traps to 3 coloring factors (structural, flow, iterNorm)

The `getFactors` pattern is standard — copy from mandelbulb.glsl and adjust exponential falloff coefficients if desired.

### Step 4: Wire into 4 existing files

#### 4a. `FractalType.java` — Add enum value

Add before `TEST_SCENE`:
```java
MY_FRACTAL("Display Name", "kernelname"),
```

#### 4b. `GLSLFractalizerController.java` — 2 switch blocks

**`setFractalType()` method** (~line 107) — add instantiation case:
```java
case MY_FRACTAL -> this.currentParams = new MyFractalParams();
```

**`buildUniforms()` method** (~line 884) — add uniform binding case:
```java
case MY_FRACTAL -> {
    MyFractalParams p = (MyFractalParams) currentParams;
    uniforms.put("maxIterations", p.getMaxIterations());
    uniforms.put("scale", p.getScale());
    // ... all fractal-specific uniforms matching the shader
}
```

For `vec3` uniforms: `uniforms.put("name", new float[]{x, y, z})`.
For `mat3` uniforms: use `createRotationMatrix()` helper.

#### 4c. `FractalConfig.java` (in `org.fractalizer.config`) — 2 methods

**`extractFractalParams()`** — add `else if` block to serialize params to map:
```java
} else if (params instanceof MyFractalParams p) {
    map.put("maxIterations", p.getMaxIterations());
    map.put("scale", p.getScale());
}
```

**`applyFractalParams()`** — add `else if` block to deserialize map to params:
```java
} else if (params instanceof MyFractalParams p) {
    if (map.containsKey("maxIterations")) p.setMaxIterations(getInt(map, "maxIterations"));
    if (map.containsKey("scale")) p.setScale(getFloat(map, "scale"));
}
```

Both blocks must go **before** the `TestSceneParams` / `CornellBoxParams` blocks (those are non-fractal scenes).

#### 4d. `FractalPanel.java` — 5 locations

1. **Field declarations** (~line 34): Add `private VBox myFractalControls;` and slider fields (`private EnhancedSlider mfIterSlider;`, etc.)

2. **`createContent()`** (~line 114): Add `createMyFractalControls();` call, and add `myFractalControls` to the `panel.getChildren().addAll(...)` list (before `testSceneControls`)

3. **`createMyFractalControls()` method**: Create the VBox, instantiate sliders with `new EnhancedSlider(label, min, max, default, isInteger)`, wire `.setOnAction(v -> { if (!suppressRender && params instanceof ...) { ... renderCallback.requestRender(); } })`. End with `setVisible(false); setManaged(false);`

4. **Combo box handler** (~line 689): Add hide lines (`myFractalControls.setVisible(false); myFractalControls.setManaged(false);`) and a `case` in the show switch

5. **`refreshFromParams()`** (~line 902): Add hide lines in the "hide all" block, and an `else if (params instanceof MyFractalParams p)` block that shows the controls and sets slider values

### Naming conventions

| Concept | Convention | Example |
|---------|-----------|---------|
| Enum value | `UPPER_SNAKE` | `PSEUDO_KLEINIAN` |
| kernelName | `lowercase` | `pseudokleinian` |
| Params class | `PascalCase + Params` | `PseudoKleinianParams` |
| Shader file | `{kernelName}.glsl` | `pseudokleinian.glsl` |
| VBox field | `{camelCase}Controls` | `pseudoKleinianControls` |
| Slider prefix | 2-3 letter abbreviation | `pk` → `pkIterSlider` |

### Verification

After implementation:
1. `mvn compile` — must succeed
2. `mvn javafx:run` — select the new fractal in the dropdown, verify geometry renders
3. Test save/load with File > Save/Load
4. Run visual regression: `mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.TiledRenderTest"` (existing fractals must not change)

## Audio-Reactive Fractals

Système optionnel de fractales audio-réactives. Documentation complète : **[docs/AUDIO_REACTIVE.md](docs/AUDIO_REACTIVE.md)**

- Chargement MP3/WAV/AAC via JavaFX MediaPlayer
- Analyse spectrale temps réel (8 bandes, beat detection, onset detection)
- Modulation des paramètres fractals (power, scale, rotations) + effets GLSL (couleur, glow, FOV, fog)
- **Export offline** : pré-analyse audio complète → rendu frame par frame haute qualité (path tracer, N samples) → MP4 synchronisé
- Instructions de suppression complète dans la doc

**Fichiers** : `audio/AudioReactiveEngine.java`, `audio/AudioPreAnalyzer.java`, `ui/panels/AudioPanel.java`

## Dependencies

- **LWJGL 3.4.1** - OpenGL bindings for GPU rendering
- **JavaFX 25.0.1** - UI framework
- **JavaFX Media** - Audio playback and spectrum analysis (audio-reactive feature)
- **Java 21+** required
- **FFmpeg** (optional) - For MP4 video export and audio pre-analysis (must be in system PATH)

## Viewport HUD & Feedback

- **3D Orientation Compass**: Real-time 3D axes (X, Y, Z) in the top-right corner, showing camera world orientation via quaternion math.
- **Dynamic Speed Meter**: A segmented thrust indicator on the right edge that appears when adjusting movement speed, then fades out.
- **Telemetry Overlay**: Professional technical readout (FOV, Speed) in the bottom-left for monitoring.
- **Focus Ring**: Visual circular feedback (Cyan/Red) at the click position when setting Depth of Field focal distance.
