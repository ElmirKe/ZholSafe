# benchmarks

Model comparison methodology for the RoadGuard detector (Stage 2 prepared the structure; **no
results exist yet — NOT MEASURED**).

## Candidates

`zholsafe-yolo26n-coco` vs `zholsafe-yolo11n-coco` (later: custom ZholSafe-trained variants).
No primary model is chosen until both are measured under identical conditions.

## Identical-conditions rule

Same device · same ONNX Runtime version and execution provider · same input resolution policy
(per spec; report it) · same frame set · same warm-up count · same clock · same confidence/IoU
policy where comparable (end2end models have in-graph top-k; report thresholds explicitly).

## What is collected (`kz.zholsafe.benchmark.DetectorBenchmark`)

model id, provider, device info, input size, frames, warm-up, measured, failures, detections,
total / preprocess / inference / postprocess latency (mean, median, P95, min, max), FPS,
approximate used heap. Output: `BenchmarkResult.toReportLine()`.

For labelled clips/images: TP / FP / FN / precision / recall via `AccuracyResult` (class-aware
greedy IoU matching). **Requires real ground truth** — none exists in the repository.

## Evaluation categories

`DAY, DUSK, NIGHT, DISTANT_OBJECT, SMALL_ANIMAL, LARGE_LIVESTOCK` (`EvaluationCategory`), plus
`LATENCY_ONLY` for synthetic-frame latency runs. No accuracy may be claimed for a category
without labelled data in that category. Record results as:

```
results/<date>-<device>/<model-id>/<category>.txt   ← one BenchmarkResult report line per run
```

## Running (once models exist)

- Android: an engineering entry point calling `DetectorBenchmark.run(detector, frames, warmUp, Build.MODEL, category)` — not yet wired into the UI (Stage 7 / on request).
- JVM: `DetectorBenchmark` with a JVM ONNX Runtime `TensorSession` implementation (not shipped; the app implementation is Android-only). Note JVM x86 numbers are NOT device numbers.
