# models

Inference artefacts consumed by the Java Android app through ONNX Runtime (local, offline).

**No model binaries are committed** (`*.onnx` is git-ignored). Nothing here is fabricated: when
`model.onnx` is missing the app shows `MODEL NOT AVAILABLE`, the pipeline runs DEGRADED, and no
detections are ever simulated in LIVE or DEMO mode.

## Layout (Stage 2)

```
models/road/<model-id>/
    model.onnx        ← NOT committed; produced by ai-training/export/export_onnx.py
    model-spec.json   ← committed; complete description of the export (see below)
    labels.txt        ← committed; one model label per line, line index == model class index
```

| Directory            | Family  | Decoder                        | NMS in model | Status |
|----------------------|---------|--------------------------------|--------------|--------|
| `road/yolo11n/`      | YOLO11n | `YOLO_RAW_CXCYWH_NC` [1,84,8400] | no (app NMS) | spec **OBSERVED** from a real export (Stage 2.5, SHA-256 `c95beeaa…`, see `model-manifest.json`); model.onnx still NOT committed — re-export to reproduce |
| `road/yolo26n/`      | YOLO26n | `YOLO_END2END_XYXY_CONF_CLS` [1,300,6] | yes | spec + labels only — **model.onnx NOT PRESENT** |
| `driver/`            | —       | —                              | —            | Stage 3 |

The active model is selected by `DetectorConfig.roadModelDir` (default `models/road/yolo11n`).
Switching candidates is a configuration change; the Android pipeline does not know which family
is active. **No winner has been chosen** — MODEL SELECTION IS DEFERRED until both are benchmarked
under identical conditions (`docs/DEVELOPMENT.md` → Benchmarking).

## Producing a legitimate `model.onnx`

On a workstation with the training stack (`pip install -r ai-training/requirements-train.txt`):

```bash
cd ai-training
# YOLO11n — raw head, NMS in app
python export/export_onnx.py --weights yolo11n.pt --model-dir ../models/road/yolo11n --nms false
# YOLO26n — end2end head (NMS-free)
python export/export_onnx.py --weights yolo26n.pt --model-dir ../models/road/yolo26n
```

`export_onnx.py` writes `model.onnx`, then runs `write_model_spec.py`, which **inspects the real
graph** (input/output names and shapes, opset) and refuses to write a spec whose decoder does not
match the actual output tensor. It also fills `sha256` so the app rejects a spec/model mismatch.

Pretrained COCO weights support only `PERSON, DOG, HORSE, COW, SHEEP` of the ZholSafe classes.
**GOAT and CAMEL are not detectable with these models**; they require custom training
(`ai-training/`). Labels other than those five are ignored by `LabelMap` — they are never
re-mapped by numeric coincidence.

## Deployment into the app

`android-app/app/build.gradle` task `syncModelAssets` copies `models/road/**` into generated
assets at build time (`preBuild`). Alternatively push files to the device without rebuilding:
`adb push models/road/yolo11n /data/data/kz.zholsafe/files/models/road/` (the override location
checked first by `AssetModelFiles`).

## `model-spec.json` fields

`modelId, family, version, modelFile, labelsFile, inputName?, outputName?, inputWidth,
inputHeight, inputChannels, layout (NCHW|NHWC), inputType (FLOAT32), normalization
(SCALE_0_1|NONE), letterbox, padValue, confidenceThreshold, iouThreshold, decoder, nmsInModel,
numClasses, maxDetections, labelAliases {modelLabel: canonicalLabel}, sha256?, metadata{}`.
Parsed by `kz.zholsafe.ai.spec.ModelSpecParser`; consistency rules (e.g. end2end ⇒ nmsInModel)
are enforced in `ModelSpec`. `metadata` is informational (source, export date, dataset, opset,
precision, expected output shape) and is not read by the app.
