# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## IMPORTANT Rules
- **NEVER** include `Co-Authored-By`, AI mentions, Claude mentions, or any reference to AI/LLM in commit messages, code comments, or any generated content. This rule is absolute and permanent.
- Creative feature ideas and roadmap: see **[IDEAS.md](IDEAS.md)**
- **Next subject (the owner, 2026-11-13): colour that holds through a zoom**, [IDEAS.md](IDEAS.md) § 27. Measure and show him first, build after.
- **The march step is not to be changed** (IDEAS.md, Rejected, 28): a surface fraying into streaks up close is answered by the fudge factor.
- **Deep zoom / precision / DoF / navigation work in progress: read [docs/DEEP_ZOOM_HANDOFF.md](docs/DEEP_ZOOM_HANDOFF.md) first.** § 0 says where the work is (it is all on `main` now; the branches it names are merged or deleted), what is done, and what comes next (no fractal-type-specific code paths; one GPU probe for distances; double precision emitted by the compiler; manual DoF).

## Build Commands

```bash
mvn compile              # Build
mvn test                 # Unit tests (JUnit, no GPU) — exactly what CI runs
mvn javafx:run           # Run app (JavaFX)
mvn package              # Shaded JAR in target/Fractaliz3r-<version>.jar
mvn clean install        # Clean rebuild
mvn -Prelease clean javafx:jlink package -DskipTests   # Release image (jlink + LWJGL DLLs) in target/image/
```

**Unit tests** live in `src/test/java` and need no GPU: graph compiler output, material SSBO
layout, hybrid chains and their library, `.frac` save/reload round trip (`ConfigRoundTripTest`),
camera and animation maths, and the node graph panel built against a stub controller
(`UiWiringTest`, which is how a control that was written but never added to its toolbar gets
caught). `.github/workflows/ci.yml` runs `mvn verify` on Windows and Linux (under xvfb) on every
push; `.github/workflows/release.yml` builds the Windows installer on a `v*` tag (see
**[docs/EXPORT.md](docs/EXPORT.md)** § Building a Release).

**Every harness writes under `out/`** (gitignored). Keep it that way — the repo root once held
thirty output folders.

**GPU harnesses** (run after shader/rendering changes):
```bash
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.TiledRenderTest"   # Tiled export
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.ZoomOutTest"       # Zoom-out rendering

# Deterministic golden-image regression + median benchmark (bit-exact reproducible per GPU).
# Goldens are GPU-specific and gitignored (out/test_regression/); run 'update' once on a good build.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.RenderRegression" -Dexec.args="update"   # write goldens
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.RenderRegression" -Dexec.args="check"    # diff vs goldens (exit 1 on regression)
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.RenderRegression" -Dexec.args="bench"    # median render time per scene
# Pass a navigator manifest as a 2nd arg to validate/bench on fine-DETAIL views instead of global defaults.

# Autonomous "traveller": global view -> fine-detail framing on any fractal (output to out/nav/).
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.FractalNavigator" -Dexec.args="MANDELBULB out/nav 640x360 12 travel 8 0.6 50"
#   modes: travel (auto-frame -> depth-guided target -> dive -> sharpness sweet-spot) | fly (eased flight -> mp4) | manifest (write detail cameras) | list (explicit cameras)
#   any "name=value" arg overrides a param before travelling, e.g. juliaCx=0.42 juliaCy=0.18

# Deep-zoom detail lab: same cameras, several parameter variants, quantitative surface metrics
# (detail = Laplacian variance, edges%, lum, contrast) -> proves a deep-zoom change instead of eyeballing.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.DeepZoomLab" -Dexec.args="MANDELBOX out/dzl 480x270 4 out/nav/mbox_ladder.txt detailLOD=0,2,4"
#   pass "scene" instead of a camera file to keep the camera the .frac was saved with,
#   e.g. A/B any preset against itself: ... "presets/X.frac out/ab 640x360 24 scene rimIntensity=0.15,0.0"

# Demo presets: build .frac files + render a preview of each so candidates can be judged.
# Cameras come from FractalNavigator sweet spots, not default global framings. A 5th arg
# limits the forge to the presets whose name contains it (presets on disk may be hand-tuned;
# rebuilding one must not rewrite the others), e.g. LABYRINTH, the node-graph world.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.PresetForge" -Dexec.args="presets out/presets_preview 960x540 48"
#   A preview dir of "-" writes the .frac files and stops (no GPU); render one with
#   ShaderCompileProbe --render, which skips the two-minute compile of the built-in shaders.

# Gallery: render every .frac in a directory exactly as the app shows it (params + gradient +
# post-processing chain). This is where docs/gallery/*.jpg come from (1280x720, 256 spp).
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.GalleryRender" -Dexec.args="presets out/gallery 1280x720 256"

# Autonomous discovery: search Julia-constant space for new fractals worth looking at.
# Boundary constants are found on the CPU (no rendering), then rendered, scored, filtered
# for diversity, and the winners written as .frac. ~180 candidates in ~6 s.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.JuliaProspector" -Dexec.args="out/prospect 180 320x180 6 8"

# Autonomous discovery in hybrid-chain space: random chains (28 step types, gating, estimator)
# compiled one shader per structure (~12 s each), many parameter draws per structure as uniforms
# (milliseconds each), auto-framed on the depth AOV, scored, library chains marked, the best
# written as FOUND_NN.frac with a labelled contact sheet. 20 x 8 = 160 candidates in ~5 min.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.HybridProspector" -Dexec.args="out/prospect_hybrid 20 8 320x180 6 8 1"
#   (outDir structures drawsPerStructure WxH samples keep seed)
# One generation of nine children from one or two saved chains, as the Breed button does it
# (six parameter mutants on the parents' shaders, three structural children or crossovers, ~40 s):
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.HybridProspector" -Dexec.args="--breed out/breed out/prospect_hybrid/FOUND_01.frac out/prospect_hybrid/FOUND_02.frac"
# The Discoveries tab runs the same search on a throw-away scene and puts the user's scene back:
# render before, search, render after, require zero pixels different.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.ProspectSwapProbe" -Dexec.args="320x180 2 out/prospect_swap"

# Hybrid chains: several formulas composed inside one iteration loop (HybridNode), 28 step
# types. The first entries are controls that must reproduce the stand-alone Mandelbulb,
# Mandelbox and Bristorbrot exactly; they are compared on the depth AOV, not on colour.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.HybridLab" -Dexec.args="out/hybrid 480x270 12"
#   4th arg "presets" renders the shipped chain library (HybridPresets) instead.

# Where saturation is lost: forces a pure R/G/B gradient and renders the same camera as
# raw factors / diffuse / specular / final, so the pass that flattens colour is identifiable.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.ColorProbe" -Dexec.args="presets/JULIA_BULB_OVERVIEW.frac out/colorprobe 480x270 12"

# Which coloring modes can put more than one hue on an object: one scene under all 13,
# as a labelled sheet with a hue-spread count. Modes 0-8 give one hue, 9-12 give several.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.ColorDemo" -Dexec.args="presets/HYBRID_BOXBULB.frac out/colordemo 400x225 20"

# Interaction cost: framebuffer reallocation on a preview<->full resize, and the refinement
# pass's throughput (ms per sample) and image cadence through the viewport scheduler.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.ResizeProbe" -Dexec.args="1920x1080 0.5 20"
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.ResponsivenessProbe" -Dexec.args="presets/JULIA_BULB_OVERVIEW.frac 1280x720 24"
# How fluid is the viewport while the camera moves: one renderPreview per frame from the FX thread
# for N seconds; JavaFX held per call, worst FX stall, images/s, request->image latency, and how long a
# move waits for its preview while the refinement pass runs. Scene = .frac or a FractalType name.
# A 4th arg "probe" keeps one probe in flight per frame, as the auto speed does while a key is held.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.NavigationFluidityProbe" -Dexec.args="presets/ALBEDO_039.frac 1920x1080 3"
# The other half: the app's own panels (node editor, Lighting, Material, Quality) built against the
# real controller, one slider of each dragged at 30 ticks/s; JavaFX held per tick, worst stall,
# images/s, request->image. "window" puts them in a real window so layout and CSS passes count.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.SliderFluidityProbe" -Dexec.args="presets/ALBEDO_039.frac 1280x720 3 window"
# Does the viewport hand the GPU a long step while the camera dives? The real scheduler down a ladder of
# cameras ("dive": 1e-3 to 1e-15, three a decade): a request, then the refinement's first steps; each rung's
# longest step against its plan. Keep it small (320x180): an overrun shows there without resetting the driver.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.ViewportDiveProbe" -Dexec.args="MANDELBULB 320x180 dive 0.8,1.2,-0.8,0,0,0 abort=600"
# The other way: the viewport while the camera backs away from the surface, a request per frame as a held
# key gives, the image on screen kept at every frame and after a rest (the refinement). "auto" takes each
# frame's step from the auto speed, distance being the dial. A surface that goes missing shows in the frames.
#   (scene outDir WxH frames distance [pauseEvery] [refineMs] [auto])
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.BackOutProbe" -Dexec.args="MANDELBULB out/backout 1280x720 420 1.0 60 1200 auto"
# A refinement sample drawn in scissored strips (abortable between them) must be the same sample
# to the bit, an aborted one must be cleared, not accumulated, and a strip interrupted by another
# GL task (a palette upload) then discarded must leave no scissor behind (the bands of a preset load).
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.BandedSampleProbe"
# The probe (the scene's own program asked where camera rays land; every distance the app needs
# comes from it) and the focus pick (Ctrl+click) that uses it: a sphere against the exact
# intersection, the scene against its own image's depth, the cost of a probe, and how long a pick
# made during a refinement takes to come back and to reach the screen.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.FocusPickProbe" -Dexec.args="presets/JULIA_BULB_OVERVIEW.frac 640x360 64"
# A dive with the auto speed, headless, on any scene at 60 frames/s: each frame moves by a fraction of
# the free distance ahead (the probe); logged, a still per decade; must never cross the surface.
# (scene outDir WxH spp dial frames [eye,target as 6 comma-separated numbers: a detail camera])
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.DiveProbe" -Dexec.args="presets/JULIA_BULB_OVERVIEW.frac out/dive 480x270 16 0.1 600"
# A dive as a film: straight at the surface point under the view's centre, from as far back as the
# free space behind allows down to endDistance, geometric (same frames per decade), eased, a slow roll;
# deep precision on, every frame an export, FFmpeg makes the MP4.
#   (scene outDir WxH samples seconds fps endDistance [rollDegrees])
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.DiveFilm" -Dexec.args="presets/KIFS_DIVE.frac out/dive_film 960x540 12 24 30 2e-9 60"
# Deep precision (the camera rays marched in double, the scene's estimator translated to double by
# DeepSource): a ladder of cameras toward one surface point placed by the probe, each rung rendered
# float and double, normal error / roughness / speckle / cost, report.html. "compile" compiles every
# built-in in float and double and says which double program fails (optional 3rd arg: a name filter).
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.PrecisionLab" -Dexec.args="MANDELBOX out/precision 640x360 8 1e-1,1e-2,1e-3,1e-4,1e-5,1e-6 -4.55047,0.23943,-9.37107,-1.45361,1.45941,-6.01285"
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.PrecisionLab" -Dexec.args="compile out/precision_compile"
#   any "key=value" after the camera sets a parameter first (DeepZoomLab's keys), e.g. steps=200
#   shadowSteps=32 aoSteps=2 (the interactive preview), or "asis pathTracingEnabled=true"; each rung
#   also prints the 1-spp time of each pass and the march's mean step count.
#   Path tracing in double at depth: keep it small (160x90, 2 spp). Before ExportSampler, 320x180 at
#   6+ spp reset the display driver (2 s watchdog, nvlddmkm event 153); an application open on the GPU
#   may not survive it. Exports are cut into ~300 ms steps now, but a harness that draws otherwise is not.
# Does an export hand the GPU a long piece of work at once? Exports a scene (colour, normals) through
# ExportSampler and prints steps, strips and the longest step; PASS when none took over twice the 300 ms
# aimed at. at=1e-6 puts the camera that far before the surface under the centre, deep precision on.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.ExportWatchdogProbe" -Dexec.args="MANDELBULB out/watchdog 960x540 4 at=1e-6 0.8,1.2,-0.8,0,0,0 pathTracingEnabled=false"
#   -Dfractalizer.deep=double keeps the double entry alone, =ff the float-float one alone (to compare them;
#   unset, both, the raytracer picking one per frame).
# The double library DeepSource gives an estimator (sin, cos, atan2, acos, exp, log, pow...) run on
# the GPU against StrictMath, with the float built-ins' error beside it; "bench" adds what each
# costs in double fused multiply-adds.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.DoubleMathProbe" -Dexec.args="200000 bench"
# Float-float (FloatFloat: two floats per number, the default deep entry): every function against StrictMath,
# a Mandelbulb estimate near the surface in float / double / float-float against Java, "bench" the cost of
# each; "entries <kernel.glsl>" the entries the program gets (FloatFloatSource, DeepSource) called from one
# place, from eight, and through a subroutine (why the raytracer calls the deep estimate through one).
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.FloatFloatProbe" -Dexec.args="100000 bench"
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.FloatFloatProbe" -Dexec.args="2000 entries src/main/resources/shaders/fractals/mandelbulb.glsl"
# What a sample costs as strips against whole, on the GPU, per sync pattern (bound once, rebound
# each, fenced each, one or two in flight): the measurement behind the scheduler's slice lengths.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.StripCostProbe" -Dexec.args="presets/ALBEDO_039.frac 1920x1080"
# Does a scene shader compile block the JavaFX thread? A preview asked from the FX thread with a
# scene that needs a program must return at once (compile on the SceneCompile thread, status via
# the compile listener, image later); from any other thread it must compile inside the call.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.DeferredCompileProbe"
# Proves the cheap interactive preview cannot leak into an export: same scene exported cold
# and again straight after a preview, compared pixel for pixel.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.ExportAfterPreviewProbe" -Dexec.args="presets/JULIA_BULB_OVERVIEW.frac 640x360 12"
# Caustics (photon.glsl): the energy check (a matte slab lit twice, by NEE and by photons that may
# land without meeting glass; the ratio must be 1, with and without the emission map), the cost of a
# photon pass, and a glass ball and a smooth metal Mandelbulb with and without caustics.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.CausticProbe" -Dexec.args="out/caustic 640x360 64"
# Emitter sampling (lights.glsl): the built-in slab scene rendered with the graph's emitters found by
# chance and drawn directly must converge to the same image; noise removed at 32 spp; time per sample.
# Any .frac renders the same comparison without a verdict (a bright emitter is clamped when found by chance).
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.EmitterProbe" -Dexec.args="slab out/emitter 640x360 1024 32"
# One light transport (BIDIR): the path tracer alone and the path tracer with the photon pass weighted
# against it must converge to the same image; panel = glass ball under an emissive panel, metal = a
# smooth metal block, beam = the beam on the slab, window = the panel scene behind a pane of glass
# (the gather's case). Noise removed and time per sample too.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.BidirProbe" -Dexec.args="panel out/bidir 320x180 2048 32"
# The app's Explore button, headless: CameraExplorer from a fractal's default camera (or a
# .frac), scored thumbnails written as a sheet best first, time per view.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.ExploreProbe" -Dexec.args="MANDELBULB out/explore 320x180 4 3 4 0.6"
#   (TYPE-or-.frac outDir WxH samples targets steps shrink); "variations 12 0.2" as 5th..7th args
#   runs the Variations tab instead (count, amplitude).
# The Presets & Chains browser's thumbnails: every chain + every preset at 320x180. Rerun with
# "install" whenever a chain or a preset is added — a JUnit test fails until the shipped set matches.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.ThumbnailForge" -Dexec.args="out/thumbs 320x180 32 install"
# The laser sweep: a mirror of the optical table turns a few degrees back and forth, one still export
# per frame (the transform's rotation is a uniform, nothing recompiles), FFmpeg makes the MP4.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.LaserSweep" -Dexec.args="presets/LASER_TABLE.frac out/sweep 1280x720 96 96 24 45 3.0"
#   (scene outDir WxH samples frames fps mirrorYaw amplitudeDeg)
# A film in which the formula itself moves: the camera swings around the centre while parameters of the
# fractal leaves go from one value to another and back (uniforms, nothing recompiles); the film loops.
#   (scene outDir WxH samples seconds fps swingDegrees key=from..to ...)
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.MorphFilm" -Dexec.args="presets/PRISM_GEM.frac out/morph 960x540 48 12 30 16 power=5..9"
# A flight as a film: the camera follows a smooth curve through waypoints (a text file, "ex ey ez tx ty tz"
# per line) at an even, eased pace; leaf parameters may go and come back as in MorphFilm. seconds = 0
# renders one still per waypoint instead (to choose the path; parameters then sweep from first to last).
#   (scene outDir WxH samples seconds fps path.txt [key=from..to ...])
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.FlightFilm" -Dexec.args="presets/CATHEDRAL.frac out/flight 960x540 96 16 24 docs/flights/cathedral.txt"
# How far ahead of the work an export progress bar runs: time of the first 100%% report
# against the moment the export future actually completes.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.ExportProgressProbe" -Dexec.args="presets/JULIA_BULB_OVERVIEW.frac 2600x1600 128"
# How long each shader takes to compile, and which one never returns: the built-ins first (7-10 s
# each on the dev machine), then any .frac given. Run it before blaming a hang or an NVIDIA C9999
# on anything else; a shader that grows past the driver's budget shows up here by name.
mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.ShaderCompileProbe" -Dexec.args="presets/ALBEDO_039.frac presets/LABYRINTH.frac"
```

## Project Architecture

### Core Components

```
org.fractalizer
├── GLSLFractalizerApp.java          # JavaFX entry point with FPS navigation
├── engine/
│   ├── GLSLEngine.java              # GPU compute abstraction (LWJGL/OpenGL)
│   ├── ShaderPreprocessor.java      # GLSL symbol renaming for shader concatenation
│   ├── DeepSource.java              # deep precision: the scene's DE_simple and what it reaches copied in double -> DE_deep(dvec3); entry() picks float-float first
│   ├── FloatFloatSource.java        # the same in float-float: parsed, typed, every operation on the position written as an ff_ call; common subexpressions shared
│   ├── FloatFloat.java              # the float-float library (ff1..ff4, ffm2): error-free arithmetic under precise, sin/cos/atan2/exp/log/pow...
│   ├── BlueNoiseGenerator.java      # 64x64 blue noise texture (Mitchell's best-candidate)
│   └── Camera.java                  # Quaternion-based FPS camera (no gimbal lock)
├── fractals/
│   ├── FractalParams.java           # Interface for fractal parameters
│   ├── FractalType.java             # Enum of available fractal types
│   ├── AbstractFractalParams.java   # Base class with common params (camera, lighting, gradient, etc.)
│   ├── GradientPalette.java         # Custom gradient model with ColorStops and GPU texture generation
│   ├── [10 fractal Params classes]  # Mandelbulb, Mandelbox, Menger, KIFS, QuatJulia4D, PolyIFS, Sierpinski, PseudoKleinian, Apollonian, Bristorbrot
│   ├── FractalTerrainParams.java    # Fractal Terrain: fBm heightfield
│   ├── CornellBoxParams.java        # Cornell Box: per-object materials in shader
│   ├── CustomShaderParams.java      # Custom Shader: user GLSL source + dynamic uniforms
│   └── NodeGraphParams.java         # Node Graph: wraps GraphNode tree as fractal params
├── graph/
│   ├── GraphNode.java              # Abstract base for composable fractal operations
│   ├── FractalNode.java            # Leaf: wraps FractalType + per-node params
│   ├── PrimitiveNode.java          # Leaf: 12 SDF geometric primitives
│   ├── CSGNode.java                # Binary: Union/Intersect/Subtract/Morph
│   ├── TransformNode.java          # Unary: 7 modes (Standard/Mirror/Twist/Bend/Taper/Rep/Rep1D)
│   ├── HybridNode.java             # Leaf: chains formulas INSIDE one iteration loop (28 step types, per-step iteration gating)
│   ├── HybridPresets.java          # 31 named chains (controls first), offered as "Load a chain…" in the editor
│   ├── EffectNode.java             # Unary: Erosion/Crystal/Moss
│   ├── MaterialNode.java           # Unary: per-node material overrides
│   ├── GraphCompiler.java          # Compiles node tree → composite GLSL shader (8 phases)
│   ├── GraphNodeNamer.java         # Stable node naming for animation tracks
│   └── NodeGraphAnimationHelper.java # Bridge: graph nodes → animatable timeline parameters
├── explore/
│   ├── CameraExplorer.java          # Scored views from the current camera: pivot, auto-frame, aim scan, dives
│   ├── ParamExplorer.java           # Variations: knobs nudged at random, rendered from the same camera, scored
│   ├── ChainProspector.java         # Discoveries: random hybrid chains, one compile per structure, draws as uniforms, framed, scored, known families marked
│   ├── Look.java                    # a discovery's palette / light / sky / material, drawn from the seed, saved with it
│   ├── ChainBreeder.java            # a generation from one or two parents: parameter mutants, structural moves, crossovers
│   ├── ParamKnobs.java              # which numeric parameters a scene has (fractal leaves' floats, hybrid steps)
│   ├── CameraFlight.java            # eased flight between two poses (smoothstep + slerp), stepped by the render loop
│   ├── FrameScorer.java             # detail / coverage / centring score shared with the navigator harness
│   ├── ViewRenderer.java            # what the explorer needs from a renderer (GPU in the app, a sphere in tests)
│   ├── ControllerViewRenderer.java  # ViewRenderer over GLSLFractalizerController, in memory at thumbnail size
│   └── ControllerChainRenderer.java # ChainRenderer over the controller: a chain at the root, uniforms refreshed, depth + colour
├── audio/
│   ├── AudioReactiveEngine.java     # Spectrum analysis, beat/onset detection
│   └── AudioPreAnalyzer.java        # Offline FFT pre-analysis (FFmpeg decode)
├── render/
│   ├── ViewportScheduler.java       # The interactive viewport: requests -> bounded steps on the GL thread, one preemption policy
│   ├── SceneSnapshot.java           # A scene frozen for one render (program source, uniforms, sizes, preview policy)
│   ├── CostModel.java               # ns per pixel per program: preview size, samples per step, rows per strip
│   ├── ExportSampler.java           # every export / in-memory render in steps of <= ~300 ms (whole samples or strips): the driver's 2 s watchdog
│   ├── ViewportEvent.java           # images and typed status to the UI
│   ├── LightList.java               # the scene's lights as one table for the shaders (the graph's emitters today)
│   └── FFmpegExporter.java          # MP4 video export via FFmpeg
├── animation/
│   ├── Timeline.java                # Animation timeline with tracks
│   ├── AnimationTrack.java          # Keyframe track with optional Catmull-Rom spline
│   └── Keyframe.java                # Single keyframe (time, value, easing)
└── ui/
    ├── GLSLFractalizerController.java  # Bridges UI with GLSL engine (parameter caching)
    ├── ViewportHUD.java                # 3D Compass, Speed Meter, Telemetry overlay
    ├── DiveSpeed.java                  # auto speed: a frame's step = a fraction of the distance to the surface in front, bounded by the free distance along the move (the probe)
    ├── AnimationManager.java           # Manages timeline and keyframe editing
    ├── components/
    │   ├── EnhancedSlider.java         # Pro slider: mouse wheel, precision, lock, inline edit
    │   ├── GradientEditor.java         # Visual gradient editor with draggable color stops
    │   ├── CustomShaderEditor.java     # GLSL editor with @param parsing, dynamic sliders
    │   ├── NodeGraphEditor.java        # Visual node graph editor: canvas + detail panel + undo/redo
    │   ├── ExploreDialog.java          # "Explore": Views (scored framings, click to fly) + Variations (knobs nudged, click to apply)
    │   └── SceneBrowser.java           # "Presets & Chains": every shipped .frac and hybrid chain as a thumbnail, click to load; Discoveries tab = the prospector live
    └── panels/
        ├── FractalPanel.java           # Fractal type and parameters
        ├── MaterialPanel.java          # Material type, physical props, artistic palettes
        ├── LightingPanel.java          # Light direction and colors
        ├── QualityPanel.java           # Ray steps, DoF, path tracing, adaptive sampling
        ├── PostProcessingPanel.java    # Bloom, tone mapping, color correction
        ├── AudioPanel.java             # Audio-reactive controls + offline video export
        └── ExportPanel.java            # Image/animation export with motion blur

src/test/java/  (JUnit, no GPU — `mvn test`, run by CI)
├── config/ConfigRoundTripTest       # every setting a .frac carries, written and read back
├── engine/ShaderPreprocessorTest    # symbol prefixing for shader concatenation
├── engine/DeepSourceTest            # the double entry: what DE_simple reaches, typed in double, structs, shims, every built-in
├── engine/FloatFloatSourceTest      # the float-float entry: every built-in and chain translates, uniforms stay float, sincos shared, swizzle writes
├── engine/CameraTest                # quaternion camera: orthonormal, no drift, axes
├── render/CostModelTest             # strip planning: a latency-bound scene is not cut into latency-only strips
├── animation/AnimationTrackTest     # keyframes, easing, Catmull-Rom, timeline
├── graph/GraphCompilerTest          # GLSL + uniforms for leaves, CSG, transforms
├── graph/MaterialSSBOTest           # SSBO layout, matId propagation, serialization
├── graph/HybridNodeTest             # chain library, per-step uniforms, serialization
├── ui/DiveSpeedTest                 # auto speed against a late probe: never more than the gap left ahead, waits for a trusted measure, backs out of the solid
└── ui/components/UiWiringTest       # is a control actually ON the built panel (stub controller)

test/  (GPU harnesses in src/main — run them instead of re-reading the render path; output in out/)
├── RenderRegression.java        # golden-image diff + benchmark, global and detail scenes
├── DeepZoomLab.java             # detail/saturation metrics, A/B any param on any .frac
├── ColorProbe.java              # which shading pass loses saturation
├── ColorDemo.java               # which coloring modes give more than one hue
├── JuliaProspector.java         # autonomous search of Julia-constant space
├── HybridLab.java               # hybrid chains + bit-exact controls; "presets" = the library
├── HybridProspector.java        # command line over explore/ChainProspector: sheet, ranking, FOUND_NN.frac, per-recipe table; --breed = one generation
├── ProspectSwapProbe.java       # the Discoveries search must leave the user's scene pixel-identical
├── FractalNavigator.java        # global -> fine-detail camera traveller
├── PresetForge.java             # build demo .frac files + preview each
├── ResizeProbe.java             # framebuffer cost of a preview<->full switch
├── ResponsivenessProbe.java     # the refinement's throughput (ms/sample) and image cadence
├── ExportProgressProbe.java     # how far ahead of the work a progress bar runs
├── ShaderCompileProbe.java      # compile time per shader, built-ins then any .frac; names the one that hangs
├── ExportAfterPreviewProbe.java # the cheap preview must not leak into an export
├── CausticProbe.java            # caustics: photon energy against NEE, cost of a pass, pictures with and without
├── EmitterProbe.java            # emitters drawn vs found by chance: same converged image, noise removed, cost
├── BidirProbe.java              # path tracer alone vs with the photon pass weighted against it: same converged image
├── DeferredCompileProbe.java    # a scene compile asked from the FX thread must not block it
├── NavigationFluidityProbe.java # the viewport while the camera moves: FX held, images/s, latency, interrupt
├── ViewportDiveProbe.java       # the viewport down a dive to 1e-15: each rung's longest GPU step against its plan (the driver's 2 s)
├── BackOutProbe.java            # the viewport while the camera backs out, auto speed or not: every frame on screen kept (a surface cut away at a distance shows there)
├── SliderFluidityProbe.java     # the viewport while a slider of each real panel moves; "window" for layout costs
├── BandedSampleProbe.java       # a sample drawn in strips is the same sample; an aborted one is cleared; a depth read or a probe between strips loses nothing
├── FocusPickProbe.java          # the probe and the focus pick: sphere vs exact intersection, scene vs its image's depth, cost, latency during a refinement
├── DiveProbe.java               # auto speed: a headless dive on any scene, readings per frame, a still per decade, never crosses the surface
├── DiveFilm.java                # a dive as a film: geometric approach to the centre's surface point, deep precision, every frame exported, MP4
├── PrecisionLab.java            # deep precision: float vs double down a camera ladder (normal error, speckle, cost); "compile" = every built-in in double
├── DoubleMathProbe.java         # the double library (dp_sin, dp_atan2, dp_pow...) on the GPU against StrictMath; "bench" = cost of each
├── FloatFloatProbe.java         # the float-float library against StrictMath, a Mandelbulb estimate in 3 precisions, cost; "entries" = call sites vs subroutine
├── ExportWatchdogProbe.java     # an export's steps: how many, strips, the longest (must stay far under the driver's 2 s)
├── StripCostProbe.java          # GPU cost of strips vs whole per sync pattern: why slices are 60 ms
├── ExploreProbe.java            # the app's Explore button, headless: scored views (or variations) from any camera
├── ThumbnailForge.java          # the browser's thumbnails: every chain + every preset at 320x180, "install" ships them
├── LaserSweep.java              # the optical table's mirror turning: one still per frame, FFmpeg makes the MP4
├── MorphFilm.java               # a film in which the formula moves: the camera swings while leaf parameters go and come back (power=5..9), FFmpeg makes the MP4
├── FlightFilm.java              # a flight as a film: the camera along a smooth curve through waypoints, leaf parameters moving; seconds = 0 = a still per waypoint
└── GalleryRender.java           # every .frac in a dir rendered as the app shows it (README gallery)
```

### GLSL Shaders (`src/main/resources/shaders/`)

```
shaders/
├── raytracer.glsl         # Main raymarcher (fractal-agnostic)
├── common.glsl            # Common utilities, materials, artistic palettes
├── postprocess.glsl       # Bloom, tone mapping, color correction
├── lights.glsl            # The light list (LightList.java) and the photon pass's draws and densities, under HAS_EMITTERS / BIDIR
├── photon.glsl            # The photon pass: light tracing from every light, MIS-weighted; the scene compiled once more under PHOTON_PASS
├── grid_count/scan/scatter.comp  # the hash grid over the pass's photon vertices, for the gather behind glass
├── photon_splat.vert/frag # the photons as points into the accumulation and the emission map
└── fractals/              # Individual fractal Distance Estimators
    ├── mandelbulb.glsl, mandelbox.glsl, menger.glsl, kaleidoscopic.glsl
    ├── quaternionjulia4d.glsl, polyhedral.glsl, sierpinski.glsl
    ├── pseudokleinian.glsl, apollonian.glsl, bristorbrot.glsl
    ├── fractalterrain.glsl, cornellbox.glsl
```

## Shader Assembly Pipeline (Summary)

| Mode | Concatenation | Trigger |
|------|---------------|---------|
| **Standard** | `#version 430` + `common.glsl` + `fractal.glsl` + `raytracer.glsl` | Single fractal type |
| **Boolean Ops** | Above + `#define BOOLEAN_OPS` + preprocessed secondary (b_ prefix) | Boolean Operations enabled |
| **Node Graph** | `#version 430` + `common.glsl` + GraphCompiler output + `raytracer.glsl` | FractalType.NODE_GRAPH |
| **Custom Shader** | `#version 430` + `common.glsl` + user source + `raytracer.glsl` | FractalType.CUSTOM_SHADER |

Uniform changes never trigger recompilation — only structural changes require a new shader compile. Full documentation: **[docs/SHADER_PIPELINE.md](docs/SHADER_PIPELINE.md)**

## Node Graph System (Summary)

The Node Graph is the primary UI for fractal editing/composition. Composable tree → single GPU shader.

**Node types:** `FractalNode` (leaf) / `PrimitiveNode` (leaf: 12 SDF shapes) / `CSGNode` (binary: 5 ops — Union/Intersect/Subtract/Morph/Nesting) / `TransformNode` (unary: 7 modes) / `EffectNode` (unary: Erosion/Crystal/Moss) / `MaterialNode` (unary: per-node material overrides via SSBO, `colorMode` 0=palette/1=solid/2=tint, gated by `#define HAS_MATERIALS`).

**GraphCompiler** compiles in 8 phases with unique prefixes (`n0_`, `t0_`, `e0_`, `m0_`, `c0_`, `p0_`) via `ShaderPreprocessor`.

Full documentation: **[docs/NODE_GRAPH.md](docs/NODE_GRAPH.md)**

## Detailed Documentation Index

Most of what this project learned the hard way is in these files rather than in the code.
Before reading a render path to answer a question about it, check whether one of the
harnesses in **[docs/RENDERING.md](docs/RENDERING.md) § Test Harnesses** already measures
it — several exist precisely because reading the code gave the wrong answer.


| Topic | File | Contents |
|-------|------|----------|
| Shader pipeline | [docs/SHADER_PIPELINE.md](docs/SHADER_PIPELINE.md) | Assembly modes, ShaderPreprocessor, uniform lifecycle, recompilation triggers |
| Node graph | [docs/NODE_GRAPH.md](docs/NODE_GRAPH.md) | All node types incl. **HybridNode** (formulas composed inside one iteration loop) and its chain library, GraphCompiler phases, uniforms, animation, serialization, UI |
| Interactive viewport | [docs/INTERACTIVE_RENDER.md](docs/INTERACTIVE_RENDER.md) | The viewport scheduler: threads, snapshots, jobs and steps, the preemption policy, the cost model, what the app keeps |
| Rendering | [docs/RENDERING.md](docs/RENDERING.md) | Blue noise, cone tracing, adaptive sampling, cinematic pipeline, effects, coloring, spline camera, HUD. Also: **why colour looked flat** (rim light, palette-tinted sky, which coloring modes carry more than one hue), **deep zoom** (view-relative scales, iteration LOD, what the formula limits), **interactive preview vs full quality**, **progressive batching and interruptibility**, and the eighteen test harnesses |
| Features | [docs/FEATURES.md](docs/FEATURES.md) | EnhancedSlider, dice randomizer, morph crossfade, custom shader editor, boolean ops, nesting |
| Export | [docs/EXPORT.md](docs/EXPORT.md) | VR/360, tiled rendering, AOV export, mesh export, video encoding, jlink release, visual regression test, **why an export progress bar can lie** |
| Audio-reactive | [docs/AUDIO_REACTIVE.md](docs/AUDIO_REACTIVE.md) | Spectrum analysis, beat/onset detection, mappings, offline export pipeline |

## How to Add a New Fractal Type

Adding a fractal requires **2 new files** and **4 existing file edits**. Follow this checklist exactly.

### Step 1: Choose names

Pick a `kernelName` (lowercase, no spaces — used for shader filename and animation track prefix) and a `displayName` (human-readable). Example: `kernelName = "mandelbulb"`, `displayName = "Mandelbulb"`.

### Step 2: Create the Params class

**File:** `src/main/java/org/fractalizer/fractals/{Name}Params.java`

Follow `MandelbulbParams.java` as the minimal template:
- Extend `AbstractFractalParams`
- Declare fractal-specific fields with `@Animatable(display = "...")` annotation
- Set defaults in constructor (including `camera.setPosition(...)` if needed)
- Override `getType()` → return matching `FractalType` enum
- Override `withReducedQuality()` → copy specific params + reduce iterations
- Add getters/setters for each field

### Step 3: Create the GLSL shader

**File:** `src/main/resources/shaders/fractals/{kernelName}.glsl`

Follow `mandelbulb.glsl` as the template. Must define exactly these 4 things:
1. **Uniforms** — matching the names you'll bind in `buildUniforms()`
2. **`struct OrbitTrap`** — always has: `float minDist, planeX, planeY, planeZ; int iterations;`
3. **`float DE(vec3 pos, out OrbitTrap trap)`** — full DE with orbit trap tracking
4. **`float DE_simple(vec3 pos)`** — same math, no orbit traps (used for shadows/AO/normals)
5. **`vec3 getFactors(OrbitTrap trap)`** — converts traps to 3 coloring factors

### Step 4: Wire into 4 existing files

#### 4a. `FractalType.java` — Add enum value
```java
MY_FRACTAL("Display Name", "kernelname"),  // before TEST_SCENE
```

#### 4b. `GLSLFractalizerController.java` — 2 switch blocks
- **`setFractalType()`** (~line 107): `case MY_FRACTAL -> this.currentParams = new MyFractalParams();`
- **`buildUniforms()`** (~line 884): bind all fractal-specific uniforms

For `vec3`: `uniforms.put("name", new float[]{x, y, z})`. For `mat3`: use `createRotationMatrix()`.

#### 4c. `FractalConfig.java` (in `org.fractalizer.config`) — 2 methods
- **`extractFractalParams()`** — serialize params to map
- **`applyFractalParams()`** — deserialize map to params

Both blocks must go **before** the `TestSceneParams` / `CornellBoxParams` blocks.

#### 4d. `FractalPanel.java` — 5 locations
1. Field declarations (~line 34)
2. `createContent()` (~line 114): add create call + add to children
3. `createMyFractalControls()` method: VBox + EnhancedSliders + callbacks
4. Combo box handler (~line 689): hide/show case
5. `refreshFromParams()` (~line 902): hide all + `else if instanceof` show

### Naming conventions

| Concept | Convention | Example |
|---------|-----------|---------|
| Enum value | `UPPER_SNAKE` | `PSEUDO_KLEINIAN` |
| kernelName | `lowercase` | `pseudokleinian` |
| Params class | `PascalCase + Params` | `PseudoKleinianParams` |
| Shader file | `{kernelName}.glsl` | `pseudokleinian.glsl` |
| VBox field | `{camelCase}Controls` | `pseudoKleinianControls` |
| Slider prefix | 2-3 letter abbreviation | `pk` → `pkIterSlider` |

### Verification

1. `mvn compile` — must succeed
2. `mvn javafx:run` — select new fractal, verify geometry
3. Test save/load with File > Save/Load
4. Run visual regression test (see Build Commands above)

## Navigation Controls

| Key | Action |
|-----|--------|
| Arrow keys | Move forward/backward/strafe |
| Mouse drag | Look around |
| Q/E | Roll left/right |
| Page Up/Down | Move up/down |
| R | Reset camera |
| Space | Render full quality |
| Scroll wheel | Adjust movement speed |

## Dependencies

- **LWJGL 3.4.3** — OpenGL bindings for GPU rendering
- **JavaFX 26.0.2** — UI framework
- **JavaFX Media** — Audio playback and spectrum analysis
- **JDK 24** to build and run from source (JavaFX 26 ships Java 24 class files, so 24 is the floor; `--release 21` remains the bytecode target; CI uses Temurin 24)
- **FFmpeg** (optional) — MP4 video export and audio pre-analysis (must be in system PATH)
