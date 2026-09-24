# Stage 7 build matrix

Baseline: `d30bfff75c6b4476b13cc984a5219a80c32ced4e` on `arena/01a0cebd-zholsafe`.

| Component | Runtime | Build/test command | Executed | Result |
|---|---|---|---|---|
| `android-app/core` | Java 17/JVM | Gradle `clean :core:test` | YES | PASS — 316 tests |
| `android-app/ort-adapter` | Java 17/ONNX Runtime API | Gradle `:ort-adapter:build` | YES | PASS; no module tests |
| `android-app/smoke-test` | Java 17/desktop ORT | Gradle `:smoke-test:test` | YES | PASS — 5 utility tests |
| Real-model smoke run | Java 17/desktop ORT | `scripts/run-smoke-test.sh` | NO | NOT AVAILABLE — git-ignored `model.onnx` absent; accepted Stage 2.5 artifacts retained |
| `android-app/app` | Android SDK 34 | Gradle `:app:testDebugUnitTest :app:assembleDebug` | YES | PASS; unit-test task NO-SOURCE; APK assembled |
| `zholnet-server` | Java 21/Spring Boot | Maven `clean test` | YES | PASS — 52 tests |
| Hazard JSON contract | Python 3 stdlib | `python scripts/check_contracts.py` | YES | PASS |
| AI tooling | Python | `python -m pytest ai-training/tests -q` | YES | NOT AVAILABLE — `pytest` is not installed |
| PostgreSQL/PostGIS runtime | Docker/PostGIS | `docker compose up` | NO | NOT AVAILABLE — Docker/psql absent |

The Android build is a real SDK build, not syntax parsing. No Android device was used.
