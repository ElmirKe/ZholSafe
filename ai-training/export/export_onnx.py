#!/usr/bin/env python3
"""Export best.pt → ONNX and write the matching label file (Stage 2).

Usage:
    python export/export_onnx.py --weights runs/road/v0/weights/best.pt \
        --out ../models/road/zholsafe-road.onnx

The label file is generated from configs/classes.yaml so Java's LabelMap stays consistent.
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from zholsafe_ai.classes import write_label_file  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True, help="destination .onnx path")
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--opset", type=int, default=17)
    args = parser.parse_args()
    if not args.weights.exists():
        print(f"weights not found: {args.weights}", file=sys.stderr)
        return 2
    try:
        from ultralytics import YOLO  # type: ignore
    except ImportError:
        print("Training stack not installed. Run: pip install -r requirements-train.txt", file=sys.stderr)
        return 2
    exported = YOLO(str(args.weights)).export(format="onnx", imgsz=args.imgsz, opset=args.opset, simplify=True)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(exported, args.out)
    write_label_file(args.out.with_name(args.out.stem + "-classes.txt"))
    print(f"exported {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
