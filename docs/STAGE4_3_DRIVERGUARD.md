# Stage 4.3 — DriverGuard + explainable road/driver risk fusion

**Status: CODE COMPLETE (pure Java core). Java test execution NOT VERIFIED in the authoring
sandbox (no JVM available — see "Verification status"). Android build NOT EXECUTED. Real driver
camera NOT VERIFIED. Landmark backend NOT VERIFIED.**

> **ZholSafe DriverGuard is an engineering prototype. It is NOT a medical diagnostic system and
> NOT a clinically validated microsleep detector.** It produces *temporal engineering evidence*
> (PROLONGED_EYE_CLOSURE, windowed PERCLOS-like fraction, yawn-LIKE persistence, head direction
> persistence, monitoring/visibility loss) with explicitly EXPERIMENTAL thresholds and derives a
> deterministic, explainable engineering severity from it. It never claims "the driver is drowsy",
> "the driver is asleep" or any clinical state.

## 1. What Stage 4.3 adds

```
DRIVER FRAME SOURCE ──► DriverObservationProvider ──► DriverObservation
                                                          │  (pure Java, bounded memory)
                                                          ▼
                                              TemporalDriverStateAnalyzer
                                                          │  (source-time temporal evidence)
                                                          ▼
                                                    DriverState
                                                          │
                                                          ▼
                                                  DriverRiskEngine ──► DriverRiskSnapshot

ROAD PIPELINE (Stage 4.2, UNCHANGED) ──────────────────────────────► RoadRiskSnapshot

RoadRiskSnapshot + DriverRiskSnapshot
                          │
                          ▼
                 CombinedRiskEngine ──► CombinedRiskSnapshot   (deterministic rule matrix,
                                                                 freshness budgets, degraded modes)
```

Four responsibilities stay separate and independently testable:

1. **Driver observation/inference** — the `DriverObservationProvider` port only (`kz.zholsafe.driver`).
2. **Temporal driver-state analysis** — `TemporalDriverStateAnalyzer` (implements `DriverStateAnalyzer`).
3. **Driver risk assessment** — `DriverRiskEngine` → `DriverRiskSnapshot` (`kz.zholsafe.risk`).
4. **Road/driver risk fusion** — `CombinedRiskEngine` → `CombinedRiskSnapshot` (`kz.zholsafe.risk`).

There is no opaque monolithic "drowsiness AI" and no medical semantics anywhere.

The Stage 0 placeholder contract (`DrowsinessAnalyzer` interface, binary
`eyesClosed`/`yawning` flags) was *refactored in place* — same files where compatible, explicit
unavailable semantics preserved — rather than duplicated.

## 2. DriverObservationProvider (observation port)

`kz.zholsafe.driver.DriverObservationProvider`:

```java
String sourceId();
DriverObservation provide(Frame frame) throws DetectionException;
```

The temporal core depends only on this interface and the resulting scalars — never on MediaPipe,
ML Kit, a neural network or the Android camera. Shipped implementations:

- `SyntheticDriverObservationProvider` — deterministic script
  (`NavigableMap<Long, DriverObservation>`: "at or after source time T → template"), re-stamped
  with the frame's source timestamp. Frames before the first script entry yield
  `DriverObservation.noFace`. Feeds the SAME production chain (analyzer → risk → fusion).
  It fabricates *observations* only — never `DriverState` or final risk snapshots.
- `kz.zholsafe.ai.DriverDetector` — model-lifecycle variant of the port (`load()`/`state()`),
  mirroring `RoadDetector`. Interface only; the intended Android backend is Google AI Edge /
  MediaPipe face landmarks (`MediaPipeDriverObservationProvider`, future stage).

**LANDMARK BACKEND: NOT VERIFIED** — no MediaPipe/ML Kit integration ships or runs in this stage.

## 3. DriverObservation contract

Immutable record, one frame, source-time based; no `Frame`/image/bitmap/tensor is retained
(checked by reflection in `DriverGuardContractTest`).

| field | meaning | unavailable representation |
|---|---|---|
| `timestampNanos` | source/image capture time (same-source monotonic) | ≥ 0 always |
| `faceDetected` | a face was found | `false` (≠ "eyes open", ≠ "asleep") |
| `eyeOpennessAvailable` | both openness values are real measurements | `false` ⇒ both NaN |
| `leftEyeOpenness`, `rightEyeOpenness` | continuous openness in [0,1] | NaN when unavailable |
| `mouthAvailable`, `mouthOpenScore` | optional yawn-LIKE engineering score in [0,1] | NaN when unavailable |
| `headPose` | optional `HeadPose` angles | `HeadPose.UNAVAILABLE` |
| `confidence` | provider confidence in [0,1] | any value in [0,1]; low values disqualify evidence downstream |

Rules enforced by the record: finite when available, [0,1] when available, **zero is a real
measurement** (`0.0f` openness = "measured fully closed", never "unavailable"), unavailable
values are NaN with a false flag, face-derived measurements cannot exist without a face.

## 4. Eye state (qualitative, configurable)

`EyeState ∈ {OPEN, PARTIALLY_CLOSED, CLOSED, UNKNOWN}` from `min(left,right)` openness vs
`DriverGuardConfig.eyeClosedThreshold` (0.30) / `eyePartiallyClosedThreshold` (0.60).
**UNKNOWN** when the face is missing, eyes are not evaluable or confidence is below
`minimumObservationConfidence` (0.50). UNKNOWN is never counted as OPEN or CLOSED — anywhere.
No state (and certainly no "drowsiness") is inferred from one frame.

## 5. Continuous eye closure (source-time only)

`continuousEyeClosureNanos = currentSourceTimestamp − closureStartSourceTimestamp`, maintained by
consecutive CLOSED observations. Explicitly handled:

- **Reopening** (OPEN/PARTIALLY_CLOSED) → run resets, duration 0.
- **UNKNOWN eye state / face loss** → run breaks (missing ≠ closed); a later CLOSED observation
  starts a NEW run. The pre-loss run is not silently continued.
- **Timestamp gaps** (`> maximumObservationGapSeconds`) → continuity breaks; the bridge time is
  unknown, not closed.
- **Duplicate / reversed timestamps** → the observation is explicitly REJECTED
  (`DriverState.TimestampRejection.DUPLICATE_TIMESTAMP` / `REVERSED_TIMESTAMP`); accepted
  temporal state is untouched.
- All of this uses `DriverObservation.timestampNanos` exclusively. `System.nanoTime()` is used
  nowhere in temporal logic (it exists only for processing telemetry elsewhere).

A normal short blink (< `prolongedClosureCautionSeconds`) can never produce WARNING or CRITICAL
(`TemporalDriverStateAnalyzerEyeTest.shortBlinkNeverReachesWarningOrCritical`).

The WARNING closure threshold (~1.5 s default) is an **EXPERIMENTAL DEMO THRESHOLD** used to emit
the `PROLONGED_EYE_CLOSURE` evidence code. It is **not** a universal or medical definition of
microsleep; this stage deliberately avoids the word "microsleep" as a product claim.

## 6. PERCLOS-like metric (time-weighted, bounded, coverage-aware)

`PerclosValue` = engineering PERCLOS-*like* metric over the recent bounded window:

```
value = Σ closed-eye segment time / Σ valid observed-eye segment time        (source-time segment
        over [now − perclosWindowSeconds, now]                                 durations, never frame counts)
```

- A segment between consecutive samples counts only if its start sample had valid eye evidence
  AND its span ≤ the maximum observation gap; gaps and UNKNOWN segments enter neither numerator
  nor denominator (**missing ≠ open, missing ≠ closed**).
- Only CLOSED segments count as closed. PARTIALLY_CLOSED counts as valid-but-not-closed
  (documented engineering choice, not a prescription).
- Exposed: `available`, `value ∈ [0,1]`, `validObservationDurationNanos`, `windowDurationNanos`.
- **Availability = maturity + coverage in one rule**: the valid observed time inside the window
  must reach `minimumPerclosValidCoverage × configured window` (i.e. ≥ 30 s of valid eye time
  inside a 60 s window with defaults). This deliberately keeps the metric UNAVAILABLE for young
  windows — a 1-second-old 100%-closed window is a closure event handled by the closure timers,
  not a PERCLOS measurement — and whenever valid coverage is insufficient. Insufficient coverage
  yields `available=false` and `value=NaN`, **never 0.0** (0.0 would falsely claim "observed open").

`PerclosTest` covers: mostly-open → low value; ~50% closed elapsed time with irregular intervals
→ ≈0.5 (time-weighted, not frame-counted); missing face excluded from the denominator;
insufficient coverage → unavailable; window eviction; UNKNOWN eye state exclusion; the hard
observation cap.

## 7. Bounded temporal memory

The analyzer retains only:

- one ring of `(timestampNanos, eyeClass)` samples, evicted by the PERCLOS window (time) AND by
  the hard `maxObservations` cap (defensive bound), and
- a handful of run-start timestamps (closure, face loss, eye-unavailable, mouth-open, head-away).

No `Frame`, image, bitmap, tensor or `ByteBuffer` is ever stored (reflection-checked in tests).
The processor (`DriverGuardProcessor`) and fusion holder (`CombinedRiskProcessor`) keep exactly
one latest snapshot per subsystem — no queues can grow.

## 8. Yawn-like evidence (mouth)

When mouth evidence exists, the analyzer tracks the source-time mouth-open run
(`mouthOpenScore ≥ mouthOpenThreshold`). `YawnLikeState`:

- `NONE` — mouth shut;  `MOUTH_OPEN` — open but not persistent;
- `YAWN_LIKE` — open persisted ≥ `minimumYawnDurationSeconds` ⇒ `YAWN_LIKE_EVENT` evidence (CAUTION);
- `UNAVAILABLE` — no face / no mouth landmarks / low confidence: the signal is explicitly marked
  unavailable, never fabricated.

A single open-mouth frame never escalates (`YawnHeadPoseTest`).

## 9. Head pose (qualitative)

`HeadPoseState ∈ {FORWARD, LEFT, RIGHT, DOWN, UNKNOWN}` from yaw/pitch with configurable
thresholds (`headYawThresholdDegrees`, `headDownPitchThresholdDegrees`; DOWN wins over lateral;
UNKNOWN when pose evidence is missing — never assumed FORWARD). A brief head movement never
escalates; persistence past `headAwayDurationSeconds` produces `HEAD_AWAY` (lateral, CAUTION) or
`LOOKING_DOWN` (WARNING). Roll is recorded but not classified in this stage.

## 10. Face-visibility policy

`FACE_NOT_DETECTED` never means "driver asleep" (causes: darkness, sunglasses, occlusion, angle,
face outside frame, backend failure). One missing frame does nothing. Persistence past
`persistentFaceLossSeconds` yields CAUTION with `DRIVER_VISIBILITY_LOST` + `FACE_NOT_DETECTED` —
monitoring/visibility evidence, explicitly kept distinct from fatigue evidence. Face present but
eyes not evaluable is `EyeState.UNKNOWN` (not CLOSED) and, if persistent past
`eyeVisibilityLostSeconds`, CAUTION with `INSUFFICIENT_EYE_VISIBILITY`.

## 11. DriverState / DriverRiskEngine

`DriverState` (immutable) carries: `timestampNanos`, `faceDetected`, `eyeState`,
`continuousEyeClosureNanos`, `perclos` (`PerclosValue`), `headPoseState`,
`continuousHeadAwayNanos`, raw `headPose`, `yawnLikeState`, `continuousYawnLikeNanos`,
`continuousFaceLossNanos`, `continuousEyeUnavailableNanos`, `observationQuality`, `confidence`,
`timestampRejection`. Unavailable quantities stay explicitly unavailable; coherence invariants
(no face ⇒ UNKNOWN eyes/head/yawn; closure > 0 ⇒ CLOSED) are constructor-enforced.

`DriverRiskEngine` (`DriverRiskEvaluator` port): **driver-only**, deterministic, stateless over
one `DriverState`. Road data (classes, TTC, corridor, tracks) cannot enter it — enforced by the
type signature. Policy (all thresholds EXPERIMENTAL):

| level | rule (first match upwards) |
|---|---|
| NORMAL | valid observation, no concerning temporal evidence (single blink / open-mouth frame / missing-face frame / brief head turn all stay here) |
| CAUTION | closure ≥ 0.7 s; PERCLOS ≥ 0.15; YAWN_LIKE; persistent lateral head-away; persistent face/eye visibility loss (monitoring evidence) |
| WARNING | closure ≥ 1.5 s (EXPERIMENTAL DEMO THRESHOLD); PERCLOS ≥ 0.30; persistent looking-down |
| CRITICAL | **sustained strong evidence only**: closure ≥ 3.0 s, or warning-level closure together with warning-level PERCLOS |

Informative reasons that never escalate alone: `EYES_CLOSED` (even for a blink at NORMAL),
`FACE_NOT_DETECTED`, `LOW_OBSERVATION_QUALITY`. Every non-NORMAL driver risk carries structured
reasons (record-enforced). Severity here is engineering severity — **not** "probability of
falling asleep".

## 12. CombinedRiskEngine (fusion)

Deterministic, conservative **rule matrix** — no score addition, no probabilities:

```
combined = max(road, driver),  EXCEPT  road ≥ WARNING ∧ driver ≥ WARNING ⇒ CRITICAL
```

Required rows hold exactly: N+N→N · C+N→C · W+N→W · Cr+N→Cr · N+C→C · N+W→W · N+Cr→Cr ·
**C+C→CAUTION (NOT CRITICAL)** · W+C→W · C+W→W · **W+W→CRITICAL** (Cr±anything→Cr as max).

Any combined level above `max(road, driver)` carries the explicit interaction reason
`COMBINED_HAZARD_ESCALATION` (snapshot-record-enforced). Co-occurring driver impairment
(≥ WARNING) with a road hazard (≥ CAUTION) additionally carries
`DRIVER_IMPAIRMENT_WITH_ROAD_HAZARD`. Component contributions carry `ROAD_HAZARD_PRESENT` /
`DRIVER_RISK_PRESENT`. Component levels and the whole component snapshots are preserved inside
`CombinedRiskSnapshot` — information is never erased.

## 13. Freshness policy (source-time only)

Road and driver camera clocks are independent; exact timestamp equality is never required. The
later of the two component source timestamps is the reference. `CombinedRiskConfig`
(EXPERIMENTAL budgets): `maximumRoadAgeSeconds`, `maximumDriverAgeSeconds`,
`maximumRoadDriverSkewSeconds`.

- Component older than its age budget ⇒ excluded ⇒ single-source output with `STALE_ROAD_STATE`
  / `STALE_DRIVER_STATE`. A stale WARNING/CRITICAL can never silently influence the fused level
  (its level remains visible as preserved information).
- Skew beyond budget ⇒ explicit single-source fallback (fresher component) with
  `ROAD_DRIVER_TIMESTAMP_SKEW` (+ the older side's STALE code).
- `System.nanoTime()` is never used for freshness — source timestamps only.

## 14. Degraded modes

| road | driver | output |
|---|---|---|
| valid WARNING | unavailable | `ROAD_ONLY` / WARNING (+ `DRIVER_UNAVAILABLE`) |
| unavailable | valid WARNING | `DRIVER_ONLY` / WARNING (+ `ROAD_UNAVAILABLE`) |
| unavailable | unavailable | `UNAVAILABLE` — **no level, not NORMAL** |
| available | stale | single-source with explicit STALE code |

One valid subsystem always remains usable when the other fails.

## 15. Demo mode

`CombinedRiskDemoSequenceTest` executes the required deterministic sequence through the
production chain (synthetic driver script → `DriverGuardProcessor` → analyzer →
`DriverRiskEngine`; genuine `RoadRiskSnapshot`s; `CombinedRiskProcessor` → `CombinedRiskEngine`):

`T0(1.0 s) normal/normal → T1(2.0) eyes close → T2(3.0) closure continues (CAUTION) →
T3(3.5) threshold crossed (driver WARNING) → T4(3.6) road hazard (CAUTION) → T5(3.8) road WARNING
→ T6(4.0) driver WARNING + road WARNING ⇒ COMBINED CRITICAL + COMBINED_HAZARD_ESCALATION`.

The driver remains within the WARNING band through T6 (closure 2.0 s < 3.0 s), the young-window
PERCLOS stays honestly unavailable, and the replayed script reproduces byte-identical snapshots
(determinism). No collision probability is computed anywhere.

Timing demo remains deliberate: `DriverGuardProcessor` runs on its own
`FramePipeline` + single-slot drop-oldest `LatestFrameQueue`, fully independent of
`RoadDetectionProcessor` — driver work can never block road processing. With a real Android
backend the wiring is symmetric to the road pipeline (same `FramePipeline`).

## 16. Verification status (honest)

- **Java tests: written (10 new test classes, §Tests); NOT EXECUTED** — the authoring sandbox has
  no JDK/JRE, no permission to install one, and no network access to fetch one. Nothing is
  claimed beyond that. All sources pass a full tree-sitter **syntax** parse (grammar-level only —
  this does NOT replace `javac`/JUnit).
- **Road regression (Stage 4.2): code untouched** — `risk/RoadRisk*`, `physical/*`,
  `tracking/*`, `trajectory/*` and the road pipeline were not modified (verified by diff); their
  tests remain as-is. Two mechanical compatibility updates were required outside Stage 4.3 code:
  one line in legacy `BaselineRiskEngine` (PERCLOS accessor after the `DriverState` refactor) and
  constructor-call updates in `RiskEngineContractTest`/`ConfigValidationTest`/
  `DriverStateInvariantsTest` (same semantic scenarios, same expectations).
- **Stage 2.5 real-model smoke test NOT REPEATED** — detector/preprocessing/decoder/NMS/model
  code was not modified.
- **Android build: NOT EXECUTED** (no SDK/JDK in the sandbox). **Real driver camera: NOT
  VERIFIED. Landmark backend (MediaPipe): NOT VERIFIED.** Concurrent front+rear camera support is
  hardware-dependent and is NOT assumed: a viable MVP demo path is real road camera + synthetic
  driver stream (or replay + synthetic), since analysis logic behind the provider is identical.
  Synthetic tests do NOT count as real camera verification.

## 17. Known limitations

- All thresholds are EXPERIMENTAL demo values, uncalibrated to any population, vehicle or camera.
- Eye classification uses `min(left,right)` openness (both-eyes-closed semantics); winks count as
  open — conservative for closure evidence.
- PARTIALLY_CLOSED does not contribute to PERCLOS numerator (documented choice).
- A young PERCLOS window is unavailable by design; early-trip coverage relies on the closure
  timers instead.
- Head-pose and mouth evidence are optional: backends that do not provide them simply produce
  UNKNOWN/UNAVAILABLE signals (flagged, never fabricated).
- Roll is recorded but not classified.
- Fusion trusts component snapshots; it does not re-validate their internal upstream lineage
  (that is each subsystem's contract).
- DriverGuard is NOT wired into the Android app UI in this stage (core + extension points only,
  to avoid claiming unverifiable device integration).

## 18. Not implemented (by design)

Medical diagnosis or clinically validated microsleep detection; face identity recognition;
driver identification; emotion recognition; age/gender inference; cloud video upload; ZholNet
server changes; any Stage 5+ work; lane detection / semantic segmentation / ego-motion
decomposition; new road detector training; C++/JNI.
