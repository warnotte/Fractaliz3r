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
│   └── CornellBoxParams.java        # Cornell Box: sceneScale (per-object materials in shader)
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

## Dependencies

- **LWJGL 3.3.3** - OpenGL bindings for GPU rendering
- **JavaFX 21.0.2** - UI framework
- **Java 21+** required
- **FFmpeg** (optional) - For MP4 video export (must be in system PATH)
