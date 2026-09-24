# Stage 7 verification report

Baseline: `d30bfff75c6b4476b13cc984a5219a80c32ced4e`.

## Results

| Verification | Status | Evidence/result |
|---|---|---|
| Clean Gradle matrix | PASS | `clean :core:test :smoke-test:test :ort-adapter:build :app:testDebugUnitTest :app:assembleDebug`; 316 core + 5 smoke utility tests; build successful in 32 s |
| Android APK | PASS | SDK 34 build; min 26, target/compile 34; final self-check artifact 79,751,706 bytes |
| Android unit tests | NOT AVAILABLE | Gradle task executed and reported `NO-SOURCE` |
| Server | PASS | Maven `clean test`; 52/52 tests |
| Wire contract | PASS | `scripts/check_contracts.py` |
| AI Python tests | NOT AVAILABLE | Python exists, but `pytest` is not installed |
| Real-model rerun | NOT EXECUTED | `models/road/yolo11n/model.onnx` absent; accepted Stage 2.5 desktop artifacts preserved |
| Real PostGIS | NOT EXECUTED | Docker and `psql` unavailable |
| Device/camera/GPS | NOT EXECUTED | No physical Android device used |
| Vehicle A → B deterministic path | PASS | Existing Stage 6 bridge/contract/evaluator tests assert HORSE, AHEAD, WARNING and ≈420 m through a simulated server boundary |

Complete executed automated count: **373 tests** (316 core + 5 smoke utility + 52 server), all
passing. The schema contract check also passed and is reported separately because it is a script,
not a test-runner case.

## Safety boundary audit

- `RoadDetectionProcessor`, tracker, trajectory, physical processors and risk engines have no
  network dependency. `RoadHazardNetworkCoordinator` is invoked only after a local snapshot exists.
- `HazardEventBridge` is pure mapping; missing/stale GPS returns a skip outcome without mutating risk.
- `QueuedHazardPublisher` owns a bounded metadata queue and bounded retries on its own executor.
- `RemoteHazardEvaluator` returns only `RemoteHazardSnapshot`; it has no RoadRisk/CombinedRisk API.
- `RemoteHazardWarning` intentionally has no TTC field, preventing Vehicle A TTC presentation as
  Vehicle B TTC.
- `RemoteHazardPoller` uses one in-flight gate and lifecycle generation. Failure changes only the
  remote snapshot.
- Camera processing uses the one-slot `LatestFrameQueue`; network latency cannot retain frames.

Existing regression tests explicitly cover dead-server local-risk independence, missing/stale GPS,
bounded publisher queue, overlapping poll prevention, bounded registry, unsupported detector
CAMEL/GOAT mapping, bilateral closure, conflicted physical evidence, Stage 5 JSON compatibility and
the deterministic two-vehicle scenario. No missing safety regression required new production code.

## Technical audits

- Detector: preprocessing, letterbox inverse, tensor shapes/types, decoder, NMS, label mapping and
  finite values are covered. Desktop YOLO11n ONNX verification artifacts support PERSON, DOG,
  HORSE, COW and SHEEP. CAMEL/GOAT are not claimed for the current detector.
- Tracking: ByteTrack-inspired (not official ByteTrack), bounded history, non-reused IDs and explicit
  tentative/confirmed/lost semantics. Crossing/occlusion identity switches remain possible.
- Physical: optical-axis Z, not slant/road/GPS distance; calibration and evidence quality are
  explicit. Conflicts remain unavailable and size-only evidence cannot silently authorize CRITICAL.
- RoadRisk: current confirmed tracks only, explicit CRITICAL gates, engineering severity rather than
  probability. Optical-only escalation remains an experimental MVP limitation.
- DriverGuard: bilateral closure, source timestamps, gap/reversal behavior, time-weighted PERCLOS,
  bounded history and no retained images verified. The 1.5 s setting is experimental, not medical.
- CombinedRisk: explicit matrix plus freshness/skew; remote advisories cannot enter it.
- Server: tests cover validation, TTL/dedup/idempotency/conflict behavior, query SQL contract,
  ordering and privacy. Migration declares PostGIS geography(Point,4326), GiST and expiry indexes,
  but runtime PostGIS execution is unverified here.

## Claims, security and privacy

Repository searches found no committed production API keys, personal device identifiers, camera or
biometric upload path. Demo database credentials are placeholders and documented as non-production.
No claim is made for 200 m detection, night reliability, Android real-time performance, medical
drowsiness diagnosis, real-road validation, official checkpoint provenance, or production safety.
The Stage 2.5 checkpoint self-identification is consistent, but upstream provenance was not
independently confirmed in that sandbox.

## Performance scope

The clean desktop build/test matrix completed in 32 seconds in this Windows environment. Existing
Stage 2.5 desktop benchmark artifacts remain the detector evidence. No Android latency or FPS was
measured, and no unstable wall-clock microbenchmark was added for the small mapper/evaluator code.

## Classification

**HACKATHON DEMO READY** — builds/tests and the deterministic fallback demo are reproducible.

**PRODUCTION ROAD-SAFETY READY: NO.** Device, real PostGIS, road/night performance, calibration,
security hardening and safety validation remain outstanding.
