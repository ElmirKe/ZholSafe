# ZholSafe

Intelligent road-safety system for intercity and rural highways in Kazakhstan.

ZholSafe combines on-device driver monitoring (fatigue / microsleep), road-scene computer vision
(animals, pedestrians and other hazards), tracking and trajectory analysis, a local explainable
Risk Engine and immediate driver alerts — all working **offline** — with **ZholNet**, a
supplementary network that shares GPS-tagged hazard events between nearby vehicles and builds a
dynamic geospatial risk map.

Target hazards: **horse, cow, sheep, goat, camel, dog, person** (extensible).

> **Status: Stage 4.4 — Android app builds (Gradle wrapper committed); 284 core + 14 app JVM tests pass. Front camera → MediaPipe Face Landmarker → DriverGuard → combined risk → local alerts (siren, vibration, RU/KK/EN voice) and a driver screen are implemented. Run on a physical phone NOT VERIFIED yet; road `model.onnx` must still be exported. Thresholds remain EXPERIMENTAL; no field validation.**
> Stage 2.5 verified real YOLO11n inference on desktop/JVM (tested weights upstream provenance
> UNCONFIRMED). Model binaries are not committed. See `docs/STAGE2_5_REAL_MODEL_TEST.md`,
> `docs/STAGE3_TRACKING.md`, `docs/STAGE4_0_TRAJECTORY.md`,
> `docs/STAGE4_1_DISTANCE_TTC.md`, `docs/STAGE4_2_RISK_ENGINE.md`,
> `docs/STAGE4_3_DRIVERGUARD.md` and `docs/ROADMAP.md`.

## Architecture in one picture

```
Road camera → LatestFrameQueue → RoadDetector → ObjectTracker
                                         → ImageTrajectory → PhysicalDiagnostics
                                         → RoadRiskSnapshot (diagnostic only; no alerts)
Driver frames → DriverObservationProvider → TemporalDriverStateAnalyzer
                                         → DriverState → DriverRiskSnapshot
RoadRiskSnapshot + DriverRiskSnapshot → CombinedRiskEngine → CombinedRiskSnapshot
                                         (rule matrix, freshness budgets, degraded modes)

Future only: AlertManager on the combined risk; ZholNet events → optional cloud map.
```

Stage 4.2 is road-object-only. Its physical diagnostics use explicit calibration (none
configured by default), flat-road or opt-in object-height assumptions, bounded source-time
samples, and separate LOW-quality uncalibrated optical TTC. The road-only risk evaluator reads
their immutable snapshot; it does **not** use the legacy aggregate `RiskInput`, driver state or
AlertSink. Without calibration, metric depth/range-rate/TTC remain unavailable, not zero; the
evaluator may use LOW-quality image evidence. Its severity score is **NOT a collision
probability**. The eventual local alert path must work offline; it is not connected here.
Details: `docs/ARCHITECTURE.md` and `docs/STAGE4_2_RISK_ENGINE.md`.

Stage 4.3 adds the driver side and the fusion layer in the same pure-Java spirit:
`DriverObservationProvider` (backend-agnostic port; deterministic synthetic provider shipped;
MediaPipe/ML Kit NOT integrated), `TemporalDriverStateAnalyzer` (source-time eye closure,
bounded time-weighted PERCLOS, yawn-like and head-direction persistence, explicit
missing-vs-open/closed semantics), `DriverRiskEngine` (driver-only, explainable) and
`CombinedRiskEngine` (deterministic rule matrix, source-time freshness budgets, degraded
single-source modes). DriverGuard is an engineering prototype — **not** a medical diagnostic or
clinically validated microsleep detector. All thresholds are EXPERIMENTAL demo values. Details:
`docs/STAGE4_3_DRIVERGUARD.md`.

## Technologies

| Part                         | Technology                                   |
|------------------------------|----------------------------------------------|
| Vehicle application          | Java, Android (CameraX), Gradle              |
| Local inference              | Java + ONNX Runtime (no Python at runtime, no C++/JNI at this stage) |
| Local Risk Engine / alerts   | Pure Java (`android-app/core`)               |
| ZholNet server               | Java 21, Spring Boot 3.3, Maven              |
| Database                     | PostgreSQL + PostGIS                         |
| AI development               | Python (dataset prep, training, validation, benchmarks, ONNX export only) |
| Web monitor / risk map       | Plain HTML/CSS/JS                            |

## Repository layout and module responsibilities

| Directory          | Responsibility |
|--------------------|----------------|
| `android-app/core` | Pure-Java contracts, road detection, Stage 3 tracking, Stage 4.0 image trajectory, Stage 4.1 physical diagnostics, Stage 4.2 road-only risk, Stage 4.3 DriverGuard temporal analysis + driver-only risk + combined road/driver risk fusion, configuration, pipeline ports, baseline `RiskEngine`, unit tests |
| `android-app/app`  | Android Java app: CameraX road + driver cameras, MediaPipe DriverGuard backend, local alerts, driver screen (RU/KK/EN) + engineering overlay; location and ZholNet client deferred |
| `zholnet-server`   | Spring Boot server: health endpoint, hazard event DTO + validator, module boundaries |
| `ai-training`      | Python tooling: class registry, training/validation/export entry points, tests |
| `models`           | Where ONNX models must be placed (binaries not committed) + label files |
| `database`         | PostGIS schema design (`schema/001_init.sql`) |
| `web`              | Static monitor page (risk map in Stage 6) |
| `tests`            | Cross-module contract schema + fixtures |
| `demo`             | Demo-mode scenarios (same pipeline as live) |
| `scripts`          | Build fallbacks and contract checks |
| `docs`             | `ARCHITECTURE.md`, `DATA_CONTRACTS.md`, `DEVELOPMENT.md`, `ROADMAP.md` |

## Build / run (what is possible today)

```bash
# ZholNet server (JDK 21 + Maven)
cd zholnet-server && mvn test && mvn package
java -jar target/zholnet-server-0.0.1-SNAPSHOT.jar      # GET http://localhost:8080/api/v1/health

# Core Java module + Android app (JDK 17, Android SDK 34). Gradle 8.7 wrapper committed.
cd android-app && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
#   or without Gradle: scripts/jvm-fallback-build.sh (see header for required env vars)

# Android app (Stage 4.4): front camera → Face Landmarker → DriverGuard → combined risk → alerts;
# rear camera → road pipeline (Stage 4.2). APK assembly EXECUTED; device run NOT VERIFIED yet.
# The face model is downloaded + SHA-256-checked by the build. The road model is not committed:
# export it under models/road/<id>/ (models/README.md) or the app reports MODEL NOT AVAILABLE.
# See docs/STAGE4_4_ANDROID_DRIVER_APP.md.

# AI utilities
cd ai-training && pip install -r requirements.txt && pytest

# Contract fixture check
python3 scripts/check_contracts.py
```

## Development stages

0/0.1 Foundation & contracts ✔ → 1/1.1 CameraX pipeline ✔ (code; device pending) →
2/2.1 RoadGuard ONNX detection ✔ (code/JVM) → 2.5 real YOLO11n desktop/JVM smoke ✔
(Android pending) → 3 RoadGuard tracking ✔ (code/JVM; device pending) → 4.0 image-space trajectory ✔ (code/JVM) →
4.1 experimental distance/TTC foundation ✔ (code/JVM; real calibration/device pending) →
4.2 explainable road-only risk diagnostics ✔ (code/JVM; field validation pending) →
4.3 DriverGuard + road/driver risk fusion ✔ (code/JVM; 284 core tests pass) →
4.4 Android DriverGuard backend (MediaPipe), alerts, driver screen RU/KK/EN ✔ (APK builds; device pending) →
5 ZholNet server + PostGIS → 6 Integration/WebSocket/map → 7 Testing, profiling, audit. See `docs/ROADMAP.md`.

## Honesty policy

Nothing in this repository simulates success: missing models fail loudly, approximate values are
labelled as estimates, and unmeasured performance is reported as **NOT MEASURED**.
