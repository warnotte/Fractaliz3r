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
│   ├── AbstractFractalParams.java   # Base class with common params (camera, lighting, DoF, motion blur, etc.)
│   ├── MandelbulbParams.java        # Mandelbulb: power, iterations, bailout
│   ├── MandelboxParams.java         # Mandelbox: scale, minRadius, fixedRadius, foldingLimit
│   ├── MengerSpongeParams.java      # Menger Sponge: iterations, scale, offset
│   ├── KaleidoscopicIFSParams.java  # KIFS: scale, offset, fold angles
│   ├── Julia3DParams.java           # 3D Julia: quaternion c parameter
│   └── PseudoKleinianParams.java    # Pseudo-Kleinian: size, cSize, julia
├── render/
│   ├── ProgressiveRenderer.java     # Progressive sample accumulation
│   └── FFmpegExporter.java          # MP4 video export via FFmpeg
├── animation/
│   ├── Timeline.java                # Animation timeline with tracks
│   ├── Track.java                   # Keyframe track for a parameter
│   └── Keyframe.java                # Single keyframe (time, value, easing)
└── ui/
    ├── GLSLFractalizerController.java  # Bridges UI with GLSL engine
    ├── AnimationManager.java           # Manages timeline and keyframe editing
    ├── panels/
    │   ├── FractalPanel.java           # Fractal type and parameters
    │   ├── LightingPanel.java          # Light direction, colors, intensity
    │   ├── QualityPanel.java           # Ray steps, DoF, path tracing
    │   ├── ExportPanel.java            # Image/animation export with motion blur
    │   └── ...
    └── timeline/
        └── TimelineWidget.java         # Visual timeline editor
```

### GLSL Shaders

Located in `src/main/resources/shaders/`:

```
shaders/
├── raytracer.glsl         # Main raymarcher with all fractals (uber-shader)
├── accumulate.glsl        # Progressive sample accumulation
└── display.glsl           # Tone mapping, post-processing, final output
```

**`raytracer.glsl`** - Main rendering shader:
  - All fractal Distance Estimators (DE) in one file
  - Fractal selection via uniform `u_fractalType`
  - Ray marching with soft shadows and AO
  - Orbit trap coloring
  - DoF (Depth of Field) support
  - Path tracing option

**`accumulate.glsl`** - Progressive rendering:
  - Accumulates samples over time
  - Weighted averaging for smooth convergence

**`display.glsl`** - Final output:
  - Tone mapping (ACES, Reinhard, etc.)
  - Gamma correction
  - Vignette, chromatic aberration
  - HDRI environment blending

### Shader Structure Pattern

The uber-shader uses a switch on fractal type:

```glsl
// Distance Estimator dispatch by fractal type
float sceneSDF(vec3 pos, out OrbitTrap trap) {
    switch (u_fractalType) {
        case 0: return mandelbulbDE(pos, trap);
        case 1: return mandelboxDE(pos, trap);
        case 2: return mengerDE(pos, trap);
        // ... more fractals
    }
}

// Ray marching loop
float rayMarch(vec3 ro, vec3 rd, out OrbitTrap trap) {
    float t = 0.0;
    for (int i = 0; i < u_maxSteps; i++) {
        vec3 p = ro + rd * t;
        float d = sceneSDF(p, trap);
        if (d < u_epsilon) return t;
        t += d * u_stepFactor;
    }
    return -1.0;
}
```

**Performance notes:**
- Uber-shader avoids recompilation when switching fractals
- Simple DE variant for shadows/AO (no orbit trap overhead)
- Progressive rendering accumulates samples over frames

### Key Design Decisions

1. **Quaternion camera**: Uses quaternions for rotation to avoid gimbal lock, enabling spaceship-like 6DOF navigation inside fractals.

2. **Progressive rendering**: Samples accumulated over multiple frames for high quality without blocking UI.

3. **Uber-shader**: All fractals in one shader, selected by uniform. Avoids shader switching overhead.

4. **AbstractFractalParams**: Common parameters (camera, lighting, shadows, AO, DoF, motion blur) inherited by all fractal types.

## Navigation Controls

- **Arrow keys**: Move forward/backward/strafe
- **Mouse drag**: Look around
- **Q/E**: Roll left/right
- **Page Up/Down**: Move up/down
- **R**: Reset camera
- **Space**: Render full quality
- **Scroll wheel**: Adjust movement speed

## Adding New Fractals

1. **Add enum value** in `FractalType.java`:
   ```java
   NEW_FRACTAL("Display Name", "kernelname")
   ```

2. **Create params class** extending `AbstractFractalParams`:
   ```java
   public class NewFractalParams extends AbstractFractalParams {
       // Fractal-specific parameters only
       private float myParam;

       @Override
       public FractalType getType() { return FractalType.NEW_FRACTAL; }

       @Override
       public int setKernelParams(OpenCLEngine engine, String kernelName, int startIndex) {
           int idx = startIndex;
           idx += setCommonKernelParams(engine, kernelName, idx);  // Camera, FOV
           // Set fractal-specific params
           engine.setKernelArgFloat(kernelName, idx++, myParam);
           idx += setLightingKernelParams(engine, kernelName, idx);  // Lighting, shadows, AO, DoF
           return idx - startIndex;
       }
   }
   ```

3. **Create kernel** in `src/main/resources/kernels/newfractal.cl`:
   - Follow the kernel structure pattern above
   - Implement DE with orbit traps + simple DE
   - Match kernel signature with params order

4. **Load kernel** in `FractalizerController.loadAllKernels()`:
   ```java
   engine.loadKernelFromSources("kernelname", "renderNewFractal",
       "/kernels/common.cl", "/kernels/newfractal.cl");
   ```

5. **Add switch case** in `FractalizerController.setFractalType()`:
   ```java
   case NEW_FRACTAL:
       this.currentParams = new NewFractalParams();
       break;
   ```

6. **Add UI controls** in `FractalizerApp.createFractalPanel()`:
   - Create VBox for fractal-specific controls
   - Add to ComboBox items
   - Handle visibility toggling

## Rendering Features

- Soft shadows with configurable softness and steps
- Ambient occlusion (configurable steps and intensity)
- Orbit trap coloring with cosine palette
- Specular highlights with Fresnel
- Rim lighting
- Distance fog
- Depth of Field (thin-lens model)
- Multiple render modes (Final, Normals, Depth, AO, Shadows, Diffuse, Specular, Orbit Trap, Iterations)
- Tone mapping and gamma correction
- Background glow effect with stars
- **Quality Multiplier** for ultimate detail when exploring close to surfaces
- **Dynamic image view** that fills available space with correct aspect ratio

## Quality Multiplier System

The Quality Multiplier allows for ultimate precision when exploring fractals at any distance from the surface:

### Parameters Affected
- **Effective Ray Steps**: `maxRaySteps * qualityMultiplier` (more iterations)
- **Epsilon**: `baseEpsilon / qualityMultiplier` (smaller hit threshold)
- **Step Factor**: `STEP_FACTOR / (1 + qualityMultiplier * 0.5)` (smaller steps)
- **Fractal Iterations** (Kaleidoscopic): Increased near surface for high quality

### Quality Levels
| Level | Multiplier | Description |
|-------|------------|-------------|
| Fast Preview | 0.5x | Quick navigation |
| Normal | 1.0x | Default quality |
| High | 2.0x | Better detail |
| Ultra | 3.0x | Fine detail |
| Ultimate | 5.0x | Maximum precision (slow) |

### Implementation
- `AbstractFractalParams.qualityMultiplier` - Parameter passed to all kernels
- All 4 fractal kernels use quality-scaled ray marching
- UI slider in Quality tab (0.5x to 5.0x)
- Quality presets include multiplier adjustment

## Kaleidoscopic IFS Algorithm

The Kaleidoscopic IFS (KIFS) fractal uses conditional reflections to create Sierpinski-like structures.

### Algorithm (from Syntopia blog)
```c
// Classic KIFS folding - creates tetrahedral/kaleidoscopic symmetry
for (n = 0; n < maxIterations; n++) {
    // Fold 1: plane with normal (1, 1, 0)
    if (z.x + z.y < 0.0f) { z.xy = -z.yx; }  // Negate AND swap

    // Fold 2: plane with normal (1, 0, 1)
    if (z.x + z.z < 0.0f) { z.xz = -z.zx; }

    // Fold 3: plane with normal (0, 1, 1)
    if (z.y + z.z < 0.0f) { z.yz = -z.zy; }

    // Scale and translate
    z = z * scale - offset * (scale - 1.0f);
}

// Distance estimation
return length(z) * pow(scale, -n);
```

### Key Parameters
| Parameter | Default | Description |
|-----------|---------|-------------|
| Scale | 2.0 | Classic Sierpinski scale |
| Offset | 3.0 | Translation offset (critical!) |
| Fold Angle X/Y | 0° | Optional rotation for variations |
| Iterations | 15 | Fractal depth |

### Presets
- **Sierpinski**: Scale=2.0, Offset=3.0, Rotation=0°
- **Variation 1**: Scale=2.0, Offset=3.0, Rotation X=10°, Y=5°
- **Variation 2**: Scale=2.2, Offset=2.8, Rotation X=15°

### Important Notes
- The folding is `z.xy = -z.yx` (negate AND swap), NOT just swap
- Offset parameter is critical - 3.0 gives classic Sierpinski
- Rotation angles should be small (-30° to 30°) to avoid instability

## Roadmap

### Completed Fractals
- [x] Mandelbulb - Power-based spherical folding
- [x] Mandelbox - Box fold + sphere fold
- [x] Menger Sponge - Recursive cube subdivision (IQ algorithm)
- [x] Kaleidoscopic IFS - Configurable reflection-based IFS

### Recent Improvements
- [x] **GLSL migration** from OpenCL for better compatibility
- [x] **Unified Material System**: Supports Lambertian, Metallic, and Glass materials with physical properties (roughness, metalness, IOR).
- [x] **High-Quality Color Stability**: Fixed bug where colors faded or shifted at high Quality Multiplier settings.
- [x] **Post-Processing Enhancements**: Added Saturation control and dynamic vibrance boost for high sample counts.
- [x] **Unified Material Presets**: Intuitive presets for common materials (Gold, Silver, Crystal, etc.) that update all physical parameters.
- [x] **Animation system** with timeline, keyframes, and easing
- [x] **Motion blur** for animation export (shutter angle 0-360°)
- [x] **Video export** via FFmpeg (MP4 with CRF quality control)
- [x] **MP4 on cancel** - creates video from rendered frames even when cancelled
- [x] **Quality Multiplier system** for ultimate detail at any distance
- [x] **Auto Full Quality** enabled by default

### Planned Improvements
- [ ] Save/load fractal configurations
- [ ] More easing functions for keyframes
- [ ] Audio-reactive animation

---

## Animation System

The animation system allows keyframe-based animation of any fractal parameter.

### Components
- **Timeline** (`animation/Timeline.java`) - Manages time, FPS, and tracks
- **Track** (`animation/Track.java`) - Keyframes for a single parameter
- **Keyframe** (`animation/Keyframe.java`) - Time, value, easing function
- **AnimationManager** (`ui/AnimationManager.java`) - UI integration
- **TimelineWidget** (`ui/timeline/TimelineWidget.java`) - Visual editor

### Animatable Parameters
- Camera position (X, Y, Z)
- Camera rotation (quaternion)
- Fractal-specific params (power, scale, iterations, etc.)
- Lighting (direction, colors, intensity)
- DoF (focal distance, aperture)

### Usage
1. Navigate to desired camera position
2. Click "Add Keyframe" in Animation panel
3. Move timeline, adjust parameters, add more keyframes
4. Play animation or export to video

---

## Motion Blur

Motion blur simulates camera shutter for smoother animation.

### How It Works
- **Per-sample temporal jitter**: Each sample rendered at slightly different time
- Time offset randomly distributed within shutter window
- Samples accumulate naturally, creating blur

### Shutter Angle
| Angle | Effect |
|-------|--------|
| 0° | No blur (sharp frames) |
| 90° | Subtle blur |
| 180° | Cinematic film standard |
| 270° | Heavy blur |
| 360° | Maximum blur (full frame) |

### Implementation
- `AbstractFractalParams.shutterAngle` - 0 to 360 degrees
- `GLSLFractalizerController.exportAnimationFrameWithMotionBlur()` - Jitters time per sample
- `ExportPanel` - UI spinner in Animation export section

---

## Video Export

Animation export supports PNG sequence and MP4 video creation.

### Features
- **PNG sequence**: Individual frames as `frame_00000.png`, etc.
- **MP4 export**: Via FFmpeg (must be in PATH)
- **CRF quality**: 0 (lossless) to 51 (worst), default 23
- **Cancel recovery**: MP4 created from whatever frames were rendered

### FFmpeg Integration
- Auto-detected from system PATH
- Status shown in Export panel (version or "not found")
- Creates MP4 in-place after PNG export completes

---

## Legacy: OpenCL Kernel Architecture

### Problem Solved: Code Duplication

Analysis of the original 4 fractal kernels revealed **~70% duplicated code**:

| Component | Lines per kernel | Identical across kernels? |
|-----------|-----------------|---------------------------|
| DoF setup | ~30 lines | 100% identical |
| Ray marching loop | ~40 lines | ~95% identical |
| Shading (diffuse/specular) | ~30 lines | 100% identical |
| Render mode switch | ~50 lines | 100% identical |
| Background rendering | ~25 lines | 100% identical |
| Tone mapping/gamma | ~5 lines | 100% identical |
| **Total duplicated** | **~180 lines** | **Per kernel** |

Only **fractal-specific code** differs:
- Distance Estimator (DE full + DE_simple)
- Orbit trap structure
- Color from orbit trap mapping

This duplication caused the DoF bug in Menger Sponge - when rewriting, DoF was forgotten.

### Industry Best Practices Research

Based on research from [Mandelbulber](https://github.com/buddhi1980/mandelbulber2), [Fragmentarium](https://github.com/Syntopia/Fragmentarium), and [Inigo Quilez's work](https://iquilezles.org/articles/raymarchingdf/):

1. **Mandelbulber** (459 formula files):
   - Uses PHP code generation to auto-generate OpenCL kernels
   - Formulas stored separately, combined at compile time
   - Function pointers in CPU code mapped to generated OpenCL

2. **Fragmentarium** (GLSL):
   - Uses `#include` directives with common raytracer
   - Fractals provide only `float DE(vec3 pos)` function
   - Raytracer is "fractal-agnostic" - calls DE without knowing implementation

3. **OpenCL Limitation**:
   - [No function pointers allowed](https://stackoverflow.com/questions/7391166/does-opencl-support-function-pointers)
   - Workarounds: macros, code generation, or uber-shaders

### Solution: Function-Based Common Pipeline

We chose a **function-based approach** over macro injection for better maintainability:

**`common.cl`** now includes rendering pipeline functions:
```c
// DoF helpers
DofSetup initDofSetup(camPos, camQuat, fov, uv, focalDistance, aperture, dofEnabled, dofSamples);
void getDofSampleRay(dof, sampleIdx, pixelX, pixelY, aperture, dofEnabled, &rayOrigin, &rayDir);

// Shading helpers
float3 calculateShading(baseColor, normal, light, viewDir, ...);
float3 toneMapAndGamma(col);
float3 applyFog(col, fogColor, distance, density);
float calcSpecular(normal, light, viewDir, power, intensity);

// Render mode dispatcher
float3 renderByMode(renderMode, baseColor, normal, light, viewDir, ...);

// Background with glow and stars
float3 renderBackground(renderMode, rayDir, minDist, glowIntensity, baseHue, lightCol, ambientCol);
```

**Each fractal kernel** (e.g., `fractals/mandelbulb.cl`):
```c
// Only fractal-specific code:
typedef struct { ... } OrbitTraps;

float fractalDE(...) { ... }
float fractalDE_simple(...) { ... }
float3 calcNormal*(...) { ... }
float calcShadow*(...) { ... }
float calcAO*(...) { ... }
float3 get*Color(...) { ... }

// Kernel calls common functions
__kernel void renderMandelbulb(...) {
    DofSetup dof = initDofSetup(...);

    for (int sampleIdx = 0; sampleIdx < dof.numSamples; sampleIdx++) {
        getDofSampleRay(dof, sampleIdx, ...);
        // Ray march with fractal-specific DE
        // Calculate shadow/AO with fractal-specific functions

        // Use common rendering pipeline:
        sampleColor = renderByMode(renderMode, baseColor, normal, ...);
    }
}
```

### Benefits Achieved

1. **No more forgotten features** - DoF/shading/background automatically consistent
2. **Single point of maintenance** - Fix shading in common.cl, all fractals benefit
3. **Reduced code per fractal** - ~250 lines instead of ~450 (45% reduction)
4. **Clear separation** - Fractal math vs rendering pipeline
5. **Type safety** - No macro complexity, normal function calls

---

## Dependencies

- **LWJGL 3.3.3** - OpenGL bindings for GPU rendering
- **JavaFX 21.0.2** - UI framework
- **Java 21+** required
- **FFmpeg** (optional) - For MP4 video export (must be in system PATH)
