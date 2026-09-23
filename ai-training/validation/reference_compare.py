#!/usr/bin/env python3
"""Stage 2.5 REFERENCE ONLY: run the same ONNX model through official Ultralytics inference and
compare with the ZholSafe Java smoke-test results (results.json).

The Python result is NOT the ZholSafe result; it is an independent sanity reference. Matching is
class + greedy best IoU. Ultralytics is run with the same conf/iou thresholds as model-spec.json and
imgsz taken from the spec, using its own letterbox (auto=False, i.e. full 640x640 padded like the
Java path) and its own BGR→RGB handling. Residual differences are expected from: NV21 chroma
subsampling in the Java path (by design), nearest-neighbour vs bilinear resize, and Ultralytics'
class-agnostic NMS default (agnostic=False, i.e. class-aware — same policy as Java).

Optionally checks the Java input tensor dump (--tensor-dump) against Ultralytics' preprocessing
to detect RGB/BGR or normalisation mistakes independently of the detector.

Usage:
    python validation/reference_compare.py --model ../models/road/yolo11n/model.onnx \
        --spec ../models/road/yolo11n/model-spec.json --images /path/to/images \
        --java-results ../demo/stage2_5/results/results.json --out ../demo/stage2_5/results/reference_comparison.json
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

CANON = {"person": "PERSON", "dog": "DOG", "horse": "HORSE", "cow": "COW", "sheep": "SHEEP", "goat": "GOAT", "camel": "CAMEL"}


def iou(a, b):
    ix1, iy1 = max(a[0], b[0]), max(a[1], b[1])
    ix2, iy2 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / ua if ua > 0 else 0.0


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--model", type=Path, required=True)
    p.add_argument("--spec", type=Path, required=True)
    p.add_argument("--images", type=Path, required=True)
    p.add_argument("--java-results", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--tensor-dump", type=Path, default=None, help="<name>_input_tensor_f32.bin written by the Java harness")
    p.add_argument("--tensor-image", type=Path, default=None)
    a = p.parse_args()
    try:
        from ultralytics import YOLO  # type: ignore
        import numpy as np
        import ultralytics
    except ImportError as e:
        print(f"REFERENCE UNAVAILABLE: {e}", file=sys.stderr)
        return 2
    spec = json.loads(a.spec.read_text())
    java = json.loads(a.java_results.read_text())
    conf, iou_thr, imgsz = spec["confidenceThreshold"], spec["iouThreshold"], spec["inputWidth"]
    model = YOLO(str(a.model), task="detect")
    report = {"kind": "REFERENCE ONLY — official Ultralytics inference on the same ONNX file; not the ZholSafe result",
              "ultralytics_version": ultralytics.__version__, "conf": conf, "iou": iou_thr, "imgsz": imgsz,
              "matching": "same canonical class, greedy best IoU >= 0.5", "images": []}
    tot_match = tot_java = tot_ref = 0
    ious, dconf = [], []
    for jimg in java["images"]:
        img_path = a.images / jimg["filename"]
        if not img_path.exists() or jimg.get("status") != "DETECTOR_OK":
            continue
        res = model.predict(str(img_path), imgsz=imgsz, conf=conf, iou=iou_thr, max_det=spec["maxDetections"], verbose=False, device="cpu")[0]
        ref = []
        for b, c, s in zip(res.boxes.xyxy.tolist(), res.boxes.cls.tolist(), res.boxes.conf.tolist()):
            label = res.names[int(c)]
            if label in CANON:
                ref.append({"object_class": CANON[label], "model_label": label, "confidence": float(s), "bbox": [round(v, 1) for v in b]})
        jd = list(jimg["detections"])
        matches, unmatched_java = [], []
        used = set()
        for d in jd:
            best, best_iou = None, 0.0
            for k, r in enumerate(ref):
                if k in used or r["object_class"] != d["object_class"]:
                    continue
                v = iou(d["bbox"], r["bbox"])
                if v > best_iou:
                    best, best_iou = k, v
            if best is not None and best_iou >= 0.5:
                used.add(best)
                r = ref[best]
                matches.append({"class": d["object_class"], "iou": round(best_iou, 4), "java_conf": d["confidence"], "ref_conf": round(r["confidence"], 4),
                                "conf_delta": round(d["confidence"] - r["confidence"], 4), "java_bbox": d["bbox"], "ref_bbox": r["bbox"]})
                ious.append(best_iou)
                dconf.append(abs(d["confidence"] - r["confidence"]))
            else:
                unmatched_java.append(d)
        unmatched_ref = [r for k, r in enumerate(ref) if k not in used]
        tot_match += len(matches)
        tot_java += len(jd)
        tot_ref += len(ref)
        report["images"].append({"filename": jimg["filename"], "java_detections": len(jd), "reference_detections": len(ref), "matched": len(matches),
                                 "matches": matches, "java_only": unmatched_java, "reference_only": unmatched_ref})
    report["summary"] = {"java_total": tot_java, "reference_total": tot_ref, "matched": tot_match,
                         "mean_iou_matched": round(sum(ious) / len(ious), 4) if ious else None,
                         "min_iou_matched": round(min(ious), 4) if ious else None,
                         "mean_abs_conf_delta": round(sum(dconf) / len(dconf), 4) if dconf else None,
                         "max_abs_conf_delta": round(max(dconf), 4) if dconf else None}
    if a.tensor_dump and a.tensor_image:
        import cv2
        from ultralytics.data.augment import LetterBox
        jt = np.fromfile(a.tensor_dump, dtype="<f4").reshape(1, 3, imgsz, imgsz)
        im = cv2.imread(str(a.tensor_image))
        lb = LetterBox((imgsz, imgsz), auto=False, stride=32)(image=im)
        rt = lb[:, :, ::-1].transpose(2, 0, 1)[None].astype(np.float32) / 255.0  # BGR→RGB, HWC→CHW, 0..1
        diff = np.abs(jt - rt)
        # channel-swap probe: if Java were BGR, comparing against swapped reference would fit better
        swapped = np.abs(jt - rt[:, ::-1]).mean()
        report["tensor_check"] = {"image": a.tensor_image.name, "mean_abs_diff_vs_ultralytics_preprocess": float(diff.mean()),
                                  "p99_abs_diff": float(np.percentile(diff, 99)), "max_abs_diff": float(diff.max()),
                                  "mean_abs_diff_if_channels_were_swapped": float(swapped),
                                  "channel_order_ok": bool(diff.mean() < swapped),
                                  "per_channel_mean_java": [float(v) for v in jt.mean(axis=(0, 2, 3))],
                                  "per_channel_mean_ref": [float(v) for v in rt.mean(axis=(0, 2, 3))],
                                  "note": "Java path = NV21 round trip + nearest-neighbour; Ultralytics = bilinear RGB; small diffs expected"}
    a.out.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report["summary"], indent=2))
    if "tensor_check" in report:
        print(json.dumps(report["tensor_check"], indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
