#!/usr/bin/env python3
"""Train the RoadGuard detector (Stage 2). Stage 0: documented entry point only.

Usage:
    python training/train.py --config configs/train_road_v0.yaml

Fails fast with a clear message if the training stack is not installed. Nothing is simulated.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import yaml


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    args = parser.parse_args()
    cfg = yaml.safe_load(args.config.read_text(encoding="utf-8"))
    try:
        from ultralytics import YOLO  # type: ignore
    except ImportError:
        print("Training stack not installed. Run: pip install -r requirements-train.txt", file=sys.stderr)
        return 2
    model = YOLO(cfg["model"])
    model.train(
        data=str(Path(args.config).parent / cfg["data"]),
        imgsz=cfg["imgsz"],
        epochs=cfg["epochs"],
        batch=cfg["batch"],
        project=cfg["project"],
        name=cfg["name"],
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
