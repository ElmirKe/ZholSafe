# ZholSafe — Roadmap

Priorities: **P0** road hazard detection, driver state detection, local Risk Engine, local
warning · **P1** tracking, trajectory, risk zone · **P2** GPS hazard events, ZholNet, WebSocket
warning to other vehicles · **P3** risk map, historical analytics, advanced UI.
Never sacrifice a working P0 pipeline for P2/P3 features.

| Stage | Scope | Exit criteria | Status |
|-------|-------|---------------|--------|
| **0.1** | Contract hardening: strict v1 enum validation on the server (UNKNOWN legitimate, malformed rejected), NaN/∞ rejection across numeric contracts, DriverState/TrackedObject/VehicleContext invariants, distance/TTC sign semantics, configuration constructor validation | All tests pass; no architecture change | **DONE** |
| **0** | Architecture & foundation: monorepo, docs, data contracts, enums/interfaces, baseline Risk Engine + contract tests, Spring Boot skeleton with health test, Android skeleton, ai-training scaffold, config placeholders | Core tests pass; server builds & health test passes; docs frozen | **DONE** (this branch) — see Stage 0 report |
| **1** | Java Android foundation + CameraX: permissions, road + driver camera `FrameSource`s, foreground service, `LatestFrameQueue` wiring, status UI, `AlertManager` (basic audio/visual), Gradle wrapper, DEMO `FrameSource` from file | App runs on device; frames flow to a no-op detector; pipeline states visible; UI thread never blocked | planned |
| **2** | RoadGuard: Java + ONNX Runtime `OnnxRuntimeModel`, `YoloDetector` (preprocess/postprocess/NMS), model asset packaging, `ai-training` dataset prep + training + validation + ONNX export for initial classes | Real detections from `models/road/zholsafe-road.onnx` on device; graceful failure when missing; measured (not estimated) inference latency recorded | planned |
| **3** | DriverGuard: `DriverDetector` implementation, `DrowsinessAnalyzer` (eye-closure duration, PERCLOS, yawn), experimental thresholds documented | `DriverState` produced live; unit tests for temporal logic; thresholds labelled experimental | planned |
| **4** | Tracking + trajectory + Risk Engine v1: IoU tracker, `TrajectoryEstimator`, uncalibrated monocular `DistanceEstimator`/`TtcEstimator` (honest `Estimate`s), corridor logic, Risk Engine scoring replacing baseline internals, scenario replay from `demo/scenarios` | Contract tests still pass; scenario tests reproduce expected escalation; explainable outputs | planned |
| **5** | ZholNet: Spring Boot + PostgreSQL/PostGIS persistence, `POST /api/v1/hazards`, validation & expiration, `GET /api/v1/hazards/nearby`, migrations | Server integration tests against PostGIS; contract schema v1 honoured | planned |
| **6** | Integration: `ZholNetApi` + offline queue, `ZholNetWebSocket`, `LocationService`, server WebSocket broadcast, web risk map (plain JS) | Vehicle A event visible to vehicle B and on the map; local alert path unaffected when offline | planned |
| **7** | Testing, profiling, optimisation, final audit: device profiling, drop-rate/latency measurement, calibration of `RiskConfig`, security hardening plan, documentation audit | Measured numbers published with device/conditions; known limitations listed; C++ considered only if profiling demands it | planned |

## Explicitly out of scope until instructed

C++/JNI, Kotlin, Python inference server, microservices, React or other frontend frameworks,
production authentication, fabricated models or metrics.
