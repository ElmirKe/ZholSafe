"""Sanity checks for an exported ONNX model before handing it to the Java side.

Never fabricates results: if ``onnx`` is not installed or the file is missing, it raises.
"""
from __future__ import annotations

from pathlib import Path


def describe_model(path: Path) -> dict:
    if not path.exists():
        raise FileNotFoundError(f"ONNX model not found: {path}")
    try:
        import onnx  # noqa: WPS433 (optional heavy dependency)
    except ImportError as exc:  # pragma: no cover - environment dependent
        raise RuntimeError("pip install onnx  (see requirements.txt)") from exc
    model = onnx.load(str(path))
    onnx.checker.check_model(model)
    inputs = [(i.name, [d.dim_value or d.dim_param for d in i.type.tensor_type.shape.dim]) for i in model.graph.input]
    outputs = [(o.name, [d.dim_value or d.dim_param for d in o.type.tensor_type.shape.dim]) for o in model.graph.output]
    return {
        "opset": [(op.domain or "ai.onnx", op.version) for op in model.opset_import],
        "inputs": inputs,
        "outputs": outputs,
    }
