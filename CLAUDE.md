# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## IMPORTANT Rules
- **NEVER** include `Co-Authored-By`, AI mentions, Claude mentions, or any reference to AI/LLM in commit messages, code comments, or any generated content. This rule is absolute and permanent.

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
│   ├── Julia3DParams.java           # 3D Julia: quaternion c parameter
│   ├── PolyhedralIFSParams.java     # Polyhedral IFS: symmetry type, scale, rotations, offsets
│   ├── SierpinskiParams.java        # Sierpinski Tetrahedron: iterations, scale
│   ├── PseudoKleinianParams.java    # Pseudo-Kleinian: iterations, CSize vec3, Size, DEoffset
│   ├── ApollonianParams.java        # Apollonian Gasket: iterations, scale, foldRadius
│   ├── BristorbrotParams.java       # Bristorbrot: iterations, bailout
│   └── CornellBoxParams.java        # Cornell Box: sceneScale (per-object materials in shader)
├── audio/
│   ├── AudioReactiveEngine.java     # Spectrum analysis, beat/onset detection
│   └── AudioPreAnalyzer.java        # Offline FFT pre-analysis (FFmpeg decode)
├── render/
│   ├── ProgressiveRenderer.java     # Progressive sample accumulation
│   └── FFmpegExporter.java          # MP4 video export via FFmpeg
├── animation/
│   ├── Timeline.java                # Animation timeline with tracks
│   ├── Track.java                   # Keyframe track for a parameter
│   └── Keyframe.java                # Single keyframe (time, value, easing)
└── ui/
    ├── GLSLFractalizerController.java  # Bridges UI with GLSL engine (includes parameter caching)
    ├── AnimationManager.java           # Manages timeline and keyframe editing
    ├── components/
    │   ├── EnhancedSlider.java         # Professional UI slider with mouse wheel & precision control
    │   └── GradientEditor.java         # Visual gradient editor with draggable color stops
    ├── panels/
    │   ├── FractalPanel.java           # Fractal type and parameters (uses EnhancedSlider)
    │   ├── MaterialPanel.java          # Material type, physical props, and artistic palettes (uses EnhancedSlider)
    │   ├── LightingPanel.java          # Light direction and colors (uses EnhancedSlider)
    │   ├── QualityPanel.java           # Ray steps, DoF, path tracing, preview samples (uses EnhancedSlider)
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
    ├── julia3d.glsl
    ├── polyhedral.glsl    # Polyhedral IFS with Octa/Dodeca/Icosa/Tetra symmetries
    ├── sierpinski.glsl    # Sierpinski Tetrahedron (tetrahedral fold IFS)
    ├── pseudokleinian.glsl # Pseudo-Kleinian (box fold + sphere fold, tubular caves)
    ├── apollonian.glsl    # Apollonian Gasket (tetrahedral fold + sphere inversion)
    ├── bristorbrot.glsl   # Bristorbrot (component-wise 3D Mandelbrot)
    └── cornellbox.glsl    # Cornell Box with per-object materials (#define HAS_PER_OBJECT_MATERIAL)
```

## Advanced UI Features

### EnhancedSlider Component
A standard component used for **all** numeric parameters across the application:
- **Mouse Wheel Support**: Scroll over any slider to adjust value.
- **Modifier Keys** (Required for value adjustment to avoid ScrollPane conflicts):
  - `ALT + Scroll`: Normal speed adjustment.
  - `SHIFT + Scroll`: Fine/Precision control (10x slower).
  - `CTRL + Scroll`: Fast movement (10x faster).
- **Automatic Labels**: Displays title and value with configurable precision.
- **Tooltip Help**: Explains shortcuts when hovering.

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

### Cornell Box & Glass Refraction
The Cornell Box scene (`cornellbox.glsl`) uses `#define HAS_PER_OBJECT_MATERIAL` for per-object material assignment via `getObjectMaterial(OrbitTrap)`. Glass refraction in path tracing uses a two-surface approach: entry refraction + interior march using `abs(DE_simple)` + exit refraction, solving the SDF negative-distance problem inside glass bodies.

## Cinematic Rendering Pipeline

Fractaliz3r features a full cinematic rendering pipeline:
1. **Volumetric Fog & God Rays**: Physically-based scattering with Henyey-Greenstein phase function and shadow-aware light accumulation.
2. **Procedural Environments**: Dynamic sky types (Clouds, Space, Ocean, Studio) with spatial parallax based on camera movement.
3. **Optics (Lens Effects)**: Realistic camera imperfections including Lens Dirt (dust/spots) and JJ Abrams style anamorphic horizontal flares.
4. **Color Grading**: Procedural LUT styles (Cinema, Vintage, Matrix, Neon, Noir) for instant professional looks.

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

- **LWJGL 3.3.3** - OpenGL bindings for GPU rendering
- **JavaFX 21.0.2** - UI framework
- **JavaFX Media** - Audio playback and spectrum analysis (audio-reactive feature)
- **Java 21+** required
- **FFmpeg** (optional) - For MP4 video export and audio pre-analysis (must be in system PATH)
