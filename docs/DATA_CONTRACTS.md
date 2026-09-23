# ZholSafe — Data Contracts (v1, frozen in Stage 0)

Java types live in `android-app/core/src/main/java/kz/zholsafe/...`. Server mirrors live in
`zholnet-server/src/main/java/kz/zholsafe/server/hazard/`. The wire schema for ZholNet is
`tests/contracts/hazard-event.v1.schema.json`. Changing a contract requires updating all three
plus this document, and bumping `HazardEvent.CONTRACT_VERSION` for wire changes.

General rules

1. Typed enums, never free-form strings, for classes, statuses, levels and reasons.
2. Anything approximate is carried as `Estimate` with an explicit availability flag and method.
3. Anything unknown is represented as unavailable (flag / sentinel documented below) — never
   defaulted to a value that could be mistaken for "safe".
4. Vehicle pipeline source timestamps are monotonic nanoseconds within one `CameraSource`;
   CameraX frame timestamps may use a camera/boottime domain, not necessarily the process
   `System.nanoTime()` domain. `System.nanoTime()` is processing telemetry only and must not be
   subtracted from source timestamps. Wall-clock `Instant` is used for `HazardEvent`.
5. **Numeric hardening (Stage 0.1).** Every required numeric field, and every optional field
   whose availability flag is `true`, must be *finite* (NaN and ±Infinity rejected by the record
   constructor via `kz.zholsafe.model.Contracts`). `Float.NaN` is accepted **only** as the
   documented "unavailable" sentinel of a flagged/optional field, and then the flag must be
   `false` — a numeric value with the flag off is treated as a fabricated measurement and
   rejected. The same rule applies on the server (`HazardEventValidator`).

### Sign semantics for distance and TTC (Stage 0.1 decision)

| Quantity | Factory | Sign rule | Rationale |
|----------|---------|-----------|-----------|
| Distance (m) | `Estimate.distance(v, method)` | `v >= 0` | Physical magnitude; a negative distance is an estimator bug, not data. |
| TTC (s) | `Estimate.ttc(v, method)` | `v >= 0` | ZholSafe defines TTC as time *until* a predicted collision. A mathematically negative TTC (not closing / closest approach already passed) carries no forward-looking collision information → estimators return `Estimate.unavailable()`. |

`TrackedObject` re-checks both (rejects negative available distance/TTC even if constructed via
the generic `Estimate.of`). `BaselineRiskEngine` uses `Estimate.isNonNegative()` before comparing
against `lowTtcSeconds` / `lowDistanceMeters`, so a negative TTC can never produce
`LOW_ESTIMATED_TTC`. This is a data-validity rule, not a new safety rule.

---

## ObjectClass (enum) — `kz.zholsafe.model.ObjectClass`

| Value    | label    | category | initial target (Stage 2) |
|----------|----------|----------|--------------------------|
| PERSON   | person   | HUMAN    | yes |
| DOG      | dog      | ANIMAL   | yes |
| HORSE    | horse    | ANIMAL   | yes |
| COW      | cow      | ANIMAL   | yes |
| SHEEP    | sheep    | ANIMAL   | yes |
| GOAT     | goat     | ANIMAL   | no (custom data) |
| CAMEL    | camel    | ANIMAL   | no (custom data) |
| UNKNOWN  | unknown  | UNKNOWN  | — (model class not known to the app) |

Adding a class: add the enum constant; append the label to `ai-training/configs/classes.yaml`
and `models/road/zholsafe-road-classes.txt`; add a `RiskReason.<X>_DETECTED` and a class weight
in `RiskConfig.defaults()`. `ai-training/tests/test_classes.py` checks registry consistency.

## BoundingBox / Point2D

`BoundingBox(x1, y1, x2, y2)` floats, frame pixel coordinates after rotation, `x1<=x2`,
`y1<=y2` (validated). Provides `width/height/area/center/iou`.

## Detection — `kz.zholsafe.model.Detection`

| Field          | Type        | Notes |
|----------------|-------------|-------|
| classId        | int         | Raw model index (diagnostics) |
| objectClass    | ObjectClass | Mapped via `LabelMap`; `UNKNOWN` if unmapped |
| confidence     | float [0,1] | validated |
| box            | BoundingBox | |
| timestampNanos | long        | source frame timestamp |

A `Detection` is *not* a risk.

## Estimate — `kz.zholsafe.model.Estimate`

`Estimate(available: boolean, value: double, method: EstimationMethod)`.
`Estimate.unavailable()` is the canonical missing value. `EstimationMethod`:
`NOT_AVAILABLE, MONOCULAR_UNCALIBRATED, MONOCULAR_CALIBRATED, STEREO, RADAR, LIDAR,
VEHICLE_SENSOR, SCALE_CHANGE`.

## TrackedObject — `kz.zholsafe.tracking.TrackedObject`

| Field              | Type              | Notes |
|--------------------|-------------------|-------|
| trackId            | int               | stable per track |
| objectClass        | ObjectClass       | |
| confidence         | float             | latest |
| box                | BoundingBox       | latest; `center()` derived |
| positionHistory    | List<Point2D>     | bounded (`TrackingConfig.historyLength`), oldest first |
| movement           | MovementClass     | `UNKNOWN, STATIONARY, APPROACHING_CORRIDOR, LEAVING_CORRIDOR, CLOSING, RECEDING, IN_CORRIDOR` |
| estimatedDistance  | Estimate (m)      | `distanceEstimated()` == `available()` |
| estimatedTtc       | Estimate (s)      | `ttcEstimated()` == `available()` |
| inDrivingCorridor  | boolean           | box intersects configured corridor |
| ageFrames          | int ≥ 0           | |
| timestampNanos     | long              | |

Invariants: `confidence` finite in [0,1]; `ageFrames >= 0`; available distance/TTC `>= 0`.

## DriverObservation (per frame) — `kz.zholsafe.driver.DriverObservation`

`faceDetected, eyesClosedAvailable, eyesClosed, eyeOpenness (NaN if none), headPose
(HeadPose.UNAVAILABLE if none), yawnAvailable, yawning, confidence, timestampNanos`.
`DriverObservation.noFace(ts)` is the canonical "nothing seen" value.

## DriverState (temporal) — `kz.zholsafe.driver.DriverState`

Invariants: `eyeClosureDurationMillis >= 0`; `confidence` finite in [0,1];
`perclosAvailable=true ⇒ perclos` finite in [0,1]; `perclosAvailable=false ⇒ perclos == NaN`.

| Field                    | Type     | Unavailable representation |
|--------------------------|----------|----------------------------|
| faceDetected             | boolean  | false |
| eyesClosed               | boolean  | false (only true when known closed) |
| eyeClosureDurationMillis | long ≥0  | 0 |
| perclosAvailable         | boolean  | false |
| perclos                  | float    | NaN when `perclosAvailable=false` |
| headPose                 | HeadPose | `HeadPose.UNAVAILABLE` (`available=false`) |
| yawningDetected          | boolean  | false |
| confidence               | float    | 0 |
| timestampNanos           | long     | |

`DriverState.unavailable(ts)` marks "DriverGuard produced nothing"; the Risk Engine then adds
`DRIVER_STATE_UNAVAILABLE` (informational) rather than assuming an alert driver.

## VehicleContext — `kz.zholsafe.risk.VehicleContext`

`speedAvailable, speedMps, nightMode`; `VehicleContext.UNKNOWN` when nothing is known.
`speedAvailable=true ⇒ speedMps` finite and `>= 0`; `speedAvailable=false ⇒ speedMps == NaN`.

## RiskInput — `kz.zholsafe.risk.RiskInput`

`tracks, driverState, vehicle, roadDetectorOk, frameWidth, frameHeight, timestampNanos`.
This is the complete input surface of the Risk Engine.

## RiskAssessment — `kz.zholsafe.risk.RiskAssessment`

| Field          | Type             | Notes |
|----------------|------------------|-------|
| driverRisk     | float [0,1]      | |
| roadRisk       | float [0,1]      | |
| collisionRisk  | float [0,1]      | |
| totalRisk      | float [0,1]      | |
| level          | RiskLevel        | `NORMAL < CAUTION < WARNING < CRITICAL` |
| reasons        | List<RiskReason> | **non-empty whenever level ≠ NORMAL** (constructor-enforced) |
| timestampNanos | long             | |

`RiskReason` codes (v1): driver — `DRIVER_FACE_NOT_DETECTED, DRIVER_EYES_CLOSED,
DRIVER_PROLONGED_EYE_CLOSURE, DRIVER_HIGH_PERCLOS, DRIVER_YAWNING, DRIVER_HEAD_POSE_DISTRACTED,
DRIVER_STATE_UNAVAILABLE`; presence — `PERSON_DETECTED, ANIMAL_DETECTED, HORSE_DETECTED,
COW_DETECTED, SHEEP_DETECTED, GOAT_DETECTED, CAMEL_DETECTED, DOG_DETECTED,
UNKNOWN_OBJECT_DETECTED, MULTIPLE_HAZARDS_DETECTED`; geometry — `OBJECT_IN_DRIVING_CORRIDOR,
OBJECT_APPROACHING_DRIVING_CORRIDOR, OBJECT_CLOSING, OBJECT_LARGE_IN_FRAME`; collision —
`LOW_ESTIMATED_DISTANCE, LOW_ESTIMATED_TTC`; context — `HIGH_VEHICLE_SPEED, NIGHT_CONDITIONS`;
system — `ROAD_DETECTOR_UNAVAILABLE, LOW_CONFIDENCE_ONLY`.

Example (from `RiskEngineContractTest.combinedSignalsEscalateToCritical`):
```
level = CRITICAL
reasons = [DRIVER_EYES_CLOSED, DRIVER_PROLONGED_EYE_CLOSURE, HORSE_DETECTED,
           OBJECT_IN_DRIVING_CORRIDOR, OBJECT_APPROACHING_DRIVING_CORRIDOR,
           LOW_ESTIMATED_TTC, LOW_ESTIMATED_DISTANCE]
```

## HazardEvent (ZholNet wire contract v1) — `kz.zholsafe.model.HazardEvent`

JSON schema: `tests/contracts/hazard-event.v1.schema.json`; example:
`tests/fixtures/hazard-event.v1.example.json`.

| Field             | JSON type | Constraint | Trust |
|-------------------|-----------|------------|-------|
| eventId           | string    | non-blank; client UUID | dedupe key |
| vehicleId         | string    | non-blank  | **untrusted / self-declared** |
| hazardType        | string    | exactly one of `PERSON, DOG, HORSE, COW, SHEEP, GOAT, CAMEL, UNKNOWN` (case-sensitive) | any other value is **rejected**; `UNKNOWN` is a legitimate canonical value, not a catch-all for malformed input |
| confidence        | number    | [0,1]      | self-reported |
| risk              | number    | [0,1]      | self-reported |
| latitude          | number    | [-90,90]   | |
| longitude         | number    | [-180,180] | |
| timestamp         | string    | RFC 3339 UTC | client clock |
| status            | string    | optional; if present exactly one of `ACTIVE, EXPIRED, CONFIRMED, DISMISSED` (client sends `ACTIVE`; absent ⇒ `ACTIVE`) | any other value is **rejected**; server-owned afterwards |
| evidenceReference | string/null | optional | reserved; **not used in MVP** |

**Forward-compatibility policy (v1):** the server validates enum values strictly against the v1
list. A newer client sending a class outside v1 is rejected (HTTP 400 in Stage 5) — that is the
intended signal to bump the contract version. `HazardType.fromWire()` is a lenient reader for
persisted data and is *not* used for request validation. All numeric fields must be finite.

Server-side lifecycle fields (not on the wire from the vehicle): `receivedAt, expiresAt,
confirmationCount, clusterId` — see `database/schema/001_init.sql`. These enable future
multi-vehicle confirmation (vehicle B corroborates vehicle A → `confirmationCount++`,
status `CONFIRMED`) without changing the v1 client payload.

## GeoPosition — `kz.zholsafe.model.GeoPosition`

`latitude, longitude, accuracyMeters (NaN if unknown), speedMps (NaN), bearingDeg (NaN),
timestampMillis` with `speedAvailable()/bearingAvailable()/accuracyAvailable()`.
Latitude/longitude finite and in range; optional fields are either `NaN` or finite (accuracy and
speed additionally `>= 0`); ±Infinity is rejected everywhere.

## Frame — `kz.zholsafe.ai.Frame`

`width, height, PixelFormat {RGB_888, RGBA_8888, NV21, YUV_420_888}, ByteBuffer data,
timestampNanos, CameraSource {ROAD, DRIVER, DEMO_FILE, TEST}`. Buffer ownership stays with the
producer; consumers must not retain it.

## Configuration objects — `kz.zholsafe.config.*`

`ZholSafeConfig(mode, detector, tracking, risk, network, driverGuardEnabled, zholNetEnabled)`.
All numeric thresholds/weights in the code base live here. Current defaults are **experimental
Stage 0 values** and are the calibration surface for later stages. Constructors validate:
probabilities/fractions/weights in [0,1], positive queue sizes and time windows, corridor
`left < right`, monotonic `caution <= warning <= critical`, non-negative TTC/distance thresholds.

## Stage 1 addition — `Frame` (in-process only, not a wire contract)

`kz.zholsafe.ai.Frame` gained `int rotationDegrees` (0/90/180/270, validated) between `data` and
`timestampNanos`, and `CameraSource.DEMO_SYNTHETIC`. Rationale: Stage 2 needs the rotation to
build an upright model input without Stage 1 paying a per-frame pixel rotation. `width`/`height`
remain the *stored* buffer dimensions; use `uprightWidth()/uprightHeight()` for the display
orientation. `Frame` is never serialised and is not part of the ZholNet v1 contract; the only
existing constructor call site (a core test) was updated.

### Stage 1.1 — `Frame.timestampNanos` semantics

`timestampNanos` is the **source/image timestamp**: `ImageProxy.getImageInfo().getTimestamp()`
for the LIVE road camera, the source's own monotonic clock for DEMO/TEST. Rules:

- It is not wall-clock time and not pipeline arrival time.
- Clock domain is per source; only differences between consecutive frames of the same
  `CameraSource` are meaningful (future tracking/TTC input).
- Processing-duration / FPS telemetry uses the pipeline's local monotonic clock. Never subtract
  values from the two domains unless their compatibility is proven for the device.

## Stage 2 — detection contracts (in-process)

- `Detection.box` (`BoundingBox`, floats) = **pixels in the upright source image** of the frame
  that produced it; `Detection.classId` = the MODEL's class index (diagnostic only),
  `Detection.objectClass` = canonical class obtained via `LabelMap`. `timestampNanos` = the
  frame's source/image timestamp. Confidence is the decoder's documented final score in [0,1].
- Label mapping rule: model index → model label string → `ObjectClass`. Never index → ordinal.
  Unsupported labels are dropped. Optional `labelAliases` in `model-spec.json` are label→label.
- `DetectionSnapshot.available=false` means DETECTION UNAVAILABLE; consumers must not treat it as
  zero detections.
- `model-spec.json` schema: see `models/README.md`; validated by `ModelSpec` (Java) and
  `ai-training/tests/test_model_specs.py` (Python) with identical rules.

## Stage 3 — RoadGuard tracking (in-process only)

Stage 3 now means **RoadGuard tracking**; DriverGuard implementation is deferred to Stage 4.3.
`ObjectTracker.update(detections, sourceTimestampNanos)` is called only after a successful detector
run; successful empty detections count as misses. Detector failure freezes tracker state and publishes
`TrackingSnapshot.Status.DETECTOR_UNAVAILABLE` (not READY with an empty list). Tracking rejects
nonpositive, duplicate or out-of-order source timestamps, mismatched detection timestamps and
nonfinite/degenerate boxes before mutation; the processor publishes `TRACKER_ERROR` and rethrows.

`TrackingSnapshot(frameTimestampNanos, uprightWidth, uprightHeight, status, tracks)` is immutable;
`READY` may contain an empty list and requires positive dimensions/time. Unavailable statuses
(`NOT_STARTED`, `DETECTOR_UNAVAILABLE`, `TRACKER_ERROR`) contain no tracks. Its `confirmedObjects()`
returns only currently observed CONFIRMED objects, never stale LOST boxes or tentative tracks.
`TrackView(object: TrackedObject, state: TENTATIVE|CONFIRMED|LOST, hits, missedFrames,
 history: List<TrackObservation>)` adds immutable lifecycle metadata without changing the existing
`TrackedObject` constructor. REMOVED tracks are discarded, not published. `TrackObservation` contains
source timestamp, upright pixel `BoundingBox`, and confidence only, oldest first, bounded by
`TrackingConfig.historyLength`. `TrackedObject.positionHistory` derives from these centres and is
also bounded. For LOST tracks the box/confidence/timestamp are the last *observation*; ageFrames
counts successful detector frames since creation. All Stage 3 physical estimates are explicitly
`Estimate.unavailable()`; movement is UNKNOWN and corridor false until later stages.

IDs increase within a tracker instance and are not reused on reset; a fresh processor starts a
new ID namespace. Default two-pass IoU/high/low thresholds, UNKNOWN policy and capacity limits:
`docs/STAGE3_TRACKING.md`. These are in-process additions, not changes to HazardEvent wire v1.

## Stage 4.0 — image-space trajectory (in-process only)

`TrajectoryConfig` holds experimental thresholds and recent-window sizes (see
`docs/STAGE4_0_TRAJECTORY.md`). `TrajectoryEstimator.estimate(TrackingSnapshot)` consumes Stage 3
observation metadata; the legacy `classify(TrackedObject,w,h)` is conservative UNKNOWN because the
old centre-only history lacks timestamped sample boxes. No changes to `TrackedObject`, `Estimate`,
RiskInput or HazardEvent wire v1.

`TrajectorySnapshot(frameTimestampNanos,uprightWidth,uprightHeight,status,upstreamStatus,objects)`
is immutable. READY requires a READY tracking snapshot with positive source time/dimensions; an
empty READY object list means successful processing with no tracks. NOT_STARTED,
TRACKING_UNAVAILABLE and ESTIMATOR_ERROR contain **no objects**. `upstreamStatus` records whether
an upstream detector/tracker failed; an estimator error leaves the tracking snapshot READY.

`ObjectTrajectory` is immutable: positive track ID, canonical `ObjectClass`, last matched source
nanosecond timestamp, upright pixel box and derived pixel centre, recent sample count (bounded),
per-track `TrajectoryStatus` (AVAILABLE, INSUFFICIENT_HISTORY, INVALID_TIMESTAMPS, LOW_QUALITY,
TRACK_NOT_CURRENT), `TrajectoryQuality`, `ImageMotion`, `ImageScaleTrend`, `ApproachState`, and fit
residuals/time span. Except for AVAILABLE, numeric motion/scale/span/residuals are unavailable
NaN with explicit flags, direction and scale UNCERTAIN. LOST/tentative/stale tracks have
TRACK_NOT_CURRENT, never AVAILABLE image motion. Fit residuals are diagnostics, not calibrated
confidence or safety values.

Image-motion units: normalized X = frame-width fractions/second, normalized Y = frame-height
fractions/second (+Y down), normalized magnitude = hypot(X,Y) fractions/second, **not m/s**.
Scale uses current normalized box area (dimensionless) and linear fit slope of log(area) against
source seconds (dimensionless per second); APPROACHING/RECEDING describe apparent box-scale trends,
**not physical closing or TTC**. This is the historical Stage 4.0 contract; Stage 4.1 adds
separate explicitly method-labelled experimental depth/rate/TTC contracts below, without
changing Stage 4.0 `ImageScaleTrend` or enabling legacy Risk Engine estimates.


## Stage 4.1 — experimental physical estimation contracts (in-process, not wire v1)

The Stage 0 `Estimate`/`EstimationMethod`, `TrackedObject.estimatedDistance`,
`TrackedObject.estimatedTtc`, `RiskInput`, `VehicleContext`, and `HazardEvent` wire v1 are
unchanged/unavailable. Stage 4.1 uses its own `kz.zholsafe.physical` contracts; no value is
promoted into a risk input or a warning. Details and equations: `docs/STAGE4_1_DISTANCE_TTC.md`.

| Contract | Units/meaning | Availability/provenance |
|---|---|---|
| `CameraCalibration` | Upright source W,H; fx,fy,cx,cy source px; h metres; pitch radians positive down | Strict finite/positive intrinsics and height, bounded pitch, `MEASURED_INTRINSICS` vs `FOV_DERIVED_APPROXIMATE`. No implicit instance. Exact frame dimension match. |
| `ObjectSizePrior` | Explicit class-specific apparent HEIGHT min/nominal/max metres | `EXPERIMENTAL_UNVALIDATED` or explicitly supplied `MEASURED_FOR_OBJECT`. UNKNOWN rejected; shipped guesses opt-in only, never calibrated accuracy. |
| `DistanceEstimate` | Camera optical-axis depth metres, not Euclidean/slant/road-path range | `GROUND_PLANE`, `OBJECT_SIZE`, `GROUND_PLANE_CROSS_CHECKED` (unchanged ground value); optional size-prior min/max bounds only. No quantified ground uncertainty: bounds flag false, bounds NaN. |
| `RangeRateEstimate` | Signed relative optical-depth m/s; `closingSpeedMps=max(0,-rate)` for **available** fit; RMS metres; sample count | `METRIC_REGRESSION` only on same-track bounded physical depth samples, Stage 3 source time. Not animal/vehicle speed. No bbox pixel rate as m/s. |
| `TtcEstimate` | seconds | `METRIC_RANGE` (depth/closing speed) vs `IMAGE_SCALE` (2/log-area-rate, LOW uncalibrated optical diagnostic) distinct; never blind average. |
| `PhysicalObjectEstimate` | One track per source frame | state, id/class/source nanoseconds, distance/rate, independent metric & optical TTC, method-labelled selected diagnostic; LOST/tentative unavailable. |
| `PhysicalEstimationSnapshot` | source nanoseconds; upright W,H; immutable object list | READY empty = successful empty road. NOT_STARTED / TRACKING_UNAVAILABLE / TRAJECTORY_UNAVAILABLE / INVALID_TIMESTAMP / ESTIMATOR_ERROR contain no objects and carry upstream statuses. |

Every available physical scalar is finite and positive when its quantity requires it; a signed
range-rate may be zero for an observed stationary fit. Every unavailable numeric quantity is
**NaN**, never zero/Infinity, with an explicit `PhysicalReason`, `NOT_AVAILABLE` method and
`UNAVAILABLE` engineering quality. Available source timestamps are positive; unavailable
startup timestamp may be 0. `EvidenceQuality` (`LOW`, `MEDIUM`, `HIGH`) is **not a calibrated
probability**; method quality and sample gates are configurable in `PhysicalEstimationConfig`
and experimental. All snapshot lists and prior registries are copied, histories are numeric,
per-track, bounded and cleared on failure/removal/source reset. FOV-derived and opt-in unvalidated
prior distances stay `LOW` and cannot yield metric TTC under default `MEDIUM` gates.


## Stage 4.2 — road-only engineering risk contracts (in-process, not probability)

`RoadRiskEvaluator.evaluate(tracking,trajectory,physical)` consumes synchronized immutable
Stage 3/4.0/4.1 snapshots. `RoadRiskEngine` is stateless and leaves the legacy
`RiskEngine.evaluate(RiskInput)`, `BaselineRiskEngine`, `RiskAssessment`, `RiskConfig`,
`TrackedObject`, `VehicleContext`, DriverState and hazard-event wire v1 unchanged. It reuses
existing `RiskLevel` (NORMAL<CAUTION<WARNING<CRITICAL) and extends `RiskReason` enum with
road-specific reason codes. No model confidence or engineering score is a collision probability.

| Contract | Meaning/invariants |
|---|---|
| `NormalizedDrivingCorridor` | Pure normalized upright trapezoid; valid centre/top/half-widths; `CorridorRelation.OUTSIDE/NEAR/INTERSECTING/CENTRAL`. Inclusive bbox-touch boundary; no lane claim. |
| `RoadRiskConfig` | Immutable finite score bands, strictly increasing TTC bands, bounded named contributions, positive finite prediction horizon/motion gates, measured-quality minimum. All defaults EXPERIMENTAL. |
| `RiskEvidence` | Enum type/source/quality; numeric evidence requires finite value and type-matched unit; nonnumeric flags carry NaN/NONE; physical failure flags include `PhysicalReason`. No mixed metres, seconds, fractions or probability claims. |
| `RiskComponents` | Explicit bounded corridor/trajectory/relativeClosing/ttc/appearance/classModifier engineering terms; capped sum [0,1], NOT probability. |
| `ObjectRiskAssessment` | Current confirmed track ID/class/source timestamp; RiskLevel, bounded engineeringScore, quality, named components, copied evidence/reasons. Non-NORMAL requires structured reasons. |
| `RoadRiskSnapshot` | source nanoseconds/upright dimensions/upstream statuses; READY list copied, global level = max object level, highest-score then lowest-ID tie; NORMAL has no hazard track. Non-READY list empty with `highestLevel=Optional.empty` and no track ID. |

A successful empty road is READY/empty/NORMAL. Tracking, trajectory, physical-processor,
lineage and engine errors are distinct non-READY statuses and **NOT NORMAL**. A READY physical
snapshot with per-object `NO_CALIBRATION` is operational: the risk evaluator may use
LOW-quality image evidence but must not invent metric values. LOST/tentative tracks are excluded
from active object risk. Each per-object score is a deterministic engineering severity indicator,
not a statistically calibrated probability or a driver-facing warning. See
`docs/STAGE4_2_RISK_ENGINE.md` for formula, defaults, policy gates and synthetic examples.
