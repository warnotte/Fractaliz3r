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
├── FractalizerApp.java          # JavaFX entry point with FPS navigation
├── engine/
│   ├── OpenCLEngine.java        # GPU compute abstraction (LWJGL/OpenCL)
│   └── Camera.java              # Quaternion-based FPS camera (no gimbal lock)
├── fractals/
│   ├── FractalParams.java       # Interface for fractal parameters
│   └── MandelbulbParams.java    # Mandelbulb settings + camera reference
├── render/
│   ├── TileRenderer.java        # Tile-based rendering to avoid GPU watchdog
│   └── ImageExporter.java       # PNG export utilities
└── ui/
    └── FractalizerController.java  # Bridges UI with render engine
```

### OpenCL Kernels

Located in `src/main/resources/kernels/`:
- `mandelbulb.cl` - Ray marching with quaternion camera, soft shadows, AO, orbit trap coloring

### Key Design Decisions

1. **Quaternion camera**: Uses quaternions for rotation to avoid gimbal lock, enabling spaceship-like 6DOF navigation inside fractals.

2. **Tile-based rendering**: Images split into 256x256 tiles to avoid GPU watchdog timeouts (~2s limit).

3. **Preview system**: Uses 1/4 resolution and fewer iterations for fast parameter adjustment during navigation.

4. **FractalParams interface**: Each fractal type implements this to define its kernel parameters.

## Navigation Controls

- **Arrow keys**: Move forward/backward/strafe
- **Mouse**: Look around (click image to capture, ESC to release)
- **Q/E**: Roll left/right
- **Page Up/Down**: Move up/down
- **R**: Reset camera
- **Space**: Render full quality
- **Scroll wheel**: Adjust movement speed

## Adding New Fractals

1. Create `src/main/java/org/fractalizer/fractals/NewFractalParams.java` implementing `FractalParams`
2. Create `src/main/resources/kernels/newfractal.cl` with kernel matching the signature in mandelbulb.cl
3. Load kernel in controller: `engine.loadKernel("newfractal", "/kernels/newfractal.cl", "renderNewFractal")`

## Rendering Features

- Soft shadows with configurable softness
- Ambient occlusion (configurable steps and intensity)
- Orbit trap coloring with cosine palette
- Rim lighting
- Distance fog
- Tone mapping and gamma correction
- Background glow effect

## Dependencies

- **LWJGL 3.3.3** - OpenCL bindings for GPU compute
- **JavaFX 21.0.2** - UI framework
- **Java 21+** required
