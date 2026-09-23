"""Every committed models/road/<id>/ directory must have a consistent spec + labels pair.

This does NOT require model.onnx (git-ignored). It validates the same invariants the Java
ModelSpec/ModelSpecParser enforce, so a broken spec is caught before it reaches a device.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
MODEL_DIRS = sorted(p for p in (REPO / "models/road").iterdir() if p.is_dir())
CANONICAL = {"person", "dog", "horse", "cow", "sheep", "goat", "camel"}
REQUIRED = {"modelId", "family", "version", "modelFile", "labelsFile", "inputWidth", "inputHeight", "layout",
            "inputType", "normalization", "letterbox", "confidenceThreshold", "iouThreshold", "decoder",
            "nmsInModel", "numClasses"}


@pytest.mark.parametrize("model_dir", MODEL_DIRS, ids=[p.name for p in MODEL_DIRS])
def test_spec_and_labels_consistent(model_dir: Path):
    spec = json.loads((model_dir / "model-spec.json").read_text(encoding="utf-8"))
    assert REQUIRED <= spec.keys(), REQUIRED - spec.keys()
    labels = [l.strip() for l in (model_dir / spec["labelsFile"]).read_text(encoding="utf-8").splitlines() if l.strip()]
    assert len(labels) == spec["numClasses"]
    assert spec["decoder"] in {"YOLO_RAW_CXCYWH_NC", "YOLO_END2END_XYXY_CONF_CLS"}
    assert spec["nmsInModel"] == (spec["decoder"] == "YOLO_END2END_XYXY_CONF_CLS")
    assert 0 <= spec["confidenceThreshold"] <= 1 and 0 <= spec["iouThreshold"] <= 1
    supported = {l.lower() for l in labels} & CANONICAL
    assert supported, "model exposes no canonical ZholSafe class"
    meta = spec.get("metadata", {})
    if "canonicalClassesSupported" in meta:
        assert set(meta["canonicalClassesSupported"]) == {s.upper() for s in supported}
    for alias_from, alias_to in spec.get("labelAliases", {}).items():
        assert alias_to in CANONICAL, alias_from


@pytest.mark.parametrize("model_dir", MODEL_DIRS, ids=[p.name for p in MODEL_DIRS])
def test_pretrained_coco_specs_do_not_claim_goat_or_camel(model_dir: Path):
    spec = json.loads((model_dir / "model-spec.json").read_text(encoding="utf-8"))
    labels = {l.strip().lower() for l in (model_dir / spec["labelsFile"]).read_text(encoding="utf-8").splitlines()}
    for cls in ("goat", "camel"):
        if cls not in labels:
            assert cls.upper() not in spec.get("metadata", {}).get("canonicalClassesSupported", [])
