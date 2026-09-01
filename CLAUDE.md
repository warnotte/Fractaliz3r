# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## IMPORTANT Rules
- **NEVER** include `Co-Authored-By`, AI mentions, Claude mentions, or any reference to AI/LLM in commit messages, code comments, or any generated content. This rule is absolute and permanent.
- Creative feature ideas and roadmap: see **[IDEAS.md](IDEAS.md)**

## Build Commands

```bash
mvn compile              # Build
mvn test                 # Run tests
mvn javafx:run           # Run app (JavaFX)
mvn package              # Package JAR
mvn clean install        # Clean rebuild
mvn clean javafx:jlink package -DskipTests  # Release (jlink)
```

**Visual regression tests** (run after shader/rendering changes):
```bash
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.TiledRenderTest"   # Tiled export
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.ZoomOutTest"       # Zoom-out rendering

# Deterministic golden-image regression + median benchmark (bit-exact reproducible per GPU).
# Goldens are GPU-specific and gitignored (test_regression/); run 'update' once on a good build.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.RenderRegression" -Dexec.args="update"   # write goldens
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.RenderRegression" -Dexec.args="check"    # diff vs goldens (exit 1 on regression)
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.RenderRegression" -Dexec.args="bench"    # median render time per scene
# Pass a navigator manifest as a 2nd arg to validate/bench on fine-DETAIL views instead of global defaults.

# Autonomous "traveller": global view -> fine-detail framing on any fractal (output to nav/, gitignored).
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.FractalNavigator" -Dexec.args="MANDELBULB nav 640x360 12 travel 8 0.6 50"
#   modes: travel (auto-frame -> depth-guided target -> dive -> sharpness sweet-spot) | fly (eased flight -> mp4) | manifest (write detail cameras) | list (explicit cameras)
#   any "name=value" arg overrides a param before travelling, e.g. juliaCx=0.42 juliaCy=0.18

# Deep-zoom detail lab: same cameras, several parameter variants, quantitative surface metrics
# (detail = Laplacian variance, edges%, lum, contrast) -> proves a deep-zoom change instead of eyeballing.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.DeepZoomLab" -Dexec.args="MANDELBOX dzl 480x270 4 nav/mbox_ladder.txt detailLOD=0,2,4"

# Demo presets: build .frac files + render a preview of each so candidates can be judged.
# Cameras come from FractalNavigator sweet spots, not default global framings.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.PresetForge" -Dexec.args="presets presets_preview 960x540 48"

# Autonomous discovery: search Julia-constant space for new fractals worth looking at.
# Boundary constants are found on the CPU (no rendering), then rendered, scored, filtered
# for diversity, and the winners written as .frac. ~180 candidates in ~6 s.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.JuliaProspector" -Dexec.args="prospect 180 320x180 6 8"
```

## Project Architecture

### Core Components

```
org.fractalizer
├── GLSLFractalizerApp.java          # JavaFX entry point with FPS navigation
├── engine/
│   ├── GLSLEngine.java              # GPU compute abstraction (LWJGL/OpenGL)
│   ├── ShaderPreprocessor.java      # GLSL symbol renaming for shader concatenation
│   ├── BlueNoiseGenerator.java      # 64x64 blue noise texture (Mitchell's best-candidate)
│   └── Camera.java                  # Quaternion-based FPS camera (no gimbal lock)
├── fractals/
│   ├── FractalParams.java           # Interface for fractal parameters
│   ├── FractalType.java             # Enum of available fractal types
│   ├── AbstractFractalParams.java   # Base class with common params (camera, lighting, gradient, etc.)
│   ├── GradientPalette.java         # Custom gradient model with ColorStops and GPU texture generation
│   ├── [10 fractal Params classes]  # Mandelbulb, Mandelbox, Menger, KIFS, QuatJulia4D, PolyIFS, Sierpinski, PseudoKleinian, Apollonian, Bristorbrot
│   ├── FractalTerrainParams.java    # Fractal Terrain: fBm heightfield
│   ├── CornellBoxParams.java        # Cornell Box: per-object materials in shader
│   ├── CustomShaderParams.java      # Custom Shader: user GLSL source + dynamic uniforms
│   └── NodeGraphParams.java         # Node Graph: wraps GraphNode tree as fractal params
├── graph/
│   ├── GraphNode.java              # Abstract base for composable fractal operations
│   ├── FractalNode.java            # Leaf: wraps FractalType + per-node params
│   ├── PrimitiveNode.java          # Leaf: 11 SDF geometric primitives
│   ├── CSGNode.java                # Binary: Union/Intersect/Subtract/Morph
│   ├── TransformNode.java          # Unary: 7 modes (Standard/Mirror/Twist/Bend/Taper/Rep/Rep1D)
│   ├── EffectNode.java             # Unary: Erosion/Crystal/Moss
│   ├── MaterialNode.java           # Unary: per-node material overrides
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
│   ├── AnimationTrack.java          # Keyframe track with optional Catmull-Rom spline
│   └── Keyframe.java                # Single keyframe (time, value, easing)
└── ui/
    ├── GLSLFractalizerController.java  # Bridges UI with GLSL engine (parameter caching)
    ├── ViewportHUD.java                # 3D Compass, Speed Meter, Telemetry overlay
    ├── AnimationManager.java           # Manages timeline and keyframe editing
    ├── components/
    │   ├── EnhancedSlider.java         # Pro slider: mouse wheel, precision, lock, inline edit
    │   ├── GradientEditor.java         # Visual gradient editor with draggable color stops
    │   ├── CustomShaderEditor.java     # GLSL editor with @param parsing, dynamic sliders
    │   └── NodeGraphEditor.java        # Visual node graph editor: canvas + detail panel + undo/redo
    └── panels/
        ├── FractalPanel.java           # Fractal type and parameters
        ├── MaterialPanel.java          # Material type, physical props, artistic palettes
        ├── LightingPanel.java          # Light direction and colors
        ├── QualityPanel.java           # Ray steps, DoF, path tracing, adaptive sampling
        ├── PostProcessingPanel.java    # Bloom, tone mapping, color correction
        ├── AudioPanel.java             # Audio-reactive controls + offline video export
        └── ExportPanel.java            # Image/animation export with motion blur
```

### GLSL Shaders (`src/main/resources/shaders/`)

```
shaders/
├── raytracer.glsl         # Main raymarcher (fractal-agnostic)
├── common.glsl            # Common utilities, materials, artistic palettes
├── postprocess.glsl       # Bloom, tone mapping, color correction
└── fractals/              # Individual fractal Distance Estimators
    ├── mandelbulb.glsl, mandelbox.glsl, menger.glsl, kaleidoscopic.glsl
    ├── quaternionjulia4d.glsl, polyhedral.glsl, sierpinski.glsl
    ├── pseudokleinian.glsl, apollonian.glsl, bristorbrot.glsl
    ├── fractalterrain.glsl, cornellbox.glsl
```

## Shader Assembly Pipeline (Summary)

| Mode | Concatenation | Trigger |
|------|---------------|---------|
| **Standard** | `#version 430` + `common.glsl` + `fractal.glsl` + `raytracer.glsl` | Single fractal type |
| **Boolean Ops** | Above + `#define BOOLEAN_OPS` + preprocessed secondary (b_ prefix) | Boolean Operations enabled |
| **Node Graph** | `#version 430` + `common.glsl` + GraphCompiler output + `raytracer.glsl` | FractalType.NODE_GRAPH |
| **Custom Shader** | `#version 430` + `common.glsl` + user source + `raytracer.glsl` | FractalType.CUSTOM_SHADER |

Uniform changes never trigger recompilation — only structural changes require a new shader compile. Full documentation: **[docs/SHADER_PIPELINE.md](docs/SHADER_PIPELINE.md)**

## Node Graph System (Summary)

The Node Graph is the primary UI for fractal editing/composition. Composable tree → single GPU shader.

**Node types:** `FractalNode` (leaf) / `PrimitiveNode` (leaf: 11 SDF shapes) / `CSGNode` (binary: 5 ops — Union/Intersect/Subtract/Morph/Nesting) / `TransformNode` (unary: 7 modes) / `EffectNode` (unary: Erosion/Crystal/Moss) / `MaterialNode` (unary: per-node material overrides via SSBO, `colorMode` 0=palette/1=solid/2=tint, gated by `#define HAS_MATERIALS`).

**GraphCompiler** compiles in 8 phases with unique prefixes (`n0_`, `t0_`, `e0_`, `m0_`, `c0_`, `p0_`) via `ShaderPreprocessor`.

Full documentation: **[docs/NODE_GRAPH.md](docs/NODE_GRAPH.md)**

## Detailed Documentation Index

| Topic | File | Contents |
|-------|------|----------|
| Shader pipeline | [docs/SHADER_PIPELINE.md](docs/SHADER_PIPELINE.md) | Assembly modes, ShaderPreprocessor, uniform lifecycle, recompilation triggers |
| Node graph | [docs/NODE_GRAPH.md](docs/NODE_GRAPH.md) | All node types, GraphCompiler phases, uniforms, animation, serialization, UI |
| Rendering | [docs/RENDERING.md](docs/RENDERING.md) | Blue noise, cone tracing, adaptive sampling, cinematic pipeline, effects, coloring, spline camera, HUD |
| Features | [docs/FEATURES.md](docs/FEATURES.md) | EnhancedSlider, dice randomizer, morph crossfade, custom shader editor, boolean ops, nesting |
| Export | [docs/EXPORT.md](docs/EXPORT.md) | VR/360, tiled rendering, AOV export, mesh export, video encoding, jlink release, visual regression test |
| Audio-reactive | [docs/AUDIO_REACTIVE.md](docs/AUDIO_REACTIVE.md) | Spectrum analysis, beat/onset detection, mappings, offline export pipeline |

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
5. **`vec3 getFactors(OrbitTrap trap)`** — converts traps to 3 coloring factors

### Step 4: Wire into 4 existing files

#### 4a. `FractalType.java` — Add enum value
```java
MY_FRACTAL("Display Name", "kernelname"),  // before TEST_SCENE
```

#### 4b. `GLSLFractalizerController.java` — 2 switch blocks
- **`setFractalType()`** (~line 107): `case MY_FRACTAL -> this.currentParams = new MyFractalParams();`
- **`buildUniforms()`** (~line 884): bind all fractal-specific uniforms

For `vec3`: `uniforms.put("name", new float[]{x, y, z})`. For `mat3`: use `createRotationMatrix()`.

#### 4c. `FractalConfig.java` (in `org.fractalizer.config`) — 2 methods
- **`extractFractalParams()`** — serialize params to map
- **`applyFractalParams()`** — deserialize map to params

Both blocks must go **before** the `TestSceneParams` / `CornellBoxParams` blocks.

#### 4d. `FractalPanel.java` — 5 locations
1. Field declarations (~line 34)
2. `createContent()` (~line 114): add create call + add to children
3. `createMyFractalControls()` method: VBox + EnhancedSliders + callbacks
4. Combo box handler (~line 689): hide/show case
5. `refreshFromParams()` (~line 902): hide all + `else if instanceof` show

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

1. `mvn compile` — must succeed
2. `mvn javafx:run` — select new fractal, verify geometry
3. Test save/load with File > Save/Load
4. Run visual regression test (see Build Commands above)

## Navigation Controls

| Key | Action |
|-----|--------|
| Arrow keys | Move forward/backward/strafe |
| Mouse drag | Look around |
| Q/E | Roll left/right |
| Page Up/Down | Move up/down |
| R | Reset camera |
| Space | Render full quality |
| Scroll wheel | Adjust movement speed |

## Dependencies

- **LWJGL 3.4.1** — OpenGL bindings for GPU rendering
- **JavaFX 25.0.1** — UI framework
- **JavaFX Media** — Audio playback and spectrum analysis
- **Java 21+** required
- **FFmpeg** (optional) — MP4 video export and audio pre-analysis (must be in system PATH)
