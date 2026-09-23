# demo — DEMO MODE inputs

DEMO MODE feeds pre-recorded video or deterministic scenario files through the **same**
pipeline and the **same** Risk Engine as LIVE MODE. There is no second implementation.

```
LIVE: CameraX ──┐
                ├─→ LatestFrameQueue → RoadDetector → ObjectTracker → RiskEngine → AlertSink
DEMO: file/scenario ┘
```

## Contents

- `scenarios/` — deterministic JSON scenarios (sequences of detections / driver observations)
  that Stage 4 will replay into the tracker + Risk Engine without any neural network. Useful for
  reproducible Risk Engine demos and regression tests.
- Video clips are **not** committed (size/licensing). Place them under `demo/video/` (git-ignored)
  and reference them from a scenario.

## Honesty rules

- Demo output must be clearly labelled "DEMO" in the UI.
- A scenario replay is not a measurement: never quote FPS/latency from it.
