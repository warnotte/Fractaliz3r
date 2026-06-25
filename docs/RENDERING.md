# Rendering & Coloring Pipeline

Detailed documentation of the rendering, coloring, and quality systems in Fractaliz3r.

---

## Visual Gradient Editor

The coloring system uses a GPU-based 1D texture (256x1, RGB32F) driven by a visual gradient editor with draggable color stops.

**Architecture**: `GradientPalette` (model) → `toTextureData()` → `GLSLEngine.updatePaletteTexture()` (GL_TEXTURE1) → shader sampling via `getPresetPalette(t)` (fract, cyclic) and `getSmoothPalette(t)` (clamp, for environments).

**10 Built-in Presets**: Magma, Ice, Forest, Neon, Spectral, Sunset, Ocean, Aurora, Pastel, Monochrome.

**Parameters**:
- `Color Strength`: Multiplier for color intensity and contrast.
- `Palette Shift`: Global offset to slide colors across the fractal structure.

## Coloring Modes

13 coloring modes in `applyMaterial()` (MaterialPanel "Coloring" ComboBox). Modes 0–8 are orbit-trap-based; modes 9–12 are **geometry-based** (scale-invariant — colors derive from surface properties, not iteration history).

### Orbit-Trap Modes (0–8)

| Mode | Name | Description |
|------|------|-------------|
| 0 | **Standard** | Flow + depth (default) |
| 1 | **Iteration Bands** | Sharp discrete color bands by iteration count |
| 2 | **Distance** | Structural/proximity based |
| 3 | **Angular** | atan2 spiral patterns |
| 4 | **Blend** | Equal mix of structural + flow + depth |
| 5 | **Contour** | High-frequency sine stripes (topographic) |
| 6 | **HSV Direct** | Factors → H/S/V independently (no palette) |
| 7 | **Dual Palette** | Two palette lookups blended by depth |
| 8 | **Neon** | Sharp hue bands, high saturation glow |

### Scale-Invariant Modes (9–12)

These bypass orbit traps entirely. `applyMaterial()` receives `hitPos`, `normal`, and `rayDir` to compute colors from geometry.

| Mode | Name | How It Works | Why Scale-Invariant |
|------|------|-------------|---------------------|
| 9 | **Normal Map** | `dot(normal, lightVec) * 0.5 + 0.5` → palette | Normals are unit vectors, always [0,1] |
| 10 | **Triplanar** | Dual-octave `triplanarNoise()` blended by normal weights → palette + edge darkening | Position-proportional detail at every scale |
| 11 | **Curvature** | Laplacian of DE via 6 `DE_simple` samples, eps scaled by camera distance → palette | Normalized by adaptive epsilon |
| 12 | **Fresnel** | `pow(1 - abs(dot(normal, rayDir)), 2)` → palette | Dot product always [0,1] |

**Parameters**: `Color Strength` adjusts intensity for all modes (also controls noise frequency for Triplanar). `Palette Shift` offsets the palette lookup.

## Improved Orbit Traps

Major fractals use cumulative "Plane Traps" (weighted sum of absolute coordinates) to ensure rich, non-uniform coloring that reacts dynamically to fractal parameters.

## Sample Controls

Three separate sample counts control rendering quality at different stages:
- **Preview Samples** (QualityPanel, 16-4096, default 64): Controls `fullSamples` in the controller — the number of iterations for Auto Full Quality preview rendering.
- **Export Samples** (ExportPanel Image section, 16-1024, default 128): Passed directly to `exportToPNG(file, samples, progress)` for single image export.
- **Animation Samples** (ExportPanel Animation section, 1-128, default 16): Per-frame samples for animation sequence export.

---

## Blue Noise Sampling

AA jitter and DoF aperture sampling use a 64x64 blue noise texture instead of PCG white noise. Blue noise is spatially uniform — at equal sample counts, the image looks 2-3x cleaner to the human eye (no random clumping).

- **Texture**: 64x64 RG32F, generated at startup by `BlueNoiseGenerator` (Mitchell's best-candidate algorithm). Bound to `GL_TEXTURE2` during raytracer pass. `GL_NEAREST` filter, `GL_REPEAT` wrap (seamless tiling).
- **Temporal animation**: `fract(bn + sampleIndex * φ)` (golden ratio) decorrelates each frame while preserving the blue noise spectrum.
- **AA jitter**: `texelFetch(blueNoiseTex, ivec2(gl_FragCoord.xy) % 64, 0).rg` replaces `random2(seed)`.
- **DoF aperture**: Same texture, offset texel `(+37, +17)` for decorrelation from jitter. Drives the disk sample (`r`, `theta`) with polygon bokeh shaping preserved.
- **PCG seed consistency**: 2 dummy `random(seed)` calls at each replacement site keep the downstream PCG chain identical — zero regression on path tracing, volumetric fog, soft shadows.
- **Not used for**: path tracing bounces, volumetric fog, SSS, GGX — per-bounce randomness doesn't benefit from spatial blue noise.

---

## Raymarcher Improvements (Cone Tracing)

Four independently toggleable raymarcher enhancements in QualityPanel "Raymarcher" TitledPane. Each feature gates on its own condition — zero overhead when disabled.

- **Cone Tracing** (`pixelRadius`): Pixel-aware adaptive epsilon. `epsilon = max(MIN_EPSILON, pixelRadius * distance)` where `pixelRadius = tan(fov/2) / (height/2)`. Replaces legacy `computeAdaptiveEpsilon()` in `rayMarch`, `rayMarchSimple`, and `calcNormal`. Tiled export paths override `pixelRadius` using full image height (not tile height).
- **Fudge Factor** (0.1–2.0, default 1.0): DE step multiplier. `step *= fudgeFactor`. Applied in `rayMarch`, `rayMarchSimple`, and `calcShadow`. Values >1 = faster but riskier, <1 = more conservative.
- **Surface Refinement** (0–8 steps, default 4): Bisection of the last step interval after hit. `refineSurface()` detects **SDF vs fractal DE** at the hit point: for true SDFs (negative distance inside), binary search for zero-crossing; for fractal DEs (always non-negative), **ternary search** to minimize DE along the ray (converges to the true surface). After refinement, when cone tracing is active, **6 extra sphere-tracing steps** converge from the cone epsilon (~0.003) to `MIN_EPSILON` — each step reduces DE by ~10x. Re-evaluates full `sceneDE` at the final position for correct orbit traps and coloring.
- **Step Relaxation** (0.0–1.0, default 0.0): Keinert 2014 over-relaxation. `omega = 1 + stepRelaxation`. On overshoot (`prevD + d < candidateStep`): backstep, reset to conservative stepping. Applied in `rayMarch` and `rayMarchSimple`.

**Parameters** (in `AbstractFractalParams`, serialized in `EffectsConfig`): `coneTracingEnabled` (bool, default true), `fudgeFactor` (float), `refinementSteps` (int), `stepRelaxation` (float).

---

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

---

## Cinematic Rendering Pipeline

1. **Volumetric Fog & God Rays**: Physically-based scattering with Henyey-Greenstein phase function and shadow-aware light accumulation.
2. **Procedural Environments**: Dynamic sky types (Clouds, Space, Ocean, Studio) with spatial parallax based on camera movement.
3. **Optics (Lens Effects)**: Realistic camera imperfections including Lens Dirt (dust/spots) and JJ Abrams style anamorphic horizontal flares.
4. **Color Grading**: Procedural LUT styles (Cinema, Vintage, Matrix, Neon, Noir) for instant professional looks.
5. **Monte Carlo Path Tracing**: NEE + MIS, GGX microfacet BRDF.
6. **Advanced Lighting**: Spot light with configurable cone angle and edge softness.

---

## Cornell Box & Glass Refraction

The Cornell Box scene (`cornellbox.glsl`) uses `#define HAS_PER_OBJECT_MATERIAL` for per-object material assignment via `getObjectMaterial(OrbitTrap)`. Glass refraction in path tracing uses a two-surface approach: entry refraction + interior march using `abs(DE_simple)` + exit refraction, solving the SDF negative-distance problem inside glass bodies.

---

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

---

## Domain Distortion (Legacy — Superseded by Node Graph TransformNode)

> **Note:** The global domain distortion system (QualityPanel) still works for single-fractal modes but is functionally superseded by the Node Graph's `TransformNode`, which provides the same 5 transform types (Twist, Bend, Taper, Repetition, Repetition 1D) plus Standard and Mirror — applied per-node with composable stacking. See **[NODE_GRAPH.md](NODE_GRAPH.md)** (TransformNode section).

The legacy system applies space-warping globally to `pos` BEFORE DE evaluation via `applyDomainDistortion()` in `common.glsl`. Parameters in `AbstractFractalParams` (`distortionEnabled`, `distortionType`, `distortionAxis`, `distortionStrength`, `distortionFrequency`, `distortionOffset`), serialized in `EffectsConfig`. UI in QualityPanel "Domain Distortion" TitledPane.

---

## Spline Camera Paths (Catmull-Rom)

AnimationTrack supports an opt-in `splineInterpolation` mode. When enabled, `getValue()` uses Catmull-Rom interpolation (4 control points) instead of linear (2 points), producing smooth curved trajectories through keyframes.

- **Enabled by default** on `camPos` and `camQuat` tracks (camera position and rotation).
- **Formula**: Standard Catmull-Rom: `q(t) = 0.5 * ((2*P1) + (-P0+P2)*t + (2*P0-5*P1+4*P2-P3)*t^2 + (-P0+3*P1-3*P2+P3)*t^3)`
- **Easing + Spline**: Easing modulates `t` before spline evaluation. LINEAR = constant speed along curve, EASE_IN_OUT = decelerate at keyframes.
- **Boundary clamping**: P0 = P1 when no prior keyframe, P3 = P2 when no next keyframe.
- **Quaternion normalization**: float[] of length 4 are auto-normalized after spline to prevent drift.
- **Serialization**: `splineInterpolation` boolean in `TrackConfig` (default false = backward compatible).
- **UI**: Green "S" indicator on each track in TimelineWidget. Click to toggle. Dim gray when off.

---

## Viewport HUD & Feedback

- **3D Orientation Compass**: Real-time 3D axes (X, Y, Z) in the top-right corner, showing camera world orientation via quaternion math.
- **Dynamic Speed Meter**: A segmented thrust indicator on the right edge that appears when adjusting movement speed, then fades out.
- **Telemetry Overlay**: Professional technical readout (FOV, Speed) in the bottom-left for monitoring.
- **Focus Ring**: Visual circular feedback (Cyan/Red) at the click position when setting Depth of Field focal distance.

---

## Render Quality Finishes

Display-output and shading refinements (`postprocess.glsl`, `raytracer.glsl`):

- **Ordered dithering (4x4 Bayer)**: applied as the last step before the 8-bit quantization (which happens CPU-side via `floor(c*255)`). `color += bayerDither(gl_FragCoord.xy) / 255.0` turns the truncation into spatially-stable stochastic rounding — removes gradient banding without visible noise.
- **Display-referred sharpening**: the unsharp mask runs **after** tone map + gamma (via `toDisplayReferred()`), not on raw linear HDR — sharpens perceptual values instead of over-shooting highlights / crushing shadows.
- **Per-channel subsurface scattering**: `calcSSS()` returns a `vec3` with per-channel absorption `vec3(6, 8, 11.2)` (red penetrates deeper than blue) — a subtle warm tint in thin back-lit regions while preserving overall intensity (green = the previous scalar `8.0`).
- **Ambient floor**: `ambient = getAmbientLighting(normal) * baseColor * mix(0.2, 1.0, ao)` — deep crevices keep 20% ambient instead of crushing to black (multi-bounce fill approximation).
- **NEE soft-shadow consistency**: in all four path-trace blocks (modern/classic × metallic/lambertian) the jittered sun-disc sample drives **both** the shadow ray and the BRDF (`NdotL`/`H`/`NdotH`), so visibility and shading agree. Hard-shadow output is unchanged (RNG consumption preserved).

## Sample Accumulation Batching

`GLSLEngine.renderSamples(uniforms, count)` renders a whole batch in one GL-thread pass: constant pass state (FBO, blend, textures, SSBO, user uniforms) is bound **once** instead of per sample, with **no per-sample `glFinish`** — the GPU pipelines all samples and synchronises only at readback. Used by progressive preview and still-frame export. Note: export wall-clock is GPU-bound (the raymarch dominates 93–99%; readback / pixel conversion / PNG encode are negligible), so this is mainly a CPU-overhead and sync cleanup, not a large export speedup.

## Test Harnesses: Regression, Benchmark, Traveller

Two headless tools in `org.fractalizer.test` (invocations in **[CLAUDE.md](../CLAUDE.md)** Build Commands):

- **`RenderRegression`** — renders fixed scenes deterministically (bit-exact reproducible per GPU, self-diff 0). `update` writes golden images, `check` diffs against them and fails on any change beyond a small tolerance, `bench` reports median render time. Goldens are GPU-specific and gitignored (`test_regression/`). Accepts a navigator manifest to validate/bench on fine-**detail** views instead of default global cameras.
- **`FractalNavigator`** — autonomous global → fine-detail camera "traveller", validated across ~15 fractal types + node-graph `.frac` presets:
  - **auto-frame** the global view (backs off oversized fractals like Mandelbox);
  - **depth-guided target**: scan a 3×3 view-plane grid of aim points (depth AOV) and pick the most-detailed solid patch — skips hollow cores (Menger) and empty gaps;
  - **dive** along the view axis in shrinking steps;
  - **sweet-spot selection**: score each step (`detail × coverage-band × centering`; detail = variance of the Laplacian over depth-masked surface pixels) and keep the best, avoiding the smooth close-up washout.
  - Modes: `travel` | `fly` (eased flight global→sweet-spot → PNG sequence → mp4) | `manifest` (write per-fractal sweet-spot cameras) | `list` (explicit cameras). Output to `nav/` (gitignored).
