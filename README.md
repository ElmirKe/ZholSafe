# ZholSafe

Intelligent road-safety system for intercity and rural highways in Kazakhstan.

ZholSafe combines on-device driver monitoring (fatigue / microsleep), road-scene computer vision
(animals, pedestrians and other hazards), tracking and trajectory analysis, a local explainable
Risk Engine and immediate driver alerts — all working **offline** — with **ZholNet**, a
supplementary network that shares GPS-tagged hazard events between nearby vehicles and builds a
dynamic geospatial risk map.

Target hazards: **horse, cow, sheep, goat, camel, dog, person** (extensible).

> **Status: Stage 0 — architecture and project foundation.** No neural network has been trained,
> no model binary is included, and no performance figures exist yet (NOT MEASURED). See
> `docs/ROADMAP.md`.

## Architecture in one picture

```
 Road camera ─┐                                                     ┌──────────────────────┐
              ├─► LatestFrameQueue ─► RoadDetector (Java + ONNX RT)  │  ZholNet Server      │
 Driver cam ──┘        │                  │                          │  Java 21 Spring Boot │
                       ▼                  ▼                          │  PostgreSQL + PostGIS│
              DriverDetector        ObjectTracker → Trajectory/TTC   └──────────▲───────────┘
                       │                  │                                     │ HTTPS REST (compact events)
                       └──────► RiskEngine (Java, explainable) ◄── VehicleContext│ WebSocket (notifications)
                                   │                   │                        │
                             AlertSink (local)   HazardEventPublisher ──────────┘
                             works fully offline      (best effort)
```

The local warning path never depends on internet, server, database, cloud or other vehicles.
Details: `docs/ARCHITECTURE.md`.

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
| `android-app/core` | Pure-Java contracts (`Detection`, `TrackedObject`, `DriverState`, `RiskAssessment`, `HazardEvent`…), configuration, pipeline ports, `RiskEngine` + Stage 0 baseline, unit tests |
| `android-app/app`  | Android Java app: CameraX, alerts, location, ZholNet client, UI (skeleton in Stage 0) |
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

# Android app (Stage 2: CameraX → ONNX Runtime road detector → telemetry + box overlay).
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

0 Foundation ✔ → 0.1 Contract hardening ✔ → 1/1.1 Android + CameraX frame pipeline ✔ (code; device run pending) → 2 RoadGuard ONNX detection architecture ✔ (code + JVM tests; real model & device run pending) → 2 RoadGuard (ONNX) → 3 DriverGuard →
4 Tracking/trajectory/Risk Engine → 5 ZholNet server + PostGIS → 6 Integration + WebSocket +
map → 7 Testing, profiling, optimisation, audit. See `docs/ROADMAP.md`.

## Honesty policy

Nothing in this repository simulates success: missing models fail loudly, approximate values are
labelled as estimates, and unmeasured performance is reported as **NOT MEASURED**.
