#!/usr/bin/env bash
# Compiles the app's ONNX Runtime adapter (kz.zholsafe.ai.Ort*) against the REAL ONNX Runtime Java
# sources of the version declared in android-app/app/build.gradle. This is an API-compatibility
# check (signatures, exceptions, types) — NOT a runtime test; it does not need the Android AAR.
#
# Env: ZS_JAVA (java 17+), ZS_ECJ (ecj.jar) or a javac on PATH, ZS_ANDROID_STUBS (dir with minimal
# android.*/androidx.* stubs used only for the non-ORT parts of the app), ORT_SRC (checkout of
# https://github.com/microsoft/onnxruntime at tag v<version>, sparse: java/src/main/{java,jvm}).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VER=$(grep -oE "onnxRuntimeVersion = '[0-9.]+'" "$ROOT/android-app/app/build.gradle" | grep -oE '[0-9.]+')
: "${ORT_SRC:?set ORT_SRC to an onnxruntime checkout at tag v$VER}"
: "${ZS_ANDROID_STUBS:?set ZS_ANDROID_STUBS}"
OUT=$(mktemp -d)
SRCS=$(find "$ORT_SRC/java/src/main/java" "$ORT_SRC/java/src/main/jvm" -name '*.java'; \
       find "$ZS_ANDROID_STUBS" -name '*.java' -not -path "$ZS_ANDROID_STUBS/ai/onnxruntime/*"; \
       find "$ROOT/android-app/core/src/main/java" "$ROOT/android-app/app/src/main/java" -name '*.java')
if [ -n "${ZS_ECJ:-}" ]; then
  "${ZS_JAVA:-java}" -jar "$ZS_ECJ" -17 -nowarn -proc:none -d "$OUT" $SRCS
else
  javac --release 17 -proc:none -d "$OUT" $SRCS
fi
ls "$OUT/kz/zholsafe/ai/OrtTensorSession.class" "$OUT/kz/zholsafe/ai/OrtSessionFactory.class" >/dev/null
echo "ORT adapter compiles against onnxruntime Java sources v$VER (API check only; runtime NOT exercised)"
