# Ideas

Creative feature ideas for Fractaliz3r. Status: IDEA (not started), IN PROGRESS, DONE, REJECTED.

## 1. Fractal Portals — Non-Euclidean Geometry
**Status:** IDEA

Holes in space that teleport rays. Walk through a Mandelbulb and emerge inside a Mandelbox. Implemented directly in the raymarcher by detecting portal surfaces (sphere/plane) and remapping ray origin + direction on hit. Each portal connects two fractal parameter sets or types.

## 2. Genetic Algorithm / Fractal Breeding
**Status:** IDEA

Karl Sims-style artificial selection. Present a grid of 4-9 random variations of the current fractal. User clicks favorites, they crossover and mutate into the next generation. Parameters evolve toward aesthetically pleasing forms through selection pressure. Uses the existing `@Animatable` parameter discovery for automatic mutation ranges.

## 3. Erosion Simulation
**Status:** IDEA

Apply physical erosion (hydraulic, thermal) to the fractal distance field. Watch a Mandelbulb weather and crack like a rock formation. Could use a 3D voxel grid overlay that modifies the DE: `max(fractalDE, -erosionField)`. Erosion computed via GPU compute shader, accumulates over frames. Each run produces unique results.

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
