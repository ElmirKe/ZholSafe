# ZholSafe

Intelligent road-safety system for intercity and rural highways in Kazakhstan.

ZholSafe combines on-device driver monitoring (fatigue / microsleep), road-scene computer vision
(animals, pedestrians and other hazards), tracking and trajectory analysis, a local explainable
Risk Engine and immediate driver alerts — all working **offline** — with **ZholNet**, a
supplementary network that shares GPS-tagged hazard events between nearby vehicles and builds a
dynamic geospatial risk map.

Target hazards: **horse, cow, sheep, goat, camel, dog, person** (extensible).

> **Status: Stage 6.0 Android ZholNet client foundation implemented: validated foreground location,
> local-risk hazard bridge, asynchronous HTTP publish/nearby client and bounded metadata retry.
> Stage 5 ZholNet server is implemented with Java 21, Spring Boot,
> PostgreSQL/PostGIS, bounded REST nearby queries, server-time TTL, conservative deduplication and
> compact STOMP broadcasts; real PostGIS runtime verification remains pending. LOCAL SAFETY DOES
> NOT DEPEND ON ZHOLNET. Android/device, real driver
> camera, real-road operation and production safety are NOT VERIFIED. No production alerts.**
> Stage 2.5 verified real YOLO11n inference on desktop/JVM (tested weights upstream provenance
> UNCONFIRMED). Model binaries are not committed. See `docs/STAGE2_5_REAL_MODEL_TEST.md`,
> `docs/STAGE3_TRACKING.md`, `docs/STAGE4_0_TRAJECTORY.md`,
> `docs/STAGE4_1_DISTANCE_TTC.md`, `docs/STAGE4_2_RISK_ENGINE.md`,
> `docs/STAGE4_3_DRIVERGUARD.md`, `docs/STAGE5_ZHOLNET.md`,
> `docs/STAGE6_0_ANDROID_ZHOLNET_CLIENT.md` and `docs/ROADMAP.md`.

## Architecture in one picture

```
Road camera → LatestFrameQueue → RoadDetector → ObjectTracker
                                         → ImageTrajectory → PhysicalDiagnostics
                                         → RoadRiskSnapshot (diagnostic only; no alerts)
Driver frames → DriverObservationProvider → TemporalDriverStateAnalyzer
                                         → DriverState → DriverRiskSnapshot
RoadRiskSnapshot + DriverRiskSnapshot → CombinedRiskEngine → CombinedRiskSnapshot
                                         (rule matrix, freshness budgets, degraded modes)

RoadRiskSnapshot → HazardEventBridge → bounded async ZholNet HTTP client (supplementary only)
Future only: final remote advisory UI/audio/map and live STOMP subscription.
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
| `android-app/core` | Pure-Java local pipeline plus Stage 6.0 location/network contracts, hazard bridge, async client and bounded retry foundation |
| `android-app/app`  | Android Java app: CameraX pipeline, fused foreground location adapter, private random source token and post-risk ZholNet wiring |
| `zholnet-server`   | Stage 5 Spring Boot server: validation, JPA/PostGIS persistence, TTL/dedup, nearby REST, STOMP notifications, Actuator health |
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
# ZholNet server (JDK 21 + Maven, PostgreSQL/PostGIS)
cp .env.example .env                                   # choose a local demo password
docker compose up --build                              # health: /actuator/health
# or: cd zholnet-server && mvn test && mvn package     # requires an external PostGIS DB to run

# Core Java module (JDK 17+). Gradle wrapper NOT yet committed: the authoring sandbox cannot
# reach services.gradle.org and wrapper binaries are never fabricated.
cd android-app && gradle :core:test
#   or without Gradle: scripts/jvm-fallback-build.sh (see header for required env vars)

# Android app (includes Stage 6.0 supplementary ZholNet bridge; local risk remains offline).
# No model binaries are committed: place a legitimate export under models/road/<id>/ first
# (models/README.md) or the app reports MODEL NOT AVAILABLE. Local/offline inference only.
# Open android-app/ in Android Studio (SDK 34), or run Gradle `:app:assembleDebug`.
# Debug assembly is verified; no physical-device/camera/GPS run is claimed.

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
4.3 DriverGuard + road/driver risk fusion ✔ (code/JVM; device/backend pending) →
5 ZholNet server + PostGIS ✔ (code/unit tests; real PostGIS runtime pending) →
6.0 Android ZholNet client foundation ✔ (device/real server pending) → 6.1/6.2 advisory UI/map →
7 Testing, profiling, audit. See `docs/ROADMAP.md`.

## Honesty policy

Nothing in this repository simulates success: missing models fail loudly, approximate values are
labelled as estimates, and unmeasured performance is reported as **NOT MEASURED**.
