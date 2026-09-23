#!/usr/bin/env bash
# Stage 2.5: build and run the desktop/JVM real-model smoke test WITHOUT Gradle (sandbox fallback).
# Normal developers: cd android-app && ./gradlew :smoke-test:run --args="--model-dir ../models/road/yolo11n ..."
#
# Required env:
#   ZS_JAVA      java 17+ binary
#   ZS_ECJ       ecj.jar (or leave unset to use javac on PATH)
#   ZS_ORT_JAR   onnxruntime Java classes jar (com.microsoft.onnxruntime:onnxruntime:<ver> or a jar built
#                from the onnxruntime java sources of the same version)
#   ZS_ORT_NATIVE  directory containing libonnxruntime.so and libonnxruntime4j_jni.so for this platform
#                (passed as -Donnxruntime.native.path; not needed when the jar bundles the natives)
# Remaining arguments are forwarded to SmokeTestRunner (see its javadoc for the CLI).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/smoke-test"
: "${ZS_JAVA:?set ZS_JAVA}" "${ZS_ORT_JAR:?set ZS_ORT_JAR}"
rm -rf "$OUT" && mkdir -p "$OUT/classes"
SRCS=$(find "$ROOT/android-app/core/src/main/java" "$ROOT/android-app/ort-adapter/src/main/java" \
            "$ROOT/android-app/smoke-test/src/main/java" -name '*.java')
echo "== compile core + ort-adapter + smoke-test (Java 17 level)"
if [ -n "${ZS_ECJ:-}" ]; then
  "$ZS_JAVA" -jar "$ZS_ECJ" -17 -proceedOnError:Fatal -warn:none -proc:none -cp "$ZS_ORT_JAR" -d "$OUT/classes" $SRCS
else
  javac --release 17 -proc:none -cp "$ZS_ORT_JAR" -d "$OUT/classes" $SRCS
fi
NATIVE_OPT=()
if [ -n "${ZS_ORT_NATIVE:-}" ]; then NATIVE_OPT=(-Donnxruntime.native.path="$ZS_ORT_NATIVE"); fi
echo "== run"
cd "$ROOT"
exec "$ZS_JAVA" -Djava.awt.headless=true "${NATIVE_OPT[@]}" -cp "$OUT/classes:$ZS_ORT_JAR" kz.zholsafe.smoke.SmokeTestRunner "$@"
