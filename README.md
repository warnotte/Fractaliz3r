# Fractaliz3r GLSL

**Fractaliz3r** is a high-performance, cinematic 3D fractal explorer and renderer built on GLSL shaders with progressive raymarching and full path tracing.

![Version](https://img.shields.io/badge/version-3.0-blue)
![Java](https://img.shields.io/badge/Java-21+-orange)
![License](https://img.shields.io/badge/license-Non--Commercial-red)

<!-- TODO: Add screenshot here -->

---

## Key Features

### Node Graph Compositor

Build complex fractal scenes by composing multiple fractals in a visual node tree:

- **Composable trees**: Combine any number of fractals with CSG operations and coordinate transforms
- **4 CSG operations**: Union, Intersect, Subtract, Morph — all with smooth blending
- **7 transform modes**: Standard (translate/rotate/scale), Mirror, Twist, Bend, Taper, Repetition, Repetition 1D
- **3 surface effects**: Erosion, Crystallization, Moss — per-node, composable, stackable
- **Per-node parameters**: Each fractal node stores its own independent settings
- **Visual editor**: Canvas-based tree view with drag, zoom, context menus, and undo/redo
- **Single-shader compilation**: The entire tree compiles into one GPU shader via `GraphCompiler`

### Fractal Library (13 Types)

- **Mandelbulb** — Power-based 3D Mandelbrot with configurable iterations/bailout
- **Mandelbox** — Box/sphere fold hybrid with scale, radius, and folding controls
- **Menger Sponge** — Classic IFS with scale and offset parameters
- **Kaleidoscopic IFS** — Fold-angle based kaleidoscope with rotation
- **Quaternion Julia 4D** — Full 4D quaternion constant with slice and rotation controls (XW/YW/ZW planes)
- **Polyhedral IFS** — Octahedral, Dodecahedral, Icosahedral, and Tetrahedral symmetries
- **Sierpinski Tetrahedron** — Tetrahedral fold IFS
- **Pseudo-Kleinian** — Box/sphere fold with space repetition (tubular caves)
- **Apollonian Gasket** — Tetrahedral fold + sphere inversion
- **Bristorbrot** — Component-wise 3D Mandelbrot with Julia mode
- **Fractal Terrain** — fBm noise heightfield with configurable octaves
- **Cornell Box** — Classic rendering test scene with per-object materials
- **Custom Shader** — Write your own GLSL distance estimator with `@param` annotations for auto-generated sliders

### Cinematic Rendering

- **Monte Carlo Path Tracing** with NEE + MIS, GGX microfacet BRDF
- **Physical Materials**: Lambertian, Metallic, Glass (two-surface refraction)
- **Volumetric Fog & God Rays**: Henyey-Greenstein scattering
- **Depth of Field**: 9 bokeh presets (Standard, Cinema, Anamorphic, Vintage, Petzval, Miniature, Dream, Night, Prism)
- **Procedural Environments**: Clouds, Deep Space (Legacy/Cinematic/Ultra), Ocean, Studio
- **HDRI Environment Maps**: `.hdr`/`.png`/`.jpg` with rotation and lighting mix
- **Optics**: Anamorphic flares, lens dirt, procedural starbursts
- **Color Grading**: Built-in LUT styles (Cinema, Vintage, Matrix, Neon, Noir)
- **Subsurface Scattering**, reflections, 9 coloring modes, adaptive sampling

### Surface Effects (Per-Node)

- **Erosion Simulation**: Procedural weathering (cracks, hydraulic channels, thermal rounding)
- **Crystallization**: Voronoi-based crystal growth on fractal surfaces
- **Moss/Lichen**: Organic growth favoring crevices and horizontal surfaces
- **Composable**: Effects are per-node via `EffectNode` in the node graph — wrap any subtree, stack multiple effects
- **Domain Distortion**: Global twist/bend/taper/repetition transforms (also available per-node via TransformNode)

### Animation System

- **Timeline** with keyframe tracks and easing curves
- **Spline Camera Paths**: Catmull-Rom interpolation for smooth camera trajectories
- **`@Animatable` annotation** auto-discovers parameters for animation
- **Node graph animation**: Per-node tracks (e.g., `"Mandelbulb".power`, `"Twist".strength`)
- **Motion blur** export with configurable shutter angle

### Audio-Reactive Fractals

- Real-time spectrum analysis (8 bands, beat detection, onset detection)
- Geometry morphing, color shift, glow, FOV pulse, camera shake, warp
- **Offline export**: Pre-analyze audio → render frame-by-frame with path tracing → synchronized MP4

### Export Pipeline

- **Tiled Rendering**: Automatic tile-based rendering for exports up to 16K (15360x8640)
- **Image Export**: PNG and JPEG with optional 360 metadata
- **Depth/Normal AOV Export**: 16-bit depth, 8-bit normals for compositing
- **Video Export**: H.265 HEVC via FFmpeg
- **3D Mesh Export**: GPU-accelerated Marching Cubes to glTF/OBJ/PLY
- **VR/360**: Equirectangular projection with XMP/ExifTool metadata

---

## Getting Started

### Prerequisites

- **Java 21** or higher
- **Maven** for building
- **FFmpeg** (optional): In system PATH for MP4 video export and audio pre-analysis
- **ExifTool** (optional): In system PATH for 360 video metadata injection

### Run

```bash
git clone https://github.com/warnotte/Fractaliz3r.git
cd Fractaliz3r
mvn compile
mvn javafx:run
```

### Build Release

```bash
mvn clean javafx:jlink package -DskipTests
```

Creates a self-contained runtime in `target/image/` with `bin/fractaliz3r.bat`. No JDK needed on target machine.

---

## Controls

| Input | Action |
| :--- | :--- |
| **Arrow Keys** | Move Forward / Backward / Strafe |
| **Mouse Drag** | Look Around |
| **Q / E** | Roll Camera |
| **Page Up / Down** | Move Up / Down |
| **Space** | Render Full Quality |
| **R** | Reset Camera |
| **Scroll Wheel** | Adjust Movement Speed |
| **Middle Click / Ctrl+Click** | Pick Focal Distance (DoF) |
| **F11** | Toggle Fullscreen |
| **F1** | Keyboard Shortcuts |
| **Alt + Scroll** | Adjust slider (normal) |
| **Shift + Scroll** | Adjust slider (fine, 10x slower) |
| **Ctrl + Scroll** | Adjust slider (fast, 10x faster) |

---

## Architecture Overview

```
User → NodeGraphEditor → GraphCompiler → Composite GLSL
                                              ↓
                              GLSLEngine (compile + cache)
                                              ↓
                              GPU (raymarching + path tracing)
                                              ↓
                              Viewport / Export Pipeline
```

The rendering pipeline assembles GLSL shaders at runtime from modular source files. The node graph tree is compiled into a single fragment shader that defines the distance estimator. All rendering — raymarching, lighting, path tracing, post-processing — is GPU-side.

---

## Technical Stack

- **Core**: Java 21 (JPMS Modules)
- **Graphics**: OpenGL 4.3+ via [LWJGL 3](https://www.lwjgl.org/)
- **UI**: JavaFX with custom dark theme
- **Serialization**: Gson for `.frac` configuration files
- **Video**: FFmpeg (H.265 HEVC) & ExifTool integration
- **3D Export**: Marching Cubes to glTF 2.0 / Wavefront OBJ / PLY

---

## Documentation

- **[docs/SHADER_PIPELINE.md](docs/SHADER_PIPELINE.md)** — How GLSL shaders are assembled and sent to the GPU
- **[docs/NODE_GRAPH.md](docs/NODE_GRAPH.md)** — Node graph system: architecture, compiler, animation, serialization
- **[docs/AUDIO_REACTIVE.md](docs/AUDIO_REACTIVE.md)** — Audio-reactive fractals: spectrum analysis, beat detection, offline export

---

## License

This project is provided for **non-commercial use only**. You are free to use, study, and modify the code, but commercial exploitation, redistribution for profit, or inclusion in commercial products is strictly prohibited.
