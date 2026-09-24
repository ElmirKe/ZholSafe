#!/usr/bin/env bash
# Builds the debug app, installs it on the USB-connected phone and, optionally, pushes a locally
# exported road model into the app's override folder (files/models/road/<id>/), which
# AssetModelFiles checks before the APK assets.
#
# Use the override when your export's SHA-256 differs from the committed model-spec.json (other
# torch/onnx versions produce byte-different but functionally identical graphs): export with
# ai-training/export/export_onnx.py into a separate folder and pass that folder here.
#
#   scripts/install-android.sh                         # build + install
#   scripts/install-android.sh /path/to/export yolo11n # + push model.onnx, model-spec.json, labels.txt
set -euo pipefail

MODEL_DIR="${1:-}"
MODEL_ID="${2:-yolo11n}"
ADB="${ADB:-adb}"
cd "$(dirname "$0")/../android-app"

./gradlew :app:assembleDebug
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk

if [ -n "$MODEL_DIR" ]; then
    target="files/models/road/$MODEL_ID"
    for f in model.onnx model-spec.json labels.txt; do
        "$ADB" push "$MODEL_DIR/$f" "/data/local/tmp/zs-$f"
    done
    "$ADB" shell run-as kz.zholsafe sh -c "'mkdir -p $target && for f in model.onnx model-spec.json labels.txt; do cp /data/local/tmp/zs-\$f $target/\$f; done'"
    "$ADB" shell rm -f /data/local/tmp/zs-model.onnx /data/local/tmp/zs-model-spec.json /data/local/tmp/zs-labels.txt
    echo "road model pushed to $target"
fi
