# Stage 4.4 — Android driver app: DriverGuard backend, alerts, driver screen

Stage 4.3 delivered the DriverGuard core (temporal analysis, driver risk, road/driver fusion) with
a synthetic provider only, no alerts and a build that had never run. Stage 4.4 closes the loop on
Android: **front camera → Face Landmarker → DriverGuard → combined risk → alert**.

## Status

| Item | Status |
|------|--------|
| Gradle build (`./gradlew`) | **EXECUTED** — wrapper 8.7 committed, `:app:assembleDebug` succeeds |
| Core JVM tests | **EXECUTED** — 284/284 pass (after the two fixes below) |
| App JVM tests (`DriveStatusTest`) | **EXECUTED** — 14/14 pass |
| MediaPipe head-pose sign | **CHECKED** on real photos (see below) |
| Run on a physical phone | **NOT VERIFIED** yet |
| Road model export | **REPRODUCED** — see below; on-device run NOT VERIFIED |
| Thresholds | EXPERIMENTAL core defaults, unchanged; no field calibration |

## Build fixes found by the first real build

- `build.gradle`: `id 'java-library' apply false` is rejected by Gradle (core plugin).
- `core/build.gradle`: project-level `repositories` conflict with `FAIL_ON_PROJECT_REPOS`.
- `app/build.gradle`: reads `:ort-adapter`'s `ext.onnxRuntimeVersion` before that project is
  configured → `evaluationDependsOn(':ort-adapter')`.
- `CombinedRiskSnapshot`: `return` inside a record compact constructor does not compile; the fused
  checks moved to a helper with unchanged logic.
- `CombinedRiskProcessor`: constructor called `evaluate()` before `latestCombined` existed →
  NullPointerException on creation (caught by two existing tests).
- `RoadCamera.bind`: `hasCamera` throws the checked `CameraInfoUnavailableException`.

## What was added

| Piece | Where | Notes |
|-------|-------|-------|
| `LiveCamera` (was `RoadCamera`) | `app/camera` | `Lens.ROAD` (rear, 1280×720) or `Lens.DRIVER` (front, 640×480); unbinds only its own use cases so two sources can coexist |
| `MediaPipeDriverObservationProvider` | `app/ai` | Implements the Stage 4.3 port. Eye openness = 1 − `eyeBlink*`, mouth = `jawOpen`, head pose from the facial transformation matrix |
| Neutral head pose | same | The matrix is camera-relative: a phone mounted above the eyes reads a driver looking ahead as ~+30–40° pitch ("down"). The first 30 face frames (~2 s) set the driver's neutral pose; afterwards the deviation is reported. Until then head pose is UNAVAILABLE |
| `PipelineController` | `app/ui` | Independent road and driver `FramePipeline`s + `CombinedRiskProcessor` |
| `DriveStatus` | `app/ui` | Pure Java: fused snapshot → tone, alert kind, message keys |
| `AlertController` | `app/alert` | Siren on the ALARM stream, vibration, text-to-speech |
| Driver screen | `res/layout/activity_main.xml` | Status card, coloured frame, full-screen alarm, camera choice, mute, language, engineering view |
| Languages | `values`, `values-kk`, `values-en` | Russian (default), Kazakh, English; in-app button (AppCompat per-app locales) |

### Head-pose sign check

Same Face Landmarker model and matrix layout (column-major), formula
`pitch = atan2(m[6], m[10])`: frontal portrait **+10°**, person looking down **+22°**, person
looking up **−13°**. Positive pitch = head down, as `TemporalDriverStateAnalyzer` expects.

### Fail-safe display rules (`DriveStatus`)

- Alarms first: prolonged eye closure / high PERCLOS ≥ WARNING → **sleep alarm**; road ≥ WARNING →
  **road alarm**; looking down ≥ WARNING → **eyes-on-road alarm**.
- A camera that should run but is UNAVAILABLE, a lost face or hidden eyes → **grey**, with the
  reason. Never green.
- Green says "no danger detected" and names what is actually watched — never "safe".

### Cameras

Many phones cannot stream the front and rear cameras at once. The screen offers
**driver / road / both**; with *both* on such a phone the failing side reports UNAVAILABLE (grey)
instead of pretending. Default: **driver**, the side that works on every phone.

### Spoken alerts

In the app language. Kazakh text-to-speech voices are often not installed; then the Russian phrase
is spoken instead of staying silent. Sound can be muted; vibration and the red screen stay.

### Face model

`models/driver/face_landmarker.task` (Google AI Edge, Apache-2.0) is not committed, like every
model binary. `app:fetchFaceModel` downloads it on the first build and verifies SHA-256
`64184e22…0bc9ff`.

## Road model export (reproduced)

- Official weights `yolo11n.pt` downloaded from the Ultralytics GitHub release v8.3.0: SHA-256
  `0ebbc80d…644ee1` — **identical** to `model-manifest.json`, so the Stage 2.5 provenance flag
  "UNCONFIRMED vs upstream" can be resolved.
- Re-exported with the unchanged team command (ultralytics 8.4.160, torch 2.2.2 — the newest
  torch for Intel Macs). The graph matches `model-spec.json` field by field (input/output, decoder,
  classes, opset); only the file bytes differ: SHA-256 `67e1e5bc…96314b` instead of
  `c95beeaa…fbed5` (the manifest's export used torch 2.14). The committed spec was **not** changed.
- `:smoke-test` (the production Java chain with real ONNX Runtime) on the 13 committed images gives
  results **identical** to `demo/stage2_5/results` on 13/13 images, including the known
  dog→COW confusion.
- For a device test such an export is pushed into the app's override folder with
  `scripts/install-android.sh <export-dir>`; the APK assets stay untouched.

## Build and install

```bash
# JDK 17 + Android SDK 34 (platform, build-tools 34.0.0, platform-tools)
cd android-app
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # path to your SDK
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Not done / known limits

- No foreground service: monitoring stops when the screen is off or the app is in the background.
- Road alarms depend on the ONNX model being exported and on Stage 4.2's uncalibrated diagnostics.
- No field validation of any threshold, latency, battery or thermal behaviour (**NOT MEASURED**).
- Kazakh strings need a native-speaker review.
