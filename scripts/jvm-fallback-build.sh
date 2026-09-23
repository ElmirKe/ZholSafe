#!/usr/bin/env bash
# Fallback JVM build for android-app/core when Gradle/JDK are not available (e.g. sandboxed CI).
# Compiles with the Eclipse Compiler for Java (ECJ) and runs JUnit 5 via the console launcher.
# Normal developers should use:  cd android-app && ./gradlew :core:test
#
# Required env:
#   ZS_JAVA   - path to a `java` binary (17+)
#   ZS_ECJ    - path to ecj.jar (org.eclipse.jdt.core.compiler.batch)
#   ZS_JUNIT  - path to junit-platform-console-standalone.jar
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE="$ROOT/android-app/core"
OUT="$ROOT/build/core-fallback"
: "${ZS_JAVA:?set ZS_JAVA}" "${ZS_ECJ:?set ZS_ECJ}" "${ZS_JUNIT:?set ZS_JUNIT}"

rm -rf "$OUT" && mkdir -p "$OUT/main" "$OUT/test"
echo "== compile main (Java 17 level)"
"$ZS_JAVA" -jar "$ZS_ECJ" -17 -proceedOnError:Fatal -warn:none -d "$OUT/main" "$CORE/src/main/java"
echo "== compile tests"
"$ZS_JAVA" -jar "$ZS_ECJ" -17 -proceedOnError:Fatal -warn:none -cp "$OUT/main:$ZS_JUNIT" -d "$OUT/test" "$CORE/src/test/java"
echo "== run tests"
"$ZS_JAVA" -jar "$ZS_JUNIT" execute --class-path "$OUT/main:$OUT/test" --scan-class-path --details=tree --disable-banner
