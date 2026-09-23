# ZholSafe — Architecture (frozen in Stage 0)

This document freezes module boundaries, responsibilities, threading and failure behaviour.
Changes to this document require an explicit, justified proposal (see `DEVELOPMENT.md`).

## 1. System overview

```
                         VEHICLE (Android, Java)                              CLOUD (optional)
 ┌────────────────────────────────────────────────────────────────┐   ┌───────────────────────────┐
 │  Road camera ─┐                                                │   │  ZholNet Server           │
 │               ├─► LatestFrameQueue ─► RoadDetector (ONNX RT)   │   │  Java 21 + Spring Boot    │
 │  Driver cam ──┘        │                 │                     │   │   ├ hazard ingestion      │
 │                        │                 ▼                     │   │   ├ validation            │
 │                        │           ObjectTracker               │   │   ├ nearby queries        │
 │                        │                 │ Trajectory/TTC      │   │   ├ WebSocket notify      │
 │                        ▼                 ▼                     │   │   └ risk map / analytics  │
 │                DriverObservationProvider ─► DriverStateAnalyzer           │   │        │                  │
 │                                          │                     │   │   PostgreSQL + PostGIS    │
 │                                          ▼                     │   └───────────▲───────────────┘
 │                     RiskEngine (pure Java, explainable)        │               │ HTTPS REST (events)
 │                       │                    │                   │               │ WebSocket (notifications)
 │                       ▼                    ▼                   │               │
 │              AlertSink (audio/visual)   HazardEventPublisher ──┼───────────────┘
 │              *** works fully offline ***  (best effort, queued)│
 └────────────────────────────────────────────────────────────────┘
```

**Safety-critical path** (must work with no internet, no server, no DB, no GPS, no other vehicle):
`camera → detector → tracker → RiskEngine → AlertSink`. ZholNet is supplementary only.

## 2. Language and runtime policy

| Concern                          | Decision (Stage 0)                                  |
|----------------------------------|-----------------------------------------------------|
| Vehicle app, Risk Engine, server | **Java** (Android app: Java 17 language level; server: Java 21) |
| Production inference             | Java + **ONNX Runtime** (Java API). No Python at runtime. |
| C++/JNI                          | **Not used.** Only a future, profiling-justified optimisation. |
| Kotlin / Node / Python services  | Not used unless explicitly instructed later.        |
| AI development                   | Python (`ai-training/`) — dataset, training, validation, benchmarks, ONNX export only. |
| Web monitor                      | Plain HTML/CSS/JS. No frontend framework.           |
| Repository style                 | Modular **monorepo**; no microservices.             |

## 3. Repository layout

```
ZholSafe/
├── android-app/            Gradle multi-module
│   ├── core/               PURE JAVA (no Android): contracts, config, Risk Engine, pipeline ports
│   └── app/                Android Java: CameraX, alerts, location, ZholNet client, UI
├── zholnet-server/         Java 21 Spring Boot (Maven)
├── ai-training/            Python offline AI tooling
├── models/                 ONNX artefacts location (binaries git-ignored) + label files
├── database/               PostgreSQL/PostGIS schema design
├── web/                    Static monitor / future risk map
├── tests/                  Cross-module contract schema + fixtures
├── demo/                   Demo-mode scenarios (same pipeline as live)
├── scripts/                Build helpers, contract checks
└── docs/                   ARCHITECTURE, DATA_CONTRACTS, DEVELOPMENT, ROADMAP
```

### Why `android-app/core` is a separate pure-Java module
The Risk Engine, data contracts, configuration and pipeline interfaces must be unit-testable
without Android, camera, network, database or a neural network. Keeping them in a plain
`java-library` module enforces that boundary at compile time (the module cannot import
`android.*`). The `app` module depends on `core`, never the reverse.

## 4. Vehicle application — package responsibilities

Package root: `kz.zholsafe`

| Package        | Module | Responsibility                                                                 | Stage |
|----------------|--------|--------------------------------------------------------------------------------|-------|
| `model`        | core   | Shared value types: `ObjectClass`, `Detection`, `BoundingBox`, `Estimate`, `HazardEvent`, `GeoPosition` | 0 |
| `ai`           | core   | Inference contracts: `Frame`, `OnnxModel`, `RoadDetector`, `DriverDetector`, `LabelMap`, `ModelDescriptor`, `ModelNotAvailableException` | 0 (impl: 2 / deferred 4.3) |
| `tracking`     | core   | `ObjectTracker`, `TrackedObject`, `MovementClass`                              | 0 (impl: 3) |
| `trajectory`   | core   | Stage 4.0 `TrajectoryEstimator`; legacy `DistanceEstimator`/`TtcEstimator` remain unavailable | 0 / 4.0 |
| `physical`     | core   | Experimental calibration, ground/size depth, metric range regression, separate TTC contracts/processor | 4.1 |
| `driver`       | core   | `DriverObservation`, `DriverObservationProvider`, `EyeState`, `HeadPose(State)`, `YawnLikeState`, `ObservationQuality`, `PerclosValue`, `DriverState(Analyzer)`, `TemporalDriverStateAnalyzer`, `SyntheticDriverObservationProvider` | 4.3 |
| `risk`         | core   | Legacy `RiskEngine`/`RiskInput`/`BaselineRiskEngine` retained; Stage 4.2 `RoadRiskEvaluator`/`RoadRiskEngine`, `RoadRiskSnapshot`, `RiskLevel`/`RiskReason`; Stage 4.3 `DriverRiskEngine(Snapshot)`, `CombinedRiskEngine(Snapshot/Processor)` | 0 / 4.2 / 4.3 |
| `config`       | core   | `ZholSafeConfig` root + `DetectorConfig`, `TrackingConfig`, `RiskConfig`, `DriverGuardConfig`, `CombinedRiskConfig`, `NetworkConfig` | 0 / 4.3 |
| `pipeline`     | core   | Ports: `FrameSource`, `LatestFrameQueue`, `AlertSink`, `HazardEventPublisher`, `PipelineState` | 0 |
| `logging`      | core   | `ZLog` façade + standard `Event` names                                          | 0 |
| `camera`       | app    | `RoadCamera`, `DriverCamera` (CameraX → `Frame`)                                | 1 |
| `alert`        | app    | `AlertManager` implements `AlertSink` (audio + visual)                           | 1/4 |
| `location`     | app    | `LocationService` → `GeoPosition`                                                | 1/6 |
| `network`      | app    | `ZholNetApi` (REST, offline queue), `ZholNetWebSocket` (untrusted inbound)       | 6 |
| `ui`           | app    | Activities/views only — **no business logic**                                    | 1+ |
| `app`          | app    | `ZholSafeApplication` (config assembly, log sink)                                | 0 |

## 5. Processing pipeline (RoadGuard)

```
CameraX ImageProxy ──(camera analysis executor)──► CameraFrameAdapter → Frame (pooled NV21)
   ──► FramePipeline.onFrame → LatestFrameQueue (single slot, drop-oldest, counted)
   ──(processing executor "zs-processing")──► FrameProcessor.process(Frame)
        Stage 1: DiagnosticFrameProcessor (counters, dims, rotation, cheap luma stat)
        Stage 2: detector processor → preprocess → OnnxModel.run → postprocess/NMS → List<Detection>
   ──► ObjectTracker.update → TrackingSnapshot (Stage 3)
   ──► TrajectoryEstimator.estimate → TrajectorySnapshot (Stage 4.0 IMAGE ONLY)
   ──► PhysicalEstimationProcessor → PhysicalEstimationSnapshot (Stage 4.1 diagnostics)
   ──► RoadRiskEngine → RoadRiskSnapshot (Stage 4.2 engineering diagnostic ONLY)
   ──► [AlertSink / HazardEventPublisher: future, NOT connected]

Stage 4.3 driver pipeline (separate FramePipeline of the same shape, NOT in the road processor):
Driver FrameSource ──► LatestFrameQueue (single slot, drop-oldest) ──► DriverGuardProcessor
   ──► DriverObservationProvider.provide(Frame) → DriverObservation
   ──► TemporalDriverStateAnalyzer.update → DriverState (source-time temporal evidence)
   ──► DriverRiskEngine.evaluate → DriverRiskSnapshot
   ──► CombinedRiskProcessor.updateRoad/updateDriver → CombinedRiskEngine → CombinedRiskSnapshot
```

**Detection ≠ Risk.** A detection states presence; risk is a function of class weight,
confidence, corridor position, trajectory, distance/TTC *if available*, driver state and vehicle
context. `RiskEngineContractTest.detectionAloneIsNotCritical` guards this.

### 5.1 Stage 1 camera pipeline (implemented)

Core (`kz.zholsafe.pipeline`, pure Java, JVM-tested):

| Class | Role |
|---|---|
| `FrameSource` | Producer contract (`start(listener)`, `stop()`); LIVE and DEMO differ **only** here. |
| `LatestFrameQueue` | Capacity-1 hand-off. `offer()` returns the displaced frame so its buffer is recycled immediately; `clear()` drains at shutdown. |
| `FramePipeline` | Owns the single processing executor, wires source → queue → processor, maintains `PipelineTelemetry`/`PipelineState`, catches processor exceptions. |
| `FrameProcessor` | **Stage 2 insertion point** — one frame in, nothing out yet. |
| `DiagnosticFrameProcessor` | Stage 1 placeholder: dims/rotation/timestamp + sparse mean-luma. Not detection. |
| `PipelineTelemetry` | Counters (received / processed / droppedOrReplaced / errors), last dims, rotation, timestamp, EMA FPS. |
| `TelemetryReport` | Renders the engineering overlay text (unit-tested wording). |
| `FrameBufferRecycler` | Optional source hook: pipeline returns frames after processing / displacement / shutdown. |
| `SyntheticFrameSource` | Deterministic DEMO/TEST source (moving NV21 gradient, double-buffered). |

App (`kz.zholsafe.camera`, the only package importing `androidx.camera.*`):

| Class | Role |
|---|---|
| `RoadCamera` | `FrameSource` over CameraX: `ProcessCameraProvider` → `DEFAULT_BACK_CAMERA` (fails to UNAVAILABLE if absent) → `Preview` + `ImageAnalysis(STRATEGY_KEEP_ONLY_LATEST, YUV_420_888, target 1280×720)` bound to the Activity lifecycle. |
| `CameraFrameAdapter` | `ImageProxy` → packed NV21 `Frame` using a fixed pool of 3 direct buffers. Handles interleaved (fast, bulk row copy) and planar (generic) chroma layouts. |

`kz.zholsafe.ui.PipelineController` (plain Java) selects the source per mode and owns the
`FramePipeline`; `MainActivity` only does permission UX, lifecycle and rendering.

**ImageProxy ownership.** The proxy never leaves `RoadCamera.analyze()`: it is closed in a
`finally` on every path (normal, pool exhausted → frame skipped, conversion exception, camera
already stopping). Only width, height, rotation, timestamp and pixel bytes are copied into the
core `Frame`. Frame buffers are returned to the pool via `FrameBufferRecycler` after processing,
on displacement in the queue, and when the queue is drained at `stop()`. Steady-state allocation
per frame is one small `Frame` record.

**Backpressure** is two-stage and both stages are bounded: CameraX keeps only the latest image
while the analyzer is busy; `LatestFrameQueue` keeps only the latest `Frame` while the processor
is busy (counted as `droppedOrReplacedFrames`). Nothing anywhere can grow with load.

**Timestamps (two clock domains, never mixed).** `Frame.timestampNanos` is the *source/image*
timestamp — for `RoadCamera` it is CameraX `ImageInfo.getTimestamp()` (camera capture time,
device clock domain per Camera2 `SENSOR_INFO_TIMESTAMP_SOURCE`); for `SyntheticFrameSource` it is
the source's injected clock. It is not wall-clock and not arrival time. Tracking (Stage 3),
image trajectory (Stage 4.0) and physical range-rate (Stage 4.1) must use source-time
differences between observations of the same track from the same `CameraSource` only.
Processing duration and FPS in `PipelineTelemetry` use the pipeline's local `System.nanoTime()`
clock; the pipeline never subtracts a frame timestamp from its local clock.

**Rotation.** `Frame.rotationDegrees` = CameraX `ImageInfo.getRotationDegrees()` (clockwise
rotation that makes the stored buffer upright for the current display orientation; derived from
sensor orientation and display rotation). Pixels are **not** rotated in Stage 1 — that would be a
full copy per frame. Stage 2 must apply it during tensor preprocessing (rotate-while-resize is
free) and either map boxes back into stored coordinates or document that detections are in
upright coordinates. `Frame.uprightWidth()/uprightHeight()` are provided for that purpose.

**Process lifetime.** The pipeline runs only while `MainActivity` is started; there is no
foreground service and no background operation in Stage 1 (permissions for it are not declared).

**CameraX lifecycle.** `start()`/`stop()` run on the main thread. Use cases are bound to the
Activity's `LifecycleOwner`; `onStop()` → `PipelineController.stop()` → `FramePipeline.stop()`
→ `RoadCamera.stop()` (clearAnalyzer, unbindAll, terminate analysis executor, clear pool) and
the processing executor is shut down with `shutdownNow()` + `awaitTermination(2s)`.

**Permission.** CAMERA is requested once per session when LIVE starts. Denied → pipeline
`UNAVAILABLE — CAMERA permission denied`, no crash, no automatic re-prompt; the user can tap
"Retry camera" or switch to DEMO.

**Pipeline states (Stage 1 usage).** `NOT_STARTED` → `STARTING` (source started, no frame yet)
→ `RUNNING` (first frame) ⇄ `DEGRADED` (last frame threw; recovers on next success) ;
`UNAVAILABLE` (permission missing, no rear camera, init/bind failure, source error) ; `STOPPED`.

### 5.2 Stage 2 RoadGuard detector (implemented)

```
Frame(NV21, rotation, source ts)
  → RoadDetectionProcessor (FrameProcessor, processing thread)
    → RoadDetector.detect(frame)                       core interface, Android-free
      = OnnxRoadDetector                               model-agnostic; everything from ModelSpec
        ├─ Nv21Preprocessor: NV21 → RGB, logical rotation, letterbox, normalise → float[] (reused)
        ├─ TensorSession.run(...)                      ONNX boundary (:ort-adapter OrtTensorSession over ONNX Runtime)
        ├─ DetectionDecoder (YoloRawDecoder | YoloEnd2EndDecoder) → RawDetection[] (model-input px)
        ├─ Nms.classAware (only if decoder.requiresNms())
        ├─ LabelMap: model index → label → ObjectClass; unsupported → DROPPED
        └─ LetterboxTransform.toSource → clamp → Detection[] (upright source px)
    → DetectionSnapshot (latest only) → engineering UI / overlay
```

| Element | Where | Notes |
|---|---|---|
| `RoadDetector`, `DetectorState`, `DetectorInfo`, `DetectorTimings`, `DetectionException` | core `ai` | contract; exposes no ORT/ImageProxy/Bitmap/Context |
| `ModelSpec` + `ModelSpecParser` | core `ai.spec` | one JSON per model dir; consistency validated (e.g. end2end ⇒ nmsInModel) |
| `TensorSession`, `TensorSessionFactory`, `ModelFiles` | core `ai.infer` | ONNX implementation boundary; `:ort-adapter` (pure JVM, shared by app + smoke-test) supplies `OrtTensorSession`/`OrtSessionFactory`; app supplies `AssetModelFiles` |
| `Nv21Preprocessor`, `LetterboxTransform` | core `ai.preprocess` | pixel-tested for 0/90/180/270, landscape/portrait/square letterbox, inverse + clamp |
| `YoloRawDecoder`, `YoloEnd2EndDecoder`, `Nms` | core `ai.decode` | one decoder per real output contract; fail-fast shape validation |
| `LabelMap` | core `ai` | label-string mapping only; aliases are label→label |
| `RoadDetectionProcessor`, `DetectionSnapshot`, `DetectionReport` | core `pipeline` | Stage 2 FrameProcessor + latest snapshot + UI text |
| `DetectorBenchmark`, `BenchmarkResult`, `AccuracyResult`, `EvaluationCategory` | core `benchmark` | identical-conditions harness; no results yet |
| `FakeRoadDetector` | core `ai` | scripted; tests only — the app never instantiates it |

**Coordinate convention (single, binding).** `Detection.box` is in **pixels of the upright source
image** (`frame.uprightWidth × uprightHeight`, origin top-left). Model-input coordinates exist
only inside the detector (`RawDetection`); stored-buffer coordinates exist only inside
`Nv21Preprocessor`; view coordinates exist only inside `DetectionOverlayView`. `BoundingBox`
pixel semantics from Stage 0 are preserved; after postprocessing boxes are finite, clamped,
`x1<x2`, `y1<y2` (degenerate boxes discarded).

**Rotation.** The preprocessor maps each model pixel → upright pixel → stored pixel
(`90: x=uy, y=H-1-ux`; `180: x=W-1-ux, y=H-1-uy`; `270: x=W-1-uy, y=ux`). No rotated image is
materialised. Because the letterbox is computed on the upright size, inverse-transformed boxes
are already upright.

**Decoders.** Ultralytics exports two genuinely different output contracts: raw detect head
`[1, 4+nc, N]` (YOLOv8/YOLO11, `nms=False`; confidence = max class score; NMS in app) and
end2end `[1, K, 6]` = `x1,y1,x2,y2,conf,cls` (YOLO26 default head, YOLOv10, YOLO11 `nms=True`;
no NMS in app). The decoder is chosen by `ModelSpec.decoder`, never by model name; the wrong
pairing fails at load with `MODEL_INCOMPATIBLE: …received output shape […]`. NMS is never applied
twice.

**Tensor element types (Stage 2.1).** `TensorSession.TensorInfo.elementType` is the canonical
`TensorElementType` enum; `OrtTensorSession.canonicalType(OnnxJavaType)` maps the runtime enum
explicitly (never `toString()`). The detector requires `FLOAT32` for input and output and fails
at load with the offending type otherwise. Decoders are the trust boundary for model output:
every value entering a `RawDetection` is finite (NaN/±Inf in scores, coordinates, sizes or
confidence rejects the candidate; a NaN score can never win the argmax), and end2end class
indices must be finite, integer-valued and in `[0, numClasses)` — fractional values are rejected,
never truncated. `RawDetection`'s constructor enforces finiteness as a last line of defence.

**Model loading and failure policy.** `load()` = spec → labels (count must equal `numClasses`,
≥1 canonical class) → sha256 (if present) → session → I/O validation → READY. Any failure ⇒
`DetectorState.ERROR` with a diagnostic; the pipeline still runs (DEGRADED) and every frame is a
counted processing error. Per-frame `DetectionException` ⇒ counted, snapshot `available=false`;
25 consecutive failures or a runtime shape mismatch ⇒ ERROR. **NO DETECTIONS** (READY + empty
list) is always distinguishable from **DETECTION UNAVAILABLE** (`available=false`).

**Offline.** Model files come from APK assets (synced from `models/road/**` at build time) or the
app files dir. No download, no cloud inference, no INTERNET use by the detector.

**Model strategy.** YOLO26n and YOLO11n are configured as peers (`models/road/yolo26n`,
`models/road/yolo11n`); the active one is `DetectorConfig.roadModelDir`. Model selection is
DEFERRED until benchmarked under identical conditions. Pretrained COCO exports cover only
PERSON/DOG/HORSE/COW/SHEEP; GOAT/CAMEL need custom training.

### 5.3 Stage 3 RoadGuard tracking (implemented in core; device NOT VERIFIED)

`RoadDetectionProcessor` runs `ByteTrackInspiredTracker` synchronously after each successful
`RoadDetector.detect` and publishes a separate immutable `TrackingSnapshot`. The shared ONNX
inference/decoder is unchanged. Two class-exact greedy IoU passes (high-confidence primary,
low-confidence confirmed-track recovery) provide stable IDs, tentative/confirmed/lost states and
bounded metadata history. UNKNOWN only matches UNKNOWN. Detector failure publishes unavailable
tracking and freezes state (not a miss); successful empty detection advances the miss lifecycle.
`TrackedObject` retains its Stage 0 signature: `TrackView` carries state/hits/misses/history;
distance and TTC remain unavailable, movement UNKNOWN and corridor false. The overlay draws only
currently observed tentative/confirmed tracks, labelled with their IDs; LOST boxes are stale and
never drawn. See `docs/STAGE3_TRACKING.md` for defaults, failure/timestamp/cap policies and tests.
Stage 4.0 adds image trajectory downstream; Stage 4.1 adds experimental physical diagnostics
without changing these Stage 3 fields. Stage 4.2's road-only snapshot diagnostics also leave
them unchanged. No trajectory estimator ran in Stage 3 itself.

### 5.4 Stage 4.0 image-space trajectory (implemented in core; device NOT VERIFIED)

`RoadDetectionProcessor` runs `LinearImageTrajectoryEstimator` after successful tracking,
inline on the same processing thread; it publishes a separate immutable `TrajectorySnapshot`.
The estimator consumes only confirmed CURRENT `TrackView` observation history, fits normalized
centre X/Y and log(normalized box area) against the source timestamps, and rejects poor fits.
Tentative/LOST tracks have `TRACK_NOT_CURRENT`, not projected motion. Detector or tracker failure
publishes trajectory unavailable, whereas successful zero detections is READY (possibly empty).
All numerical output uses image fractions/second or dimensionless log-area/second; no physical
`Estimate`, `MovementClass` mutation, TTC or risk integration is performed. `docs/STAGE4_0_TRAJECTORY.md`
defines math, thresholds, timestamp/quality policy and limitations.

### 5.5 Stage 4.1 physical diagnostics (experimental core; device NOT VERIFIED)

Ground-plane contact and optional explicitly injected object-height priors produce method-labelled
camera optical-axis depth, not ground-path or slant distance. Metric relative range-rate uses only
bounded per-track physical distances and Stage 3 source times. Sustained Stage 4.0 GROWING yields
an independent LOW-quality uncalibrated optical TTC; a conflicting TTC invalidates the selected
diagnostic, never averages. Missing calibration, inaccurate flat-road assumption, stale/lost track,
upstream failure and absent evidence yield explicit unavailability. The processor holds only
bounded numeric samples and runs inline, without a second queue/camera/thread; the Risk Engine
and warnings are unchanged. The Stage 4.2 evaluator reads this snapshot without modifying
Stage 4.1 semantics. See `docs/STAGE4_1_DISTANCE_TTC.md`.

### 5.6 Stage 4.2 explainable ROAD risk (experimental core; no alerts/device validation)

`RoadDetectionProcessor` evaluates READY aligned tracking/image/physical snapshots on the same
processing thread. `RoadRiskEngine` is a stateless pure-Java road-only evaluator; it reuses the
Stage 0 `RiskLevel` and `RiskReason` but takes richer snapshots through `RoadRiskEvaluator`
because `RiskInput` has neither source/quality/failure lineage nor a road-only driver-free
contract. The legacy baseline remains source compatible; no Stage 4.2 driver fusion is performed.
READY output is a per-confirmed-object severity with named components/structured evidence and a
max frame level; failures have **no** level, not NORMAL. Geometry is an approximate normalized
trapezoid, not lane detection; metric and optical TTC remain distinct. Engineering scores are
NOT collision probabilities and are not alerts. See `docs/STAGE4_2_RISK_ENGINE.md`.

## 6. DriverGuard (Stage 4.3) and road/driver risk fusion

Backend-agnostic observation port (`DriverObservationProvider`; deterministic
`SyntheticDriverObservationProvider` shipped, `DriverDetector` as the model-lifecycle variant for a
future MediaPipe landmark backend — NOT VERIFIED) → `DriverObservation` (continuous per-eye
openness, optional mouth/head evidence, explicit unavailability) →
`TemporalDriverStateAnalyzer` (source-time eye-closure runs, bounded time-weighted PERCLOS-like
window, yawn-like and head-direction persistence, missing ≠ open/closed, duplicate/reversed
timestamp rejection) → `DriverState` → driver-only `DriverRiskEngine` → `DriverRiskSnapshot`
(structured reasons mandatory for non-NORMAL; engineering severity, not a medical statement).
Fusion: `CombinedRiskEngine` — deterministic max-matrix with the WARNING+WARNING⇒CRITICAL
escalation (`COMBINED_HAZARD_ESCALATION`), source-time freshness/skew budgets
(`CombinedRiskConfig`, `STALE_*`/`ROAD_DRIVER_TIMESTAMP_SKEW` reasons) and explicit single-source
degraded modes (`ROAD_ONLY`/`DRIVER_ONLY`/`UNAVAILABLE`). All thresholds live in
`DriverGuardConfig`/`CombinedRiskConfig` and are **experimental demo values, not medical or
regulatory thresholds** (stated in code and docs). DriverGuard is an engineering prototype, NOT a
medical diagnostic or clinically validated microsleep detector. Detail: `docs/STAGE4_3_DRIVERGUARD.md`.

## 7. Risk Engine

- Interface: `RiskEngine.evaluate(RiskInput) → RiskAssessment`.
- Pure, deterministic, configuration-driven (`RiskConfig`), explainable (`RiskReason` list is
  mandatory for any level above `NORMAL`, enforced by the record constructor).
- Stage 0 `BaselineRiskEngine` remains source compatible for legacy scalar/combined input;
  Stage 4.2 **does not call** it in the road pipeline, because `RiskInput` cannot express
  synchronized source/quality/failure lineage and includes deferred driver fields. Stage 4.2
  reuses `RiskLevel`/`RiskReason` through the snapshot-specific `RoadRiskEvaluator` and
  `RoadRiskEngine`, identically for LIVE and DEMO. No driver fusion or alert path is added.

## 8. Distance and TTC policy

The legacy scalar `Estimate { available, value, method }` remains the Stage 0 `TrackedObject` /
Risk Engine contract; default legacy `DistanceEstimator`/`TtcEstimator` remain UNAVAILABLE.
Stage 4.1 deliberately adds **separate** immutable `physical.DistanceEstimate`,
`RangeRateEstimate`, `TtcEstimate`, and `PhysicalEstimationSnapshot` contracts with source time,
method, units, evidence quality, bounds where known and explicit NaN/reason for missing data.
`RoadDetectionProcessor` publishes these diagnostics after Stage 4.0 on its existing processing
thread. Stage 4.2 reads their separate snapshot for road-only risk but does NOT copy values
into `TrackedObject`, legacy RiskInput or warning logic. App defaults to no
calibration/prior and can show only uncalibrated optical-expansion TTC, explicitly labelled.
Optional FOV-derived intrinsics and class-size guesses are experimental, never safety-certified.
No source calibration → no metric range/rate/TTC. See `docs/STAGE4_1_DISTANCE_TTC.md` for geometry,
assumptions, source-time gating and failure modes. Stage 4.2 evaluator details are in
`docs/STAGE4_2_RISK_ENGINE.md`; driver fusion remains deferred.

## 9. Threading model

| Thread                | Owns                                             | Must never                          |
|-----------------------|--------------------------------------------------|-------------------------------------|
| UI (main)             | Views, alert rendering/audio triggers            | run inference, block on network     |
| Camera analysis executor (`zs-camera-analysis`, 1 thread) | ImageProxy → `Frame` (pool copy), `FramePipeline.onFrame` → `LatestFrameQueue.offer`, close ImageProxy | do heavy work; block; hold ImageProxy |
| Processing executor (`zs-processing`, 1 thread, owned by `FramePipeline`) | `FrameProcessor` → `RoadDetectionProcessor` → `OnnxRoadDetector` (preprocess + ORT run + decode + NMS) → tracker → image-space estimator → physical diagnostics → road risk evaluator (serial) | touch views; run in parallel (serial by design until measured) |
| Risk thread (or inline after inference, bounded) | `RiskEngine`, `DrowsinessAnalyzer` | block on I/O          |
| Network thread        | `ZholNetApi`, `ZholNetWebSocket`, offline queue  | influence the alert path            |

Backpressure: `LatestFrameQueue` keeps one pending frame; older frames are dropped and counted.
No unbounded buffers anywhere in the pipeline. No thread is created per frame; the DEMO source
uses one thread of its own (`zs-demo-source`). Executors are terminated on `stop()`.

## 10. Failure behaviour (fail safe, fail transparent)

| Condition               | Behaviour                                                                      |
|-------------------------|--------------------------------------------------------------------------------|
| ONNX model missing      | `ModelNotAvailableException` → UI shows "model missing" diagnostic; RoadGuard state `UNAVAILABLE`; `RiskInput.roadDetectorOk=false` → reason `ROAD_DETECTOR_UNAVAILABLE`. **No fabricated detections in LIVE mode.** |
| Camera unavailable      | `FrameSource.Listener.onSourceError` → clear state; app stays up.               |
| GPS unavailable         | Local detection continues; events lack position and are not published.        |
| Internet unavailable    | Local Risk Engine unaffected; events queued (bounded, drop-oldest) per `NetworkConfig.offlineQueueCapacity`. |
| Server down             | Same as above; WebSocket reconnects with backoff; local alerts unaffected.      |
| Unknown class from model| Mapped to `ObjectClass.UNKNOWN` (still treated as a hazard with a weight), unmapped labels logged at load. |

## 11. ZholNet Server

Package root `kz.zholsafe.server` remains one Spring Boot application. Stage 5 adds validated
hazard ingestion, JPA/PostGIS persistence, Flyway migration, server-time TTL, conservative
same-source deduplication, bounded `ST_DWithin` nearby lookup, Actuator health and compact STOMP
broadcasts. REST nearby lookup is authoritative; per-client WebSocket geography is deferred.

Trust model: the legacy `vehicleId` wire field is an untrusted, anonymous/rotating source token;
it is not returned in public event DTOs. Inbound events from the server are untrusted on the
vehicle side too (they never feed the local Risk Engine as facts — future stages may surface them
as advisory notices only).

## 12. Communication

- Vehicle → Server: HTTPS REST `POST /api/v1/hazards` (compact JSON `HazardEvent` v1, no video).
- Server → Vehicle: Stage 5 STOMP `/ws/hazards` → `/topic/hazards` compact global broadcast;
  geographic subscription filtering is deferred to Stage 6.
- Evidence snapshots are not accepted in Stage 5; media and biometrics are never persisted.

## 13. Modes

`ZholSafeConfig.OperatingMode { LIVE, DEMO }` selects only the `FrameSource` (camera vs.
file/scenario). Detector, tracker, Risk Engine and alert path are identical. Demo output is
labelled as demo in the UI; no performance claims may be derived from demo playback.

## 14. Deferred decisions (explicitly open)

- Exact YOLO variant/size and ONNX Runtime execution provider (NNAPI vs CPU) — Stage 2, by measurement.
- Driver model type (classifier vs. landmarks) — deferred Stage 4.3.
- Map library for the web monitor (Leaflet planned) — Stage 6.
- Production authentication, abuse controls and cross-vehicle corroboration.

## Stage 2.5 — module split for the real-model smoke test

```
:core         pure Java — detector, preprocess, decoders, NMS, LabelMap (unchanged)
:ort-adapter  pure JVM Java — OrtTensorSession/OrtSessionFactory (ai.onnxruntime.* only, no android.*)
:app          Android — CameraX, UI, AssetModelFiles; runtime = onnxruntime-android AAR
:smoke-test   desktop — SmokeTestRunner: JPEG/PNG → NV21 Frame → OnnxRoadDetector → real ORT jar
```

There is exactly one ORT adapter implementation. The desktop harness proves the production chain
with a real graph (`docs/STAGE2_5_REAL_MODEL_TEST.md`); it is not shipped in the APK.
