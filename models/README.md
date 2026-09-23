# models

Production inference artefacts consumed by the Java Android app through ONNX Runtime.

**No model binaries are committed** (git-ignored). Nothing in this directory is fabricated: if a
file is missing, the app reports `ModelNotAvailableException` with a clear diagnostic and does
**not** simulate detections in LIVE mode.

## Expected files

| Path                                    | Produced by                                   | Status |
|-----------------------------------------|-----------------------------------------------|--------|
| `road/zholsafe-road.onnx`               | `ai-training/export/export_onnx.py`           | MISSING (Stage 2) |
| `road/zholsafe-road-classes.txt`        | same script (from `configs/classes.yaml`)     | present (labels only) |
| `driver/zholsafe-driver.onnx`           | Stage 3 (face/eye-state model or landmarks)   | MISSING (Stage 3) |
| `driver/zholsafe-driver-classes.txt`    | Stage 3                                       | placeholder |

## Contract for `road/zholsafe-road.onnx`

- Input: `float32[1,3,640,640]`, RGB, values scaled to `[0,1]` (see `DetectorConfig`).
- Output: YOLO-family detection tensor; exact layout is validated at load time in Stage 2.
- Label file: one label per line, line index == class index, must match
  `kz.zholsafe.model.ObjectClass` labels (enforced by `ai-training/tests/test_classes.py`).

## Deployment into the app

Stage 2 will copy `models/road/*` into `android-app/app/src/main/assets/models/road/` at build
time (Gradle task) — not by hand.
