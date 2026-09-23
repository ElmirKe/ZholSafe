# Stage 2.5 — Real YOLO11n ONNX end-to-end smoke test

**Purpose.** Prove that the Stage 2 inference architecture (unchanged) works with a REAL YOLO11n
ONNX graph, REAL ONNX Runtime and REAL road/animal photographs — not only synthetic tensors.
It is a **smoke test**: 31 images say nothing about accuracy (no mAP, no validation set).

**Baseline:** `8ade6c96569ec0846ec7d3e30340ed00f87d7cb7` (Stage 2.1). Executed 2026-09-23.

Everything below is a DESKTOP/JVM result. Android build and device runs were **NOT EXECUTED**.

---

## 1. Pipeline exercised

```
JPEG/PNG ─ ImageIO ─► ImageFrames.nv21()  RGB→NV21 (BT.601 full range, V,U interleaved)
                        + optional synthetic sensor rotation (90/180/270)
        ─► Frame(NV21, rotationDegrees)                         ← same type CameraX delivers
        ─► OnnxRoadDetector.detect()                            ← PRODUCTION core, unmodified
              Nv21Preprocessor   (rotation → letterbox 640×640 pad 114 → /255 → NCHW float32)
              OrtTensorSession   (PRODUCTION adapter, now in :ort-adapter)
                 ai.onnxruntime.OrtSession.run(...)             ← REAL ONNX Runtime 1.19.2, CPU
              YoloRawDecoder     ([1,84,8400] → conf filter ≥ 0.35)
              Nms.classAware     (IoU 0.45, max 50)
              LabelMap           (COCO label string → ObjectClass; 75 labels dropped)
              LetterboxTransform.toSource → Detection[] (upright source pixels)
        ─► results.json / summary.csv / annotated/*.jpg
```

No `FakeRoadDetector`, no scripted `TensorSession`, no mock tensor is on this path. The harness
only decorates the real session (`RecordingSessionFactory`) to count `run` calls and copy the
raw output for diagnostics; the detector consumes the runtime result directly.

Harness: `android-app/smoke-test/` (`kz.zholsafe.smoke.SmokeTestRunner`). Runner script without
Gradle: `scripts/run-smoke-test.sh`.

### Structural change (minimal, reported)

`OrtTensorSession` and `OrtSessionFactory` were **moved unchanged** from `:app` into a new
pure-JVM module `android-app/ort-adapter` (same package `kz.zholsafe.ai`). Reason: prompt §13 —
one ORT adapter, no diverging desktop copy. `:app` depends on `:ort-adapter` + the Android AAR;
`:smoke-test` depends on `:ort-adapter` + the desktop jar. The ORT version is now declared once in
`ort-adapter/build.gradle`. `OrtSessionFactory` still references `NNAPIFlags`, which exists in the
desktop Java API too (it was compiled against the real v1.19.2 sources and ran on the JVM).

---

## 2. Model acquisition & provenance (`models/road/yolo11n/model-manifest.json`)

| Item | Value |
|---|---|
| Family / name | YOLO11 / yolo11n (detect, COCO-80 pretrained) |
| Official source | `github.com/ultralytics/assets/releases/download/v8.3.0/yolo11n.pt` — GitHub API confirms the asset (id 196201276, **5 613 764 bytes**) but `release-assets.githubusercontent.com` is unreachable from this sandbox |
| Actual source | GitHub blob `rahulkumarreddy567/Vision_QA_Pipeline:yolo11n.pt` (git blob `45b273b4…`, 5 613 764 bytes) fetched via the GitHub API. **Provenance UNCONFIRMED vs upstream** (identical size; checkpoint self-describes as ultralytics 8.2.100 / yolo11n.yaml / coco.yaml / 600 epochs / AGPL-3.0). The user explicitly approved this with the flag. |
| Weights SHA-256 | `0ebbc80d4a7680d14987a577cd21342b65ecfd94632bd9a8da63ae6417644ee1` |
| Export | official Ultralytics export via the existing `ai-training/export/export_onnx.py --nms false` (ultralytics 8.4.160, torch 2.14.0+cpu, onnx 1.23.0, onnxslim 0.1.96) |
| ONNX | `model.onnx`, 10 741 397 bytes, **SHA-256 `c95beeaa60349d3558a4ea775da2cb297a94a14d550b17cc8d57fa4b998fbed5`**, IR 8, opset 17, FP32, git-ignored (repo policy) |
| Graph input | `images` float32 `[1,3,640,640]` (static) |
| Graph output | `output0` float32 `[1,84,8400]` (static); 0 `NonMaxSuppression` nodes; metadata `end2end=False`, `nms=False` |
| Classes | 80 (graph metadata `names`): person=0, dog=16, horse=17, **sheep=18, cow=19** |

Inspection was done with `onnx` (checker OK) and independently confirmed at runtime by the Java
session (`runtime_inputs/outputs` in results.json).

### ModelSpec changes (observed → spec)

`write_model_spec.py` regenerated `model-spec.json` from the real graph. Diff vs baseline:
`version` → `ultralytics-8.2.100-coco-pretrained`; `sha256` null → `c95beeaa…`;
`metadata.exportDate` → 2026-09-23; `metadata.status` PROVISIONAL → **OBSERVED**;
`metadata.source` corrected; new `metadata.fieldProvenance` block classifying each field as
OBSERVED_FROM_GRAPH / OBSERVED_AT_RUNTIME / CONFIGURED / ASSUMED. **No structural field changed**
(input/output names, 640×640, NCHW, decoder `YOLO_RAW_CXCYWH_NC`, `nmsInModel=false`, 80 classes,
`[1,84,8400]` were all already correct). `normalization=SCALE_0_1` and `letterbox=true` remain
ASSUMED (Ultralytics convention; consistent with the reference comparison, not readable from the
graph). YOLO26n spec remains PROVISIONAL (not tested).

### Decoder contract

EXPECTED `[1, 4+nc, N]` = `[1,84,8400]` · ACTUAL (graph + runtime) `[1,84,8400]` ·
DECODER USED `YoloRawDecoder` **unchanged**. Layout confirmed semantically by the reference
comparison (boxes match Ultralytics with mean IoU 0.959).

### Labels

`labels.txt` is written from the checkpoint's own `model.names` (80 lines). Verified against the
graph metadata: person 0, dog 16, horse 17, sheep 18, cow 19. `LabelMap` maps exactly these five;
GOAT and CAMEL are **not present** and remain unsupported (nothing is aliased to them).

---

## 3. Test images (`demo/stage2_5/test-manifest.json`)

31 real photographs obtained by web image search from the sandbox (served at preview
resolution, 500–2464 px). Categories actually represented: ROAD_EMPTY 6 (incl. one
horse-crossing *sign* as a false-positive probe), PERSON 7, DOG 3, HORSE 5, COW 3, SHEEP 2,
MIXED 5. Lighting: DAY (most), DUSK 5, NIGHT 2. Orientation: 22 landscape, 9 portrait; unusual
aspect ratios 500×889, 500×381, 1195×1412, 2464×1632.

Git policy: **13 Pexels-licensed images are committed** (`demo/stage2_5/images/`, 512 KB) with
their annotated outputs. The other 18 come from stock-photo previews / unverified licences: used at
test time only, **not committed**; source URL, SHA-256, category and expectation are in the manifest
and their numerical results are in `results.json`. Expectations are "visible target classes" set
by eye — smoke expectations, not ground truth.

---

## 4. Results (`demo/stage2_5/results/`)

* `results.json` — full machine-readable report (environment, model, per-image timings, candidate
  counts raw→conf→NMS→label-map, detections in upright source pixels, rotation tests, colour
  sanity, benchmark, memory).
* `summary.csv` — one line per image.
* `reference_comparison.json` — Ultralytics reference (§6).
* `annotated/` — 13 diagnostic images (committed subset only).

**Execution:** 31/31 images `DETECTOR_OK`; 265 real `OrtSession.run` calls; 0 exceptions.
Empty roads: 5/5 returned `DETECTOR_EXECUTED_SUCCESSFULLY_ZERO_TARGET_DETECTIONS` (raw 8400 →
0 after confidence filter) — distinct from `DETECTION_UNAVAILABLE`, which never occurred.

**Expected-target check:** YES 23, PARTIAL 1, NO 1, N/A(none expected) 6.
Classes demonstrated through the canonical mapping: **PERSON, DOG, HORSE, COW, SHEEP** — all five.

**False positives / misses observed (manual review, not hidden):**

| Image | Observation |
|---|---|
| `horse_01_sign_only` | horse **pictogram on a warning sign** → HORSE 0.85 (Ultralytics reference gives the same box at 0.67). Genuine model FP; relevant for Kazakh roads with livestock-crossing signs. |
| `dog_04_black_dog_person_bg` | near black dog → **COW 0.85** (class confusion; reference identical). Person found. |
| `cow_04_cow_head` | extreme close-up of a decorated cow head indoors → PERSON ×2 (people are actually present) + DOG 0.37; no COW. Hard/atypical image; expected target NOT found. |
| `cow_01_herdsman` | cow found only at 0.35 (threshold edge), person 0.88. |
| `person_03_group_sidewalk` | Java 11 persons vs reference 10: one extra PERSON at 0.43 in Java (a tightly packed crowd; see §6). |
| `sheep_03_roadside`, `night_03`, `person_04` | non-target labels (`truck`, `car`, `handbag`) survived NMS and were dropped by LabelMap as designed. |

**Geometry (visual):** boxes are correctly placed on landscape (e.g. `sheep_02`, `dog_03`),
portrait (`person_02_street_portrait_tall` 500×889, `road_empty_01`) and unusual aspect
(`road_empty_03_forest` 500×381, `person_02`) images; no offset/scale artefact from letterbox
inversion was seen in any of the 31 annotated outputs (the 18 uncommitted ones were reviewed
during the run and then deleted).

**Rotation (coordinate logic only, synthetic metadata):** for 8 images × {90,180,270} the
detections match the rotation-0 result with the same class count and IoU ≥ 0.9858 (mostly 1.0;
the tiny deviations come from nearest-neighbour sampling of the transposed buffer). This does
**not** validate camera orientation on a device.

**Colour sanity:** pure red/green/blue synthetic frames through the production preprocessor land in
channels 0/1/2 respectively for both RGB_888 and NV21 paths (NV21 red = [0.996, 0.004, 0.0]).
Independently, the Java input tensor for `dog_03` vs Ultralytics' own preprocessing: mean |Δ| 0.008
(p99 0.059) versus 0.115 if the channels were swapped → **no RGB/BGR swap**.

---

## 5. DESKTOP/JVM benchmark — **not Android performance**

Environment: Linux 6.1 x86-64, Intel Xeon @ 2.60 GHz, **2 vCPUs**, OpenJDK 21.0.8, ONNX Runtime
1.19.2 CPU EP, intra-op threads 2, FP32 640×640. 10 warm-up + 30 measured runs on each of 3
images (pooled 90 runs):

| metric | ms |
|---|---|
| preprocess mean (NV21→letterbox→tensor, Java) | 4.37 |
| inference mean (`OrtSession.run` incl. tensor create/copy) | 57.55 |
| postprocess mean (decode + NMS + label map) | 1.37 |
| total mean / median / P95 | 63.30 / 62.80 / 80.39 |

Memory (approximate, `/proc/self/status` RSS): 49.8 MB before model load → 114.7 MB after load →
421.7 MB after all inference runs (JVM heap 58.8 MB; the rest is ORT native arenas + heap copies
from `getFloatBuffer`). Android RAM: **NOT MEASURED**.

---

## 6. Reference sanity check (Ultralytics, `ai-training/validation/reference_compare.py`)

Same `model.onnx`, same images, official `YOLO.predict` (onnxruntime-python 1.30.0, conf 0.35,
iou 0.45, imgsz 640). Canonical-class detections only, matched by class + best IoU ≥ 0.5:

* Java 80 detections, reference 79, **matched 79**; mean IoU **0.959**, min IoU 0.78;
  mean |Δconf| 0.029, max |Δconf| 0.208.
* The single unmatched Java detection: `person_03` PERSON 0.43 (a crowd); the largest confidence
  deltas (−0.21 on that image, +0.19 on the sign) are consistent with the known preprocessing
  differences: the Java path samples NV21 (4:2:0 chroma) with nearest-neighbour resize, Ultralytics
  uses bilinear RGB. No systematic offset, scale or class-index error was found, so no tuning was
  done.

---

## 7. Failure diagnostics

None occurred. The harness would report `failed_stage` ∈ {MODEL_LOAD, IMAGE_DECODE, PREPROCESS,
ORT_RUN, OUTPUT_SHAPE, DECODE, LABEL_MAP, VISUALIZATION} with exception, stack trace, detector state,
runtime I/O metadata and spec in `results.json` (exit code 2 for load failure, 1 for any image failure).

---

## 8. How to reproduce

```bash
# 1. weights → ONNX (needs ai-training/requirements-train.txt)
cd ai-training && python export/export_onnx.py --weights yolo11n.pt --model-dir ../models/road/yolo11n \
    --nms false --model-id zholsafe-yolo11n-coco --family yolo11 --version ultralytics-8.2.100-coco-pretrained
sha256sum ../models/road/yolo11n/model.onnx      # expect c95beeaa…

# 2. smoke test (Gradle)
cd android-app && ./gradlew :smoke-test:run --args="--model-dir ../models/road/yolo11n \
    --input ../demo/stage2_5/images --manifest ../demo/stage2_5/test-manifest.json --output ../demo/stage2_5/results"
#    or without Gradle: ZS_JAVA=… ZS_ECJ=… ZS_ORT_JAR=… ZS_ORT_NATIVE=… scripts/run-smoke-test.sh <same args>

# 3. reference
cd ai-training && python validation/reference_compare.py --model ../models/road/yolo11n/model.onnx \
    --spec ../models/road/yolo11n/model-spec.json --images ../demo/stage2_5/images \
    --java-results ../demo/stage2_5/results/results.json --out ../demo/stage2_5/results/reference_comparison.json
```

Sandbox note: Maven Central was unreachable, so the desktop ORT Java runtime used here was the
official v1.19.2 Java sources compiled with ECJ + the official `libonnxruntime.so` 1.19.2 taken from
the PyPI wheel + the official JNI C sources compiled with gcc. This is the same Java API/ABI as the
Maven artifact `com.microsoft.onnxruntime:onnxruntime:1.19.2` but it is not that binary.

---

## 9. Gates

| Gate | Result | Evidence |
|---|---|---|
| A MODEL | **PASS** (weights provenance flagged UNCONFIRMED vs upstream) | real ONNX present, SHA-256 recorded, graph inspected, spec matches |
| B RUNTIME | **PASS** | real `OrtSession` loaded the model, 265 real runs, real `[1,84,8400]` outputs |
| C ZHOLSAFE PIPELINE | **PASS** | preprocess → ORT → decoder → NMS → LabelMap → `Detection[]` on 31/31 images, no mocks |
| D GEOMETRY | **PASS** | visual inspection of landscape, portrait, unusual-aspect and target images; rotation IoU ≥ 0.986 |
| E SEMANTICS | **PASS** | PERSON, DOG, HORSE, COW, SHEEP all produced through the canonical mapping |

Known limitations: weights hash not upstream-confirmed; 31 images at preview resolution; no
accuracy claim; desktop numbers only; Android build/device NOT EXECUTED; ORT desktop runtime was
locally assembled, not the Maven binary; nearest-neighbour resize and NV21 chroma loss remain the
production behaviour (accepted for now, measured here: mean tensor |Δ| 0.008 vs Ultralytics).
