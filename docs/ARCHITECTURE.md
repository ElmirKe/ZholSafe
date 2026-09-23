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
 │                DriverDetector ──► DrowsinessAnalyzer           │   │        │                  │
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
| `ai`           | core   | Inference contracts: `Frame`, `OnnxModel`, `RoadDetector`, `DriverDetector`, `LabelMap`, `ModelDescriptor`, `ModelNotAvailableException` | 0 (impl: 2/3) |
| `tracking`     | core   | `ObjectTracker`, `TrackedObject`, `MovementClass`                              | 0 (impl: 4) |
| `trajectory`   | core   | `TrajectoryEstimator`, `DistanceEstimator`, `TtcEstimator` (pluggable, honest) | 0 (impl: 4) |
| `driver`       | core   | `DriverObservation`, `DriverState`, `HeadPose`, `DrowsinessAnalyzer`           | 0 (impl: 3) |
| `risk`         | core   | `RiskEngine`, `RiskInput`, `RiskAssessment`, `RiskLevel`, `RiskReason`, `VehicleContext`, `BaselineRiskEngine` | 0 (final algo: 4) |
| `config`       | core   | `ZholSafeConfig` root + `DetectorConfig`, `TrackingConfig`, `RiskConfig`, `DriverGuardConfig`, `NetworkConfig` | 0 |
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
CameraX ImageProxy ──(camera thread)──► Frame ──► LatestFrameQueue (single slot, drop-oldest)
   ──(inference thread)──► preprocess → OnnxModel.run → postprocess/NMS → List<Detection>
   ──► ObjectTracker.update → List<TrackedObject> (+ TrajectoryEstimator, Distance/TtcEstimator)
   ──(risk thread or same thread, bounded)──► RiskEngine.evaluate(RiskInput) → RiskAssessment
   ──► AlertSink (UI thread for rendering/audio) ; HazardEventPublisher (network thread)
```

**Detection ≠ Risk.** A detection states presence; risk is a function of class weight,
confidence, corridor position, trajectory, distance/TTC *if available*, driver state and vehicle
context. `RiskEngineContractTest.detectionAloneIsNotCritical` guards this.

## 6. DriverGuard

`DriverDetector` (per-frame, Stage 3) → `DriverObservation` → `DrowsinessAnalyzer` (temporal:
eye-closure duration, PERCLOS window, recent yawn) → `DriverState` → `RiskInput`.
All thresholds live in `DriverGuardConfig` and are **experimental demo values, not medical or
regulatory thresholds** (stated in code and docs).

## 7. Risk Engine

- Interface: `RiskEngine.evaluate(RiskInput) → RiskAssessment`.
- Pure, deterministic, configuration-driven (`RiskConfig`), explainable (`RiskReason` list is
  mandatory for any level above `NORMAL`, enforced by the record constructor).
- Stage 0 ships `BaselineRiskEngine` so the pipeline can be wired and the contract tests are
  real. Stage 4 replaces its scoring internals — **not** the interface — and adds calibration
  hooks. There is exactly one Risk Engine for LIVE and DEMO.

## 8. Distance and TTC policy

`Estimate { available, value, method }` is the only way to carry distance/TTC. The default
estimators are `UNAVAILABLE`. A monocular uncalibrated heuristic (Stage 4) must tag itself
`MONOCULAR_UNCALIBRATED`; UI must render such values as approximate. When information is
insufficient, return `Estimate.unavailable()` — never a fabricated number.

## 9. Threading model

| Thread                | Owns                                             | Must never                          |
|-----------------------|--------------------------------------------------|-------------------------------------|
| UI (main)             | Views, alert rendering/audio triggers            | run inference, block on network     |
| Camera executor(s)    | ImageProxy → `Frame`, `LatestFrameQueue.offer`   | do heavy work; block                |
| Inference thread      | `RoadDetector`, `DriverDetector`, tracker        | touch views                         |
| Risk thread (or inline after inference, bounded) | `RiskEngine`, `DrowsinessAnalyzer` | block on I/O          |
| Network thread        | `ZholNetApi`, `ZholNetWebSocket`, offline queue  | influence the alert path            |

Backpressure: `LatestFrameQueue` keeps one pending frame; older frames are dropped and counted.
No unbounded buffers anywhere in the pipeline.

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

Package root `kz.zholsafe.server` with boundaries `vehicle/ hazard/ alert/ geospatial/
analytics/ websocket/ api/ config/`. Stage 0 provides: application class, `GET /api/v1/health`,
`HazardEventDto` (contract v1), `HazardEventValidator` (pure), `HazardType`/`HazardStatus`,
documented security extension points, `application.yml` with env-driven settings and no secrets.
Persistence (PostgreSQL + PostGIS), WebSocket and geospatial queries are Stage 5/6.

Trust model: `vehicleId` is self-declared; inbound events from the server are untrusted on the
vehicle side too (they never feed the local Risk Engine as facts — future stages may surface them
as advisory notices only).

## 12. Communication

- Vehicle → Server: HTTPS REST `POST /api/v1/hazards` (compact JSON `HazardEvent` v1, no video).
- Server → Vehicle: WebSocket `/ws/hazards` for nearby-hazard notifications.
- Optional evidence snapshots: reserved field `evidenceReference`; **not** part of the MVP.

## 13. Modes

`ZholSafeConfig.OperatingMode { LIVE, DEMO }` selects only the `FrameSource` (camera vs.
file/scenario). Detector, tracker, Risk Engine and alert path are identical. Demo output is
labelled as demo in the UI; no performance claims may be derived from demo playback.

## 14. Deferred decisions (explicitly open)

- Exact YOLO variant/size and ONNX Runtime execution provider (NNAPI vs CPU) — Stage 2, by measurement.
- Driver model type (classifier vs. landmarks) — Stage 3.
- Map library for the web monitor (Leaflet planned) — Stage 6.
- Migration tool for the database (Flyway/Liquibase) — Stage 5.
