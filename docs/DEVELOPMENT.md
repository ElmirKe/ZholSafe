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

# Android app (requires Android SDK 34 + network access to Google Maven for CameraX)
cd android-app && ./gradlew :app:assembleDebug
#   NOTE: no Gradle wrapper binary is committed yet (the build sandbox has no access to
#   services.gradle.org, and wrapper jars are never fabricated). Use Android Studio or a local
#   `gradle wrapper --gradle-version 8.7` once, then commit gradle/wrapper/*.

# ZholNet server
cd zholnet-server && mvn test          # or: mvn package && java -jar target/zholnet-server-*.jar
curl http://localhost:8080/api/v1/health

# AI utilities
cd ai-training && pytest

# Cross-module contract fixture check
python3 scripts/check_contracts.py
```

## Running the camera pipeline on a device (Stage 1)

1. Open `android-app/` in Android Studio (SDK 34, JDK 17), let it sync CameraX 1.3.4.
2. Run `app` on a physical device (the emulator's virtual rear camera works too but FPS numbers
   are meaningless there). The Activity is landscape-locked.
3. The app starts in **DEMO** (synthetic frames, no hardware). Tap **Switch to LIVE**; grant the
   CAMERA permission once. Denying it shows `PIPELINE: UNAVAILABLE — CAMERA permission denied`
   and the app keeps running; use **Retry camera** to re-request.
4. The overlay shows MODE / CAMERA / PIPELINE, RESOLUTION + ROTATION, CAMERA FPS / PROCESSED FPS
   (EMA estimates), RECEIVED / PROCESSED / REPLACED / ERRORS, and
   `AI detector: NOT LOADED — STAGE 2`. Logcat tag `Pipeline` prints a summary on stop.
5. Sanity checks: REPLACED grows only when the processor is slower than the camera; ERRORS stays
   0; after backgrounding and returning, counters continue and no `zs-*` thread leaks.

Stage 1 was **not** verified on hardware in the authoring environment (no SDK, no device);
treat the steps above as the manual test plan.

## Road detector (Stage 2) on a device

1. Produce a legitimate `model.onnx` for at least one candidate (see `models/README.md`); commit
   nothing binary. `syncModelAssets` (runs before `preBuild`) copies `models/road/**` into assets.
   Without a model the overlay shows `STATUS: MODEL NOT AVAILABLE` and `DETECTION UNAVAILABLE`.
2. Select the model with `DetectorConfig.roadModelDir` (`models/road/yolo11n` or `…/yolo26n`);
   provider with `DetectorConfig.executionProvider` (`CPU` default; `NNAPI` falls back to CPU).
3. Run the app: the overlay shows MODEL / STATUS, INFER FPS, detector ms split
   (pre/inf/post), per-class counts (PERSON…CAMEL) and up to three `X DETECTED conf [box]` lines.
   Boxes are drawn by `DetectionOverlayView` assuming `PreviewView` `fitCenter`; if the scale type
   is changed the overlay disables itself rather than draw misleading boxes.
4. Logcat tags: `OnnxDetector` (load diagnostics, provider, supported classes), `RoadDetection`,
   `Pipeline`.

### ONNX Runtime adapter API check

`scripts/check-ort-adapter.sh` compiles `kz.zholsafe.ai.Ort*` against the real ONNX Runtime Java
sources for the version pinned in `app/build.gradle` (`git clone --branch v<ver> --sparse
microsoft/onnxruntime`, `java/src/main/{java,jvm}`). It proves signature compatibility only; it
does not load the native library or run a model.

### Benchmarking candidates

`kz.zholsafe.benchmark.DetectorBenchmark` runs any `RoadDetector` over a frame list with warm-up
and produces mean/median/P95 for total/pre/inference/post plus FPS. Compare YOLO26n vs YOLO11n
only under identical conditions (device, ORT version, provider, frames, warm-up, thresholds) and
record `BenchmarkResult.toReportLine()` output under `ai-training/benchmarks/results/`. Accuracy
(`AccuracyResult`) requires labelled ground truth. No numbers exist yet.

## Stage 3 tracking verification

See `docs/STAGE3_TRACKING.md` for the algorithm, integration, limitations and reproducible JVM
commands. Stage 3 is RoadGuard tracking; DriverGuard is deferred to Stage 4.3. The Stage 2.5 real
model desktop report is unchanged; Android build/device tracking remain NOT VERIFIED unless run on
a configured Android SDK/device. For the overlay, an observed track shows `HORSE #17 0.82` (or
`(tentative)`); a LOST box is not drawn, and no metres/TTC/risk is shown.

## Stage 4.0 image-space trajectory verification

`docs/STAGE4_0_TRAJECTORY.md` describes normalized units, regression, fit quality and explicit
unavailability. `cd android-app && gradle :core:test` is the official JVM test command when
Gradle/JUnit dependencies are available. The engineering text can show image-only motion and
qualitative bbox-growth labels; the Stage 3 track-ID overlay is unchanged. No metre, m/s, TTC or
risk warning is derived from this stage. Android SDK build/device run and official Gradle/JUnit
must be reported separately as NOT EXECUTED when not run.

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

## Real-model smoke test (Stage 2.5)

```bash
cd android-app && ./gradlew :smoke-test:run --args="--model-dir ../models/road/yolo11n \
    --input ../demo/stage2_5/images --manifest ../demo/stage2_5/test-manifest.json --output ../demo/stage2_5/results"
#   fallback without Gradle: scripts/run-smoke-test.sh (env: ZS_JAVA, ZS_ECJ, ZS_ORT_JAR, ZS_ORT_NATIVE)
#   reference comparison:    ai-training/validation/reference_compare.py (needs ultralytics)
```

Requires a real `models/road/yolo11n/model.onnx` (git-ignored; produce it with
`ai-training/export/export_onnx.py`, expected SHA-256 in `model-manifest.json`). Results are a
DESKTOP/JVM smoke test — never quote them as Android performance or as accuracy.
