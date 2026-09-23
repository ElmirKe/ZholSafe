#!/usr/bin/env python3
"""Validate tests/fixtures/*.json against tests/contracts/*.schema.json.

Uses a deliberately small built-in validator (types, enum, min/max, required,
additionalProperties) so no third-party dependency is needed. Exits non-zero on failure.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / "tests/contracts/hazard-event.v1.schema.json"
FIXTURE = ROOT / "tests/fixtures/hazard-event.v1.example.json"

TYPES = {"string": str, "number": (int, float), "integer": int, "boolean": bool, "object": dict, "array": list, "null": type(None)}


def validate(instance: dict, schema: dict) -> list[str]:
    errors: list[str] = []
    for key in schema.get("required", []):
        if key not in instance:
            errors.append(f"missing required '{key}'")
    props = schema.get("properties", {})
    if not schema.get("additionalProperties", True):
        for key in instance:
            if key not in props:
                errors.append(f"unexpected property '{key}'")
    for key, rule in props.items():
        if key not in instance:
            continue
        value = instance[key]
        types = rule.get("type")
        if types:
            allowed = tuple(TYPES[t] for t in ([types] if isinstance(types, str) else types))
            if isinstance(value, bool) and bool not in allowed:
                errors.append(f"'{key}' has wrong type")
            elif not isinstance(value, allowed):
                errors.append(f"'{key}' has wrong type")
                continue
        if "enum" in rule and value not in rule["enum"]:
            errors.append(f"'{key}'={value!r} not in {rule['enum']}")
        if "minimum" in rule and value < rule["minimum"]:
            errors.append(f"'{key}' below minimum")
        if "maximum" in rule and value > rule["maximum"]:
            errors.append(f"'{key}' above maximum")
        if "minLength" in rule and len(value) < rule["minLength"]:
            errors.append(f"'{key}' too short")
    return errors


def main() -> int:
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    errors = validate(fixture, schema)
    if errors:
        print("CONTRACT CHECK FAILED:\n  " + "\n  ".join(errors))
        return 1
    print(f"contract OK: {FIXTURE.relative_to(ROOT)} matches {SCHEMA.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
