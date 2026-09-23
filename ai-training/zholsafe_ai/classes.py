"""Canonical class registry helpers.

The registry lives in ``configs/classes.yaml``. This module reads it and can emit the label file
that ships next to the ONNX model so that the Java ``LabelMap`` maps indices correctly.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import yaml

AI_TRAINING_DIR = Path(__file__).resolve().parent.parent
CLASSES_YAML = AI_TRAINING_DIR / "configs" / "classes.yaml"


@dataclass(frozen=True)
class HazardClass:
    index: int
    name: str
    category: str
    initial_target: bool


def load_classes(path: Path = CLASSES_YAML) -> list[HazardClass]:
    with path.open("r", encoding="utf-8") as fh:
        data = yaml.safe_load(fh)
    result: list[HazardClass] = []
    for i, entry in enumerate(data["classes"]):
        result.append(
            HazardClass(
                index=i,
                name=str(entry["name"]).strip().lower(),
                category=str(entry["category"]),
                initial_target=bool(entry.get("initial_target", False)),
            )
        )
    names = [c.name for c in result]
    if len(names) != len(set(names)):
        raise ValueError(f"duplicate class names in {path}: {names}")
    return result


def class_names(path: Path = CLASSES_YAML) -> list[str]:
    return [c.name for c in load_classes(path)]


def write_label_file(dest: Path, path: Path = CLASSES_YAML) -> None:
    """Write one label per line; line number == model class index."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text("\n".join(class_names(path)) + "\n", encoding="utf-8")
