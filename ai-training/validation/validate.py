#!/usr/bin/env python3
"""Evaluate a trained checkpoint on the validation split (Stage 2).

Prints per-class metrics as produced by the framework. Does NOT invent numbers: if the
checkpoint or dataset is missing, it exits non-zero.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--weights", type=Path, required=True, help="path to best.pt")
    parser.add_argument("--data", type=Path, required=True, help="dataset data.yaml")
    args = parser.parse_args()
    if not args.weights.exists():
        print(f"weights not found: {args.weights}", file=sys.stderr)
        return 2
    try:
        from ultralytics import YOLO  # type: ignore
    except ImportError:
        print("Training stack not installed. Run: pip install -r requirements-train.txt", file=sys.stderr)
        return 2
    metrics = YOLO(str(args.weights)).val(data=str(args.data))
    print(metrics)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
