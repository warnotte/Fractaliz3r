# Ideas

Creative feature ideas for Fractaliz3r. Status: IDEA (not started), IN PROGRESS, DONE, REJECTED.

## 1. Fractal Portals — Non-Euclidean Geometry
**Status:** IDEA

Holes in space that teleport rays. Walk through a Mandelbulb and emerge inside a Mandelbox. Implemented directly in the raymarcher by detecting portal surfaces (sphere/plane) and remapping ray origin + direction on hit. Each portal connects two fractal parameter sets or types.

## 2. Genetic Algorithm / Fractal Breeding
**Status:** IDEA

Karl Sims-style artificial selection. Present a grid of 4-9 random variations of the current fractal. User clicks favorites, they crossover and mutate into the next generation. Parameters evolve toward aesthetically pleasing forms through selection pressure. Uses the existing `@Animatable` parameter discovery for automatic mutation ranges.

## 3. Erosion Simulation
**Status:** DONE

Procedural erosion applied to any fractal DE. Three layers (weathering cracks, hydraulic channels, thermal rounding) with proximity gating and lightweight shadow/AO variant. Animated via global timeline tracks.

## 4. Time Crystal — Depth-Dependent Parameters
**Status:** IDEA

Fractal parameters evolve as a function of the ray's travel distance. The deeper the ray penetrates, the more the geometry changes. A Mandelbulb with power=4 at the surface gradually becomes power=12 deep inside, creating impossible nested structures with infinite variation. Implemented by passing accumulated ray distance into DE() and interpolating parameters based on depth.

## 5. Reaction-Diffusion Surface Textures
**Status:** IDEA

Turing patterns (leopard spots, labyrinthine, mitosis) living on the fractal surface. The patterns evolve over time, creating organic-looking animated textures. Uses a UV-free approach: 3D reaction-diffusion computed in the distance field's neighborhood, sampled at surface hits. Purely procedural, no mesh UV needed.

## 6. Particle Rain / Snow / Fireflies
**Status:** REJECTED

Attempted implementation with procedural GPU particles (spatial hashing, fullscreen quad compositing after postprocess). Result was not satisfying — rejected by user.

## 7. Bilateral Denoiser
**Status:** REJECTED

Previously attempted and rejected — user considers it garbage.

## 8. Boolean Operations Between Fractals
**Status:** IDEA

Union/intersection/subtraction of two fractal distance fields. Carve a Menger Sponge out of a Mandelbulb with `max(DE_A, -DE_B)`, or merge them with `min(DE_A, DE_B)`. Each fractal has its own parameter set. A secondary fractal type selector + operation ComboBox in the UI. ~20 lines of GLSL (evaluate both DEs, combine), coloring from whichever DE is closer. Erosion applies to the combined result.

## 9. Moss / Lichen Growth
**Status:** IDEA

The natural complement to erosion: instead of removing material, add organic growth. Green moss/lichen that favors crevices (high AO regions) and horizontal surfaces (normal.y > threshold). Modifies both geometry (slight negative displacement = surface advances) and color (green/moss tint blended with base color based on growth factor). Animatable like erosionTime. Reuses the same displacement injection pattern as erosion.

## 10. Fractal Dreams — Screensaver Mode
**Status:** IDEA

Autonomous drift through parameter space via smooth Perlin noise over time. Parameters evolve slowly, camera orbits, fractal type crossfades periodically. A "launch and forget" mode for hours of contemplation. Uses the existing morph system + spline camera paths.

## 11. X-Ray / Volumetric Interior
**Status:** IDEA

Render the fractal as semi-transparent. Instead of stopping at the first hit, the ray continues and accumulates density based on DE proximity. Reveals internal structure like a CT scanner. Controlled by an "opacity" slider that interpolates between solid surface and translucent volume. Could use the existing orbit trap data for interior coloring.

## 12. Multi-Fractal Nesting
**Status:** IDEA

A large-scale Menger Sponge where each cube is replaced by a tiny Mandelbulb. Evaluate DE_A at large scale, then when close enough, switch to DE_B at small scale with a coordinate transform. Fractals within fractals — infinite complexity at every zoom level.

## 13. Crystallization
**Status:** IDEA

The opposite of thermal erosion: sharp crystalline structures grow from the fractal surface. Negative displacement via Voronoi-based noise (cell edges = crystal facets). Animatable like erosion. Combine erosion + crystallization = ancient ruin with crystals growing in the cracks.
