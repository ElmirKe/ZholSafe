# ZholSafe — Development Guide

## Prerequisites

| Module          | Tooling                                                                    |
|-----------------|----------------------------------------------------------------------------|
| android-app     | JDK 17+, Android Studio / Android SDK (compileSdk 34), Gradle 8.7+ (wrapper to be added in Stage 1 — see note) |
| zholnet-server  | JDK 21, Maven 3.9+ (`mvnw` wrapper to be added when network access to Maven Central is available) |
| ai-training     | Python 3.10+, `pip install -r requirements.txt` (utilities) / `requirements-train.txt` (training) |
| database        | PostgreSQL 14+ with PostGIS 3+ (Stage 5)                                    |

## Build & test

```bash
# Core (pure Java) — contracts, config, Risk Engine
cd android-app && ./gradlew :core:test            # once the Gradle wrapper is committed
#   fallback without Gradle (see scripts/jvm-fallback-build.sh header for env vars)
ZS_JAVA=... ZS_ECJ=... ZS_JUNIT=... scripts/jvm-fallback-build.sh

# Android app (requires Android SDK)
cd android-app && ./gradlew :app:assembleDebug

# ZholNet server
cd zholnet-server && mvn test          # or: mvn package && java -jar target/zholnet-server-*.jar
curl http://localhost:8080/api/v1/health

# AI utilities
cd ai-training && pytest

# Cross-module contract fixture check
python3 scripts/check_contracts.py
```

## Rules for every stage (binding)

1. Inspect the existing repository first; preserve working functionality; never create a
   replacement project.
2. Implement only the requested stage (`docs/ROADMAP.md`). Do not pull later-stage work forward.
3. Build the affected modules and run their tests. Fix compilation and integration errors you
   introduced.
4. Never claim success without verification. Report **NOT MEASURED** / **NOT EXECUTED** where
   applicable. Never fabricate FPS, latency, precision, recall, accuracy, distance, TTC, test or
   build results, or benchmark numbers.
5. Architecture changes: do not make them silently. Explain the problem, propose the smallest
   change, and wait for approval if it is substantial.

## Coding conventions

- Java: records for value types, interfaces for ports, `final` classes for implementations;
  no business logic in Activities/Fragments; no `android.*` imports in `android-app/core`.
- Enums for classes/statuses/levels/reasons — no scattered string constants.
- No magic numbers: every threshold/weight lives in `kz.zholsafe.config.*` or
  `application.yml`.
- Anything approximate → `Estimate`. Anything unknown → explicit unavailable representation.
- Python is for `ai-training/` only; it must never be needed to run the app or server.
- No C++/JNI, no Kotlin, no frontend framework, no microservices, without an approved proposal.

## Threading & performance rules

- Never run inference or heavy processing on the Android UI thread.
- Camera → inference hand-off goes through `LatestFrameQueue` (single-slot, drop-oldest). No
  unbounded frame buffers; prefer dropping stale frames.
- Avoid image copies where the platform allows direct buffer access.
- No raw frames across processes; no continuous raw video upload.
- Optimise only after profiling (Stage 7). C++ only if profiling proves Java/ONNX Runtime
  cannot meet requirements — and then only for the measured bottleneck.

## Logging

Core uses `kz.zholsafe.logging.ZLog` (Android installs a Logcat sink; server uses SLF4J).
Standard events that MUST be emitted where applicable:

`MODEL_LOADED, MODEL_LOAD_FAILED, CAMERA_STARTED, CAMERA_STOPPED, CAMERA_ERROR,
DETECTION_GENERATED (debug-rate-limited), RISK_LEVEL_CHANGED, LOCAL_ALERT_GENERATED,
HAZARD_EVENT_SUBMITTED, HAZARD_EVENT_QUEUED, HAZARD_EVENT_DROPPED, SERVER_CONNECTION_LOST,
SERVER_CONNECTION_RESTORED, GPS_UNAVAILABLE, FRAME_DROPPED`.

Never log pixel data, frame dumps or anything identifying a person's face.

## Error handling

Fail safe and transparent: missing model → clear diagnostic and `UNAVAILABLE` state; camera or
GPS or network or server unavailable → local detection/warning continues where possible; nothing
is silently simulated in LIVE mode. Mock/fake detectors are allowed only in tests and in clearly
labelled DEMO components.

## Security / privacy baseline

- `vehicleId` is not trusted; inbound server events are not trusted.
- Extension points documented in `zholnet-server/.../config/SecurityExtensionPoints.java`.
- No secrets in the repository; server config reads from environment variables.
- Server receives compact events only; evidence snapshots are a reserved, optional future field.

## Configuration

Vehicle: `kz.zholsafe.config.ZholSafeConfig` (defaults in code; overrides mechanism arrives with
the UI in Stage 1). Server: `src/main/resources/application.yml` (all values env-overridable).
DriverGuard thresholds are experimental demo values — say so in any UI that exposes them.

## Model files

See `models/README.md`. Binaries are git-ignored; nothing fake is committed. The app must fail
clearly if a model is missing.

## Git

- Work on the assigned branch; commit small, descriptive changes.
- Do not commit datasets, model binaries, build outputs or credentials (see `.gitignore`).
