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
4. Timestamps inside the vehicle pipeline are monotonic nanoseconds (`System.nanoTime` domain);
   wall-clock `Instant` is used only where the value leaves the device (`HazardEvent`).

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
| ageFrames          | int               | |
| timestampNanos     | long              | |

## DriverObservation (per frame) — `kz.zholsafe.driver.DriverObservation`

`faceDetected, eyesClosedAvailable, eyesClosed, eyeOpenness (NaN if none), headPose
(HeadPose.UNAVAILABLE if none), yawnAvailable, yawning, confidence, timestampNanos`.
`DriverObservation.noFace(ts)` is the canonical "nothing seen" value.

## DriverState (temporal) — `kz.zholsafe.driver.DriverState`

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
| hazardType        | string    | enum of `ObjectClass` names | unknown → `UNKNOWN` on server |
| confidence        | number    | [0,1]      | self-reported |
| risk              | number    | [0,1]      | self-reported |
| latitude          | number    | [-90,90]   | |
| longitude         | number    | [-180,180] | |
| timestamp         | string    | RFC 3339 UTC | client clock |
| status            | string    | `ACTIVE, EXPIRED, CONFIRMED, DISMISSED` (client sends `ACTIVE`) | server-owned afterwards |
| evidenceReference | string/null | optional | reserved; **not used in MVP** |

Server-side lifecycle fields (not on the wire from the vehicle): `receivedAt, expiresAt,
confirmationCount, clusterId` — see `database/schema/001_init.sql`. These enable future
multi-vehicle confirmation (vehicle B corroborates vehicle A → `confirmationCount++`,
status `CONFIRMED`) without changing the v1 client payload.

## GeoPosition — `kz.zholsafe.model.GeoPosition`

`latitude, longitude, accuracyMeters (NaN if unknown), speedMps (NaN), bearingDeg (NaN),
timestampMillis` with `speedAvailable()/bearingAvailable()/accuracyAvailable()`.

## Frame — `kz.zholsafe.ai.Frame`

`width, height, PixelFormat {RGB_888, RGBA_8888, NV21, YUV_420_888}, ByteBuffer data,
timestampNanos, CameraSource {ROAD, DRIVER, DEMO_FILE, TEST}`. Buffer ownership stays with the
producer; consumers must not retain it.

## Configuration objects — `kz.zholsafe.config.*`

`ZholSafeConfig(mode, detector, tracking, risk, network, driverGuardEnabled, zholNetEnabled)`.
All numeric thresholds/weights in the code base live here. Current defaults are **experimental
Stage 0 values** and are the calibration surface for later stages.
