"""Repository-wide consistency of the class registry."""
from __future__ import annotations

import re
from pathlib import Path

from zholsafe_ai.classes import class_names, load_classes

REPO = Path(__file__).resolve().parents[2]
JAVA_ENUM = REPO / "android-app/core/src/main/java/kz/zholsafe/model/ObjectClass.java"
MODELS_ROAD = REPO / "models/road"
SERVER_ENUM = REPO / "zholnet-server/src/main/java/kz/zholsafe/server/hazard/HazardType.java"

REQUIRED = {"horse", "cow", "sheep", "goat", "camel", "dog", "person"}


def test_required_hazards_present():
    assert REQUIRED <= set(class_names())


def test_indices_are_contiguous():
    assert [c.index for c in load_classes()] == list(range(len(load_classes())))


def test_java_enum_matches_registry():
    src = JAVA_ENUM.read_text(encoding="utf-8")
    java_labels = set(re.findall(r'^\s+[A-Z_]+\("([a-z_]+)",', src, flags=re.M))
    java_labels.discard("unknown")
    assert java_labels == set(class_names())


def test_registry_canonical_labels_are_lowercase_and_unique():
    names = class_names()
    assert names == [n.lower() for n in names]
    assert len(names) == len(set(names))


def test_server_enum_covers_registry():
    src = SERVER_ENUM.read_text(encoding="utf-8")
    for name in class_names():
        assert name.upper() in src
