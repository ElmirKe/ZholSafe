# ai-training — ZholSafe AI development environment (Python)

Python is used **only** for offline AI development. It is **never** required at production
runtime (Android app, Risk Engine, ZholNet Server all run on Java). There is **no** Python
inference server in this project by design.

## Responsibilities

| Directory        | Purpose                                                                 |
|------------------|-------------------------------------------------------------------------|
| `datasets/`      | Dataset manifests and download/prepare scripts (data itself is git-ignored) |
| `annotations/`   | Annotation format converters / checkers (YOLO txt ⇄ COCO)               |
| `augmentation/`  | Augmentation recipes (night, dust, rain, motion blur — rural KZ highways) |
| `training/`      | Training entry points (Ultralytics YOLO-family by default)               |
| `validation/`    | mAP / per-class evaluation on held-out data                              |
| `benchmarks/`    | Latency benchmarks of exported ONNX models on target-like hardware       |
| `export/`        | `best.pt → zholsafe-road.onnx` export + label file generation            |
| `configs/`       | Class lists and training configs (single source of truth for labels)     |
| `zholsafe_ai/`   | Small shared Python package (class registry, ONNX metadata checks)       |
| `tests/`         | pytest tests for the utilities (not for models)                          |

## Flow

```
dataset → training/train.py → runs/.../best.pt → validation/validate.py
        → export/export_onnx.py → ../models/road/zholsafe-road.onnx (+ classes.txt)
        → Java ONNX Runtime (android-app, Stage 2)
```

## Class registry

`configs/classes.yaml` is the canonical class list. The Java enum
`kz.zholsafe.model.ObjectClass` and `models/road/zholsafe-road-classes.txt` must stay consistent
with it; `tests/test_classes.py` checks this repository-wide.

## Setup

```bash
cd ai-training
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt          # utilities only
pip install -r requirements-train.txt    # training stack (ultralytics/torch) — large, optional
pytest
```

## Status (Stage 0)

- No model has been trained. `models/` contains no `.onnx` files.
- `training/`, `validation/`, `export/` contain documented entry points that fail fast with a
  clear message if the training stack is not installed. Nothing is simulated.
- Metrics: **NOT MEASURED**.
