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
**Status:** IDEA

New coloring modes beyond the current cumulative plane traps. Sphere trap (distance to a point), line trap (distance to an axis), cross trap (min of X/Y/Z distances), point trap (closest orbit point). Each mode produces radically different patterns on the same fractal geometry. Purely shader-side modification of `getFactors()` / orbit trap struct. Controlled via a ComboBox in MaterialPanel.

## 10. Domain Distortions (Twist/Bend/Repetition)
**Status:** IDEA

Space-warping transformations applied to `pos` before DE evaluation. Twist (spiral around an axis), Bend (curve space along an axis), Taper (cone-scale), Domain Repetition (infinite copies with `mod()`). Transforms any fractal into impossible shapes. Same injection pattern as erosion — modify `pos` in all geometry DE call sites. Parameters: distortion type, axis, strength, frequency. Animatable via global timeline tracks.

## 11. Fractal Morphing
**Status:** IDEA

Continuous blend between two fractal DE functions: `mix(DE_A(pos), DE_B(pos), t)`. Reuses the Boolean Operations shader compilation pipeline (both fractals already compiled and available as `DE`/`b_DE_simple`). A morph slider (0→1) smoothly transitions geometry from one fractal type to another in real-time. Could be implemented as `boolOp == 5` alongside nesting.
