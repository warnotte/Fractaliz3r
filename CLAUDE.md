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
├── FractalizerApp.java              # JavaFX entry point with FPS navigation
├── engine/
│   ├── OpenCLEngine.java            # GPU compute abstraction (LWJGL/OpenCL)
│   └── Camera.java                  # Quaternion-based FPS camera (no gimbal lock)
├── fractals/
│   ├── FractalParams.java           # Interface for fractal parameters
│   ├── FractalType.java             # Enum of available fractal types
│   ├── AbstractFractalParams.java   # Base class with common params (camera, lighting, DoF, etc.)
│   ├── MandelbulbParams.java        # Mandelbulb: power, iterations, bailout
│   └── MandelboxParams.java         # Mandelbox: scale, minRadius, fixedRadius, foldingLimit
├── render/
│   ├── TileRenderer.java            # Tile-based rendering to avoid GPU watchdog
│   └── ImageExporter.java           # PNG export utilities
└── ui/
    └── FractalizerController.java   # Bridges UI with render engine, loads all kernels
```

### OpenCL Kernels

Located in `src/main/resources/kernels/`:

```
kernels/
├── common.cl              # Shared utilities + rendering pipeline
└── fractals/
    ├── mandelbulb.cl      # Mandelbulb (power-based spherical folding)
    ├── mandelbox.cl       # Mandelbox (box fold + sphere fold)
    ├── menger.cl          # Menger Sponge (IQ algorithm)
    └── kaleidoscopic.cl   # Kaleidoscopic IFS (reflection-based)
```

**`common.cl`** - Shared utilities and rendering pipeline:
  - Vector operations (normalize3, length3, dot3, cross3)
  - Quaternion operations (rotateByQuaternion, getCameraRay)
  - Constants (RENDER_*, STEP_FACTOR, MIN_EPSILON, etc.)
  - Color utilities (palette, fresnel, iterationColor)
  - DoF helpers (DofSetup, initDofSetup, getDofSampleRay)
  - **Rendering pipeline** (renderByMode, calculateShading, renderBackground, toneMapAndGamma)

**`fractals/*.cl`** - Each fractal defines only:
  - OrbitTraps structure (for coloring data)
  - `fractalDE()` - Full DE with orbit traps
  - `fractalDE_simple()` - Simplified DE for shadows/AO
  - `calcNormal*()`, `calcShadow*()`, `calcAO*()` - Using DE_simple
  - `get*Color()` - Color from orbit traps
  - `render*()` kernel - Calls common rendering pipeline

### Kernel Structure Pattern

Each fractal kernel follows this pattern:

```c
// 1. OrbitTraps struct for coloring data
typedef struct { float plane, sphere, axis, cube; int iterations; } OrbitTraps;

// 2. Full DE with orbit traps (for primary ray hit)
float fractalDE(float3 pos, params..., OrbitTraps* traps) { ... }

// 3. Simple DE without traps (for shadows, AO, normals - ~95% of calls)
float fractalDE_simple(float3 pos, params...) { ... }

// 4. Normal calculation using simple DE (tetrahedron method)
float3 calcNormalTetra(float3 pos, params...) { ... }

// 5. Soft shadows using simple DE
float calcSoftShadow(float3 ro, float3 rd, params...) { ... }

// 6. Ambient occlusion using simple DE
float calcAO(float3 pos, float3 normal, params...) { ... }

// 7. Material color from orbit traps
float3 getOrbitColor(OrbitTraps traps, float3 baseHue) { ... }

// 8. Main render kernel
__kernel void renderFractal(...) { ... }
```

**Why two DE versions?** Performance optimization:
- Full DE calculates orbit traps for rich coloring
- Simple DE only returns distance (much faster)
- Shadows/AO/normals don't need color data, so use simple DE
- For a typical pixel: 1 full DE + ~137 simple DE calls

### Key Design Decisions

1. **Quaternion camera**: Uses quaternions for rotation to avoid gimbal lock, enabling spaceship-like 6DOF navigation inside fractals.

2. **Tile-based rendering**: Images split into 256x256 tiles to avoid GPU watchdog timeouts (~2s limit).

3. **Preview system**: Uses 1/4 resolution and fewer iterations for fast parameter adjustment during navigation.

4. **Modular kernels**: common.cl is loaded first, then fractal-specific kernel. Use `loadKernelFromSources()`.

5. **AbstractFractalParams**: Common parameters (camera, lighting, shadows, AO, DoF, specular, glow) inherited by all fractal types.

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

## Roadmap

### Completed Fractals
- [x] Mandelbulb - Power-based spherical folding
- [x] Mandelbox - Box fold + sphere fold
- [x] Menger Sponge - Recursive cube subdivision (IQ algorithm)
- [x] Kaleidoscopic IFS - Configurable reflection-based IFS

### Planned Improvements
- [x] **Refactor to modular pipeline architecture** (completed - see below)
- [ ] Animation system for parameter interpolation
- [ ] Save/load fractal configurations
- [ ] Video export

---

## Kernel Architecture (Implemented)

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

- **LWJGL 3.3.3** - OpenCL bindings for GPU compute
- **JavaFX 21.0.2** - UI framework
- **Java 21+** required
