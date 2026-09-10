# Interactive rendering: the viewport scheduler

How the viewport is rendered while the user works: what runs on which thread, what a
request is, how a heavy scene stays interruptible, and how the preview picks its size.
Exports and harnesses do not go through this; they use the synchronous engine calls.

## The problem it solves

The viewport has two jobs that fight each other: show *something* within a frame of every
input, and refine to full quality when the user stops. Before 3.2.2 the two were spread
over three threads and a set of flags (a shared `cancelled`, `rendering`, `needsRender`,
`isHighQualityActive`, "skip the completion check on the first iteration because the
sample count is only cleared on the GL thread"), a fixed-rate scheduler, and calls from the
JavaFX thread that waited on the GL thread. The fluidity work of 2026-09-11 made it fast
but added heuristics on top (a per-run cancel flag next to the shared one, a first-image
rule, a threshold of two slices, strip probes). This design replaces all of it with one
owner and one policy.

## Threads

| thread | does | never does |
|---|---|---|
| JavaFX | builds a `SceneSnapshot` from the params and posts it; receives `ViewportEvent`s | wait for the GL thread |
| GL (`GLSLEngine-Thread`) | every GL call; runs the scheduler's steps as short tasks between other GL work | anything longer than one step without yielding |
| callers of exports and harnesses | `renderStill`, `exportToPNG`, ... : synchronous engine calls, as before | run while the scheduler is not paused |

The scheduler does not own a thread. Each step is one task submitted to the GL executor,
and the step reschedules the next one. Synchronous GL work from other threads (an export,
a harness, a resize) interleaves between steps and waits at most one step. There is no
`ProgressiveRenderer` thread and no cross-thread `.get()` on the interactive path.

## Vocabulary

- **SceneSnapshot**: everything a render needs, frozen on the JavaFX thread: program key,
  program source and defines when the scene is dirty (null when the engine already has
  it), the uniform map, the material SSBO data, the viewport size, the scene's preview
  ceiling (`previewScale`) and fast-shading flag, the sample targets. Immutable.
- **Request**: "the scene is now this snapshot; show it". `ViewportScheduler.request()` is
  the only entry point from the UI. The latest request wins; earlier unstarted ones are
  dropped (coalescing).
- **Job**: the work for one request, as a sequence of bounded **steps** (about
  `STEP_BUDGET_NS`, 30 ms, of GPU work each):
  - `CompileJob`: builds the program. Atomic: a driver compile cannot be split.
  - `PreviewJob`: renders `previewSamples` samples at the size the cost model chooses
    for the budget, first image after the first step.
  - `RefineJob`: renders `fullSamples` samples at the full viewport size and full quality;
    an image every `IMAGE_INTERVAL_NS` (200 ms) and at the end.
- **Policy**, in one place (`ViewportScheduler.nextStep`):
  1. a newer request preempts a `RefineJob` at any step boundary;
  2. it preempts a `PreviewJob` only once that job has emitted its first image, so a
     stream of requests faster than one sample still shows images instead of cancelling
     every sample before it lands;
  3. a `CompileJob` is never preempted; the newest request waits for it;
  4. when a `PreviewJob` completes and no request arrives for `REFINE_DELAY_NS` (400 ms),
     a `RefineJob` for the same snapshot starts; `refineNow()` skips the wait (Space);
  5. `pause()` drops the current job and ignores requests until `resume()`, which replays
     the last request. Exports and the explorers pause the scheduler.

## Steps and the cost model

`CostModel` keeps, per program key, two nanoseconds-per-pixel estimates: one for preview
shading and one for full quality, exponentially averaged from every completed step. It
answers three questions:

- **Preview size**: the largest scale, at most the scene's `previewScale`, whose one
  sample fits the budget. Quantised to 1/32 of the viewport with a dead band so
  consecutive frames share a framebuffer.
- **Samples per step**: how many whole samples fit the budget (at most 8).
- **Rows per strip**: when one sample exceeds the budget, how many rows of it fit.

Every GPU submission also costs a fixed round trip (`STEP_OVERHEAD_NS`, ~2.5 ms measured:
107 strips of a 60 ms sample took 345 ms), so the planner aims at `budget - overhead` and
never cuts a strip below `MIN_STRIP_WORK_NS` unless a single row is dearer than that. On
the dearest scene measured a row at 1080p costs 70 ms and a fraction of a row costs the
same (a ray costs what it costs), so one row is the floor.

A step with no estimate yet (a program never rendered) is small: the preview renders at
the scene's ceiling and measures; the refinement's first strip assumes full quality is
eight times the preview cost and measures.

## The engine's part

`GLSLEngine` gains a small sample-slicing API used only on the GL thread:

- `beginSample(uniforms)` opens a sample and fixes its per-sample uniforms (the `time`
  uniform included, so every strip belongs to the same sample);
- `drawRows(y, h)` draws a scissored band of the open sample. It binds the whole pass
  again every time: the strips of one sample are separate GL tasks and a readback or a
  resize may have run between two of them (the first version assumed the state survived
  and drew its strips into the default framebuffer, which the fence then reported as done
  in 0 ms);
- `commitSample()` counts the sample; `discardSample()` marks the accumulation for a
  clear (a partial sample must never be shown, and the viewport only reads frames between
  samples for the same reason);
- `fence()` returns a `GpuFence` whose `await()` drains the GPU up to it. Every step waits
  for its own fence: that costs a pipeline bubble of a few milliseconds per step, which
  the cost model's overhead term accounts for, and buys a step that is really over when
  the scheduler looks at the mailbox. Keeping a step in flight would hide the bubble at
  the price of an abort one step later; not done yet, measured as not needed.
- `readViewportFrame()` runs the post-process and returns a `ViewportFrame` (BGRA bytes,
  size, sample count), converted to a JavaFX image by the listener on the JavaFX thread.
  Read as bytes straight from the display framebuffer: the float readback plus two passes
  over two million pixels was most of the cost of showing a refinement sample at 1080p.
- `ensureProgram(key, source, defines)` compiles only if the engine does not already hold
  exactly that source under that key, so the editor and the startup task asking for the
  same scene compile it once.

The accumulation state (`sampleCount`, `needsReset`) is touched on the GL thread only.
`renderSamples` (whole samples in one call) stays for exports and harnesses.

## Events

`ViewportListener` receives, on the JavaFX thread:

- `onImage(ViewportImage)`;
- `onStatus(ViewportStatus)`: `COMPILING`, `PREVIEW`, `REFINING(samples, target)`,
  `DONE(samples, elapsed)`, `ERROR(message)`, `PAUSED`.

Nothing is stringly typed; the status bar renders these.

## What the app keeps

An `AnimationTimer` still runs, because some producers are per frame: keyboard navigation,
a camera flight, timeline playback, the turntable, the audio-reactive uniforms, the HUD.
Each of them changes the params and calls `requestRender()`, which now builds a snapshot
and posts it; nothing polls a `needsRender` flag or a `lastRenderTime`.

## What is deleted

`ProgressiveRenderer`, `RenderProgress`, `renderSampleBanded`, the controller's
`renderPreview` / `renderFull` / `cancelRender` / `isRendering`, `deferForCompile`, the
`SceneCompile` thread, `compileNodeGraphAsync`, the compile listener and its strings,
`adaptivePreviewScale`, `lastMaterialSSBO`, `lastNodeGraphSource`, the app's `needsRender`
/ `lastRenderTime` / `isHighQualityActive` / `RENDER_DELAY_MS` / `HQ_DELAY_MS`, and the
node editor's own GPU compile (it generates the GLSL for its label, marks the scene dirty
and requests; the `CompileJob` compiles once and a driver error reaches the editor's label
through `showGpuError`).

Exports, stills and the mesh evaluator pause the viewport around their engine use
(`pauseViewport` / `resumeViewport`, nestable), which also closes a race the old loop had:
a still export ran while the preview loop kept resizing the engine.

## Proof

- `NavigationFluidityProbe` and `DeferredCompileProbe` drive the new API (docs/RENDERING.md
  § Since 3.2.2 for the numbers).
- `BandedSampleProbe` checks the slicing API: strips give the same sample to the bit, a
  discarded sample leaves nothing behind.
- `RenderRegression check`, `ExportAfterPreviewProbe`: exports unchanged to the bit.
- `ResponsivenessProbe`: the refinement's throughput and image cadence.
