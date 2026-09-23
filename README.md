# ZholSafe

Intelligent road-safety system for intercity and rural highways in Kazakhstan.

ZholSafe combines on-device driver monitoring (fatigue / microsleep), road-scene computer vision
(animals, pedestrians and other hazards), tracking and trajectory analysis, a local explainable
Risk Engine and immediate driver alerts — all working **offline** — with **ZholNet**, a
supplementary network that shares GPS-tagged hazard events between nearby vehicles and builds a
dynamic geospatial risk map.

Target hazards: **horse, cow, sheep, goat, camel, dog, person** (extensible).

> **Status: Stage 4.2 experimental explainable road-risk diagnostics implemented in pure Java; Android/device and physical accuracy NOT VERIFIED. No production alerts.**
> Stage 2.5 verified real YOLO11n inference on desktop/JVM (tested weights upstream provenance
> UNCONFIRMED). Model binaries are not committed. See `docs/STAGE2_5_REAL_MODEL_TEST.md`,
> `docs/STAGE3_TRACKING.md`, `docs/STAGE4_0_TRAJECTORY.md`,
> `docs/STAGE4_1_DISTANCE_TTC.md`, `docs/STAGE4_2_RISK_ENGINE.md` and `docs/ROADMAP.md`.

## Architecture in one picture

```
Road camera → LatestFrameQueue → RoadDetector → ObjectTracker
                                         → ImageTrajectory → PhysicalDiagnostics
                                         → RoadRiskSnapshot (diagnostic only; no alerts)

Future only: DriverGuard → combined risk / AlertManager; ZholNet events → optional cloud map.
```

Stage 4.2 is road-object-only. Its physical diagnostics use explicit calibration (none
configured by default), flat-road or opt-in object-height assumptions, bounded source-time
samples, and separate LOW-quality uncalibrated optical TTC. The road-only risk evaluator reads
their immutable snapshot; it does **not** use the legacy aggregate `RiskInput`, driver state or
AlertSink. Without calibration, metric depth/range-rate/TTC remain unavailable, not zero; the
evaluator may use LOW-quality image evidence. Its severity score is **NOT a collision
probability**. The eventual local alert path must work offline; it is not connected here.
Details: `docs/ARCHITECTURE.md` and `docs/STAGE4_2_RISK_ENGINE.md`.

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
| `android-app/core` | Pure-Java contracts, road detection, Stage 3 tracking, Stage 4.0 image trajectory, Stage 4.1 physical diagnostics and Stage 4.2 road-only risk, configuration, pipeline ports, baseline `RiskEngine`, unit tests |
| `android-app/app`  | Android Java app: CameraX road pipeline + engineering overlay; alerts, location and ZholNet client deferred |
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

# Core Java module (JDK 17+). Gradle wrapper NOT yet committed: the authoring sandbox cannot
# reach services.gradle.org and wrapper binaries are never fabricated.
cd android-app && gradle :core:test
#   or without Gradle: scripts/jvm-fallback-build.sh (see header for required env vars)

# Android app (Stage 4.2 code: detector → tracker → trajectory → physical → road-risk diagnostics; no alerts).
# No model binaries are committed: place a legitimate export under models/road/<id>/ first
# (models/README.md) or the app reports MODEL NOT AVAILABLE. Local/offline inference only.
# Open android-app/ in Android Studio (SDK 34) → run `app`. Assembling the APK was NOT EXECUTED
# in the authoring environment (no Android SDK / Google Maven access); app sources were
# compiled against API-shaped stubs only. See docs/DEVELOPMENT.md for the on-device test plan.

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
4.2 explainable road-only risk diagnostics ✔ (code/JVM; field validation pending) → 4.3 DriverGuard (deferred) →
5 ZholNet server + PostGIS → 6 Integration/WebSocket/map → 7 Testing, profiling, audit. See `docs/ROADMAP.md`.

## Honesty policy

Nothing in this repository simulates success: missing models fail loudly, approximate values are
labelled as estimates, and unmeasured performance is reported as **NOT MEASURED**.
