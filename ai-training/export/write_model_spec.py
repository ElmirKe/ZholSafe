#!/usr/bin/env python3
"""Generate/verify model-spec.json from a REAL exported ONNX file (Stage 2).

The spec is derived from the graph, not guessed from the file name:

  * input: single float32 tensor [1,3,H,W] (NCHW) → inputWidth/inputHeight/inputName
  * output [1, 4+nc, N]  → decoder YOLO_RAW_CXCYWH_NC, nmsInModel=false
  * output [1, K, 6]     → decoder YOLO_END2END_XYXY_CONF_CLS, nmsInModel=true
  * anything else        → refuse (exit 3) — do not ship a spec the Java decoder cannot validate

Usage:
    python export/write_model_spec.py --model-dir ../models/road/yolo11n \
        --model-id zholsafe-yolo11n-coco --family yolo11 --version 8.3.0-coco

Requires `onnx` (requirements.txt). Never fabricates: missing file → error.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from zholsafe_ai.onnx_check import describe_model  # noqa: E402

ZHOLSAFE_CANONICAL = ["person", "dog", "horse", "cow", "sheep", "goat", "camel"]


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def infer_decoder(output_shape: list, num_classes: int) -> tuple[str, bool]:
    dims = [d if isinstance(d, int) else -1 for d in output_shape]
    if len(dims) != 3:
        raise ValueError(f"output rank {len(dims)} != 3: {output_shape}")
    if dims[2] == 6:
        return "YOLO_END2END_XYXY_CONF_CLS", True
    if dims[1] == 4 + num_classes:
        return "YOLO_RAW_CXCYWH_NC", False
    if dims[2] == 4 + num_classes:
        raise ValueError(f"output {output_shape} is transposed [1,N,4+nc]; Java decoder expects [1,4+nc,N]")
    raise ValueError(f"unrecognised output shape {output_shape} for {num_classes} classes")


def build_spec(model_dir: Path, model_id: str, family: str, version: str, conf: float, iou: float,
               max_det: int, source: str, dataset: str, precision: str) -> dict:
    model = model_dir / "model.onnx"
    labels_path = model_dir / "labels.txt"
    if not labels_path.exists():
        raise FileNotFoundError(f"labels file missing: {labels_path}")
    labels = [ln.strip() for ln in labels_path.read_text(encoding="utf-8").splitlines() if ln.strip() and not ln.startswith("#")]
    info = describe_model(model)
    if len(info["inputs"]) != 1:
        raise ValueError(f"expected 1 input, got {info['inputs']}")
    in_name, in_shape = info["inputs"][0]
    if len(in_shape) != 4 or in_shape[1] != 3:
        raise ValueError(f"expected NCHW [1,3,H,W] input, got {in_shape}")
    out_name, out_shape = info["outputs"][0]
    decoder, nms_in_model = infer_decoder(out_shape, len(labels))
    supported = sorted({l.upper() for l in labels if l.lower() in ZHOLSAFE_CANONICAL})
    unsupported = [c.upper() for c in ZHOLSAFE_CANONICAL if c.upper() not in supported]
    return {
        "modelId": model_id,
        "family": family,
        "version": version,
        "modelFile": "model.onnx",
        "labelsFile": "labels.txt",
        "inputName": in_name,
        "outputName": out_name,
        "inputWidth": int(in_shape[3]),
        "inputHeight": int(in_shape[2]),
        "inputChannels": 3,
        "layout": "NCHW",
        "inputType": "FLOAT32",
        "normalization": "SCALE_0_1",
        "letterbox": True,
        "padValue": 114,
        "confidenceThreshold": conf,
        "iouThreshold": iou,
        "decoder": decoder,
        "nmsInModel": nms_in_model,
        "numClasses": len(labels),
        "maxDetections": max_det,
        "labelAliases": {},
        "sha256": sha256_of(model),
        "metadata": {
            "source": source,
            "exportDate": dt.date.today().isoformat(),
            "trainingDataset": dataset,
            "opset": max(v for _, v in info["opset"]),
            "precision": precision,
            "expectedOutputShape": [d if isinstance(d, int) else -1 for d in out_shape],
            "canonicalClassesSupported": supported,
            "canonicalClassesNotSupported": unsupported,
            "status": "generated from real export by write_model_spec.py",
        },
    }


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--model-dir", type=Path, required=True)
    p.add_argument("--model-id", required=True)
    p.add_argument("--family", required=True)
    p.add_argument("--version", required=True)
    p.add_argument("--conf", type=float, default=0.35)
    p.add_argument("--iou", type=float, default=0.45)
    p.add_argument("--max-det", type=int, default=50)
    p.add_argument("--source", default="ultralytics pretrained weights")
    p.add_argument("--dataset", default="COCO 2017 (80 classes) — pretrained, NOT ZholSafe data")
    p.add_argument("--precision", default="FP32")
    a = p.parse_args()
    try:
        spec = build_spec(a.model_dir, a.model_id, a.family, a.version, a.conf, a.iou, a.max_det, a.source, a.dataset, a.precision)
    except (FileNotFoundError, ValueError, RuntimeError) as exc:
        print(f"REFUSED: {exc}", file=sys.stderr)
        return 3
    out = a.model_dir / "model-spec.json"
    out.write_text(json.dumps(spec, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out}: decoder={spec['decoder']} input={spec['inputWidth']}x{spec['inputHeight']} "
          f"classes={spec['numClasses']} supported={spec['metadata']['canonicalClassesSupported']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
