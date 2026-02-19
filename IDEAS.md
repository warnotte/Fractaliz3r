# Ideas

Creative feature ideas for Fractaliz3r. Status: IDEA (not started), IN PROGRESS, DONE, REJECTED.

## 1. Fractal Portals — Non-Euclidean Geometry
**Status:** IDEA

Holes in space that teleport rays. Walk through a Mandelbulb and emerge inside a Mandelbox. Implemented directly in the raymarcher by detecting portal surfaces (sphere/plane) and remapping ray origin + direction on hit. Each portal connects two fractal parameter sets or types.

## 2. Erosion Simulation
**Status:** DONE

Procedural erosion applied to any fractal DE. Three layers (weathering cracks, hydraulic channels, thermal rounding) with proximity gating and lightweight shadow/AO variant. Animated via global timeline tracks.

## 3. Boolean Operations Between Fractals
**Status:** DONE

CSG union/intersection/subtraction/nesting of two fractal distance fields. Carve a Menger Sponge out of a Mandelbulb, merge them smoothly, tile micro-fractals on a surface, etc. Solved via Java-side `ShaderPreprocessor` that renames all local GLSL symbols in the secondary fractal with a `b_` prefix before shader concatenation — zero conflicts. `#ifdef BOOLEAN_OPS` in raytracer.glsl for clean separation. On-demand compilation with caching. Smooth blend via `smin`/`smax`. Euler XYZ rotation of the secondary fractal. Excludes Fractal Terrain, Cornell Box, and Test Scene. Also serves as the foundation for user-written custom DE shaders (runtime GLSL compilation pipeline).

## 4. Moss / Lichen Growth
**Status:** DONE

The natural complement to erosion: instead of removing material, add organic growth. Green moss/lichen that favors crevices (high AO regions) and horizontal surfaces (normal.y > threshold). Modifies both geometry (slight negative displacement = surface advances) and color (green/moss tint blended with base color based on growth factor). Animatable like erosionTime. Reuses the same displacement injection pattern as erosion.

## 5. Multi-Fractal Nesting
**Status:** DONE

Tiles a secondary fractal as micro-geometry on the primary fractal's surface. Implemented as `boolOp == 4` ("Nesting") in the Boolean Operations system. Domain warp (3× fbmLow) breaks the regular tiling grid, edge fade eliminates visible cell seams. Per-cell random rotation (Rodrigues, hashed from cell ID) gives each micro-fractal a unique orientation. Nest Mix slider crossfades between pure primary and full nesting. Reuses 100% of the boolean compilation pipeline.

## 6. Crystallization
**Status:** DONE

The opposite of thermal erosion: sharp crystalline structures grow from the fractal surface. Negative displacement via Voronoi-based noise (cell edges = crystal facets). Animatable like erosion. Combine erosion + crystallization = ancient ruin with crystals growing in the cracks.

## 7. Particle Rain / Snow / Fireflies
**Status:** REJECTED

Attempted implementation with procedural GPU particles (spatial hashing, fullscreen quad compositing after postprocess). Result was not satisfying — rejected by user.

## 8. Bilateral Denoiser
**Status:** REJECTED

Previously attempted and rejected — user considers it garbage.

## 9. Orbit Trap Modes
**Status:** DONE

7 trap modes (Default, Sphere, Line X/Y/Z, Cross, Grid) that remap coloring factors with position-based computations before `applyMaterial()`. 7 traps × 9 coloring modes = 63 visual combinations. Zero changes to any fractal shader — `remapTrapFactors()` in common.glsl applied at all 7 `getFactors()` call sites. ComboBox in MaterialPanel, serialized in MaterialConfig.

## 10. Domain Distortions (Twist/Bend/Taper/Repetition)
**Status:** DONE

5 space-warping transforms applied to `pos` before DE evaluation: Twist (spiral around axis), Bend (curve along axis), Taper (cone-scale), Repetition 1D (infinite copies on one axis), Repetition 3D (infinite grid). DE correction factor for non-isometric transforms prevents raymarching overshoot. Axis selector (X/Y/Z), strength, frequency, offset parameters. 8 geometry DE sites + 4 coloring-only DE sites in raytracer.glsl. 3 animatable global timeline tracks (strength, frequency, offset). Zero overhead when disabled.

## 11. Fractal Morphing
**Status:** DONE

Continuous blend between two fractal DE functions: `mix(DE_A, DE_B, boolBlend)`. Implemented as `boolOp == 5` in the Boolean Operations system — zero new files, reuses 100% of the compilation pipeline. Color mixing via `morphFactors()` evaluates both fractals' orbit traps and blends the coloring factors. boolBlend 0→1 = primary→secondary. Offset/rotation/scale of secondary work naturally. `boolBlend` is a global timeline track ("Boolean" group) for animated morphing.
