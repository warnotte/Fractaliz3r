# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
│   ├── AbstractFractalParams.java   # Base class with common params (camera, lighting, palettes, etc.)
│   ├── MandelbulbParams.java        # Mandelbulb: power, iterations, bailout
│   ├── MandelboxParams.java         # Mandelbox: scale, minRadius, fixedRadius, foldingLimit
│   ├── MengerSpongeParams.java      # Menger Sponge: iterations, scale, offset
│   ├── KaleidoscopicIFSParams.java  # KIFS: scale, offset, fold angles
│   ├── Julia3DParams.java           # 3D Julia: quaternion c parameter
│   └── PolyhedralIFSParams.java     # Polyhedral IFS: symmetry type, scale, rotations, offsets
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
    │   └── EnhancedSlider.java         # Professional UI slider with mouse wheel & precision control
    ├── panels/
    │   ├── FractalPanel.java           # Fractal type and parameters (uses EnhancedSlider)
    │   ├── MaterialPanel.java          # Material type, physical props, and artistic palettes (uses EnhancedSlider)
    │   ├── LightingPanel.java          # Light direction and colors (uses EnhancedSlider)
    │   ├── QualityPanel.java           # Ray steps, DoF, path tracing (uses EnhancedSlider)
    │   ├── PostProcessingPanel.java    # Bloom, tone mapping, color correction (uses EnhancedSlider)
    │   └── ExportPanel.java            # Image/animation export with motion blur
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
    └── polyhedral.glsl    # Polyhedral IFS with Octa/Dodeca/Icosa/Tetra symmetries
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

### Artistic Palette System
Located in `common.glsl`, the system provides 5 professional presets:
1. **Magma / Fire**: Intense warm oranges and reds.
2. **Ice / Ocean**: Deep cold blues and cyans.
3. **Forest / Nature**: Earthy greens and browns.
4. **Cyberpunk / Neon**: Vibrant pinks and purples.
5. **Spectral / Rainbow**: Soft pastel spectrum.

**Parameters**:
- `Palette Index`: Selection of the preset.
- `Color Strength`: Multiplier for color intensity and contrast.
- `Palette Shift`: Global offset to slide colors across the fractal structure.

### Improved Orbit Traps
Major fractals use cumulative "Plane Traps" (weighted sum of absolute coordinates) to ensure rich, non-uniform coloring that reacts dynamically to fractal parameters.

## Cinematic Rendering Pipeline

Fractaliz3r features a full cinematic rendering pipeline:
1. **Volumetric Fog & God Rays**: Physically-based scattering with Henyey-Greenstein phase function and shadow-aware light accumulation.
2. **Procedural Environments**: Dynamic sky types (Clouds, Space, Ocean, Studio) with spatial parallax based on camera movement.
3. **Optics (Lens Effects)**: Realistic camera imperfections including Lens Dirt (dust/spots) and JJ Abrams style anamorphic horizontal flares.
4. **Color Grading**: Procedural LUT styles (Cinema, Vintage, Matrix, Neon, Noir) for instant professional looks.

## Navigation Controls

- **Arrow keys**: Move forward/backward/strafe
- **Mouse drag**: Look around
- **Q/E**: Roll left/right
- **Page Up/Down**: Move up/down
- **R**: Reset camera
- **Space**: Render full quality
- **Scroll wheel**: Adjust movement speed (when not hovering a slider)

## Dependencies

- **LWJGL 3.3.3** - OpenGL bindings for GPU rendering
- **JavaFX 21.0.2** - UI framework
- **Java 21+** required
- **FFmpeg** (optional) - For MP4 video export (must be in system PATH)
