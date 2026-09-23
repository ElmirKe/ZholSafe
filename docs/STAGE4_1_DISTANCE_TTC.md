# Stage 4.1 — experimental distance and TTC foundation

**Status:** pure-Java estimation foundation and same-thread pipeline diagnostics. NOT a calibrated
safety feature, driver warning or Stage 4.2 Risk Engine input. Stage 2.5 desktop/JVM YOLO11n
inference remains separately verified; upstream weight provenance, Android inference/build/device
performance, road-plane accuracy and field calibration are **unverified**. Nothing here establishes
real-world range accuracy or collision predictiveness. Historical Stage 0–4.0 reports are not
retroactively changed.

## Configuration and coordinates

`CameraCalibration` is immutable and must be supplied explicitly to
`PhysicalEstimationProcessor(calibration, priors, config)`. The app's default processor uses
**null calibration and no priors**. `measured(...)` accepts focal lengths `(fx,fy)` and principal
point `(cx,cy)` in **upright source image pixels**, source image `(W,H)`, camera height `h` in
metres and camera pitch `theta` in radians (**positive down**). This identifies the inputs as
measured, but does **not** verify their accuracy. `fromVerticalFov(...)` assumes square pixels,
centred principal point and `fx=fy=H/[2 tan(verticalFov/2)]`; it is always marked
`FOV_DERIVED_APPROXIMATE`, never measured or safety-certified. Both require the *exact* upright
source dimensions used by tracking; changing crop, digital zoom, rotation, stabilisation, camera
mounting, height or resolution without an appropriate new calibration invalidates the estimate.
There is no automatic FOV/height/pitch from the device. A source change requires a new processor
or `reset()`; no GPS, IMU, ego-motion or road-slope compensation is present.

All distance values here are **camera optical-axis depth** `lambda` in metres (`camera z`), NOT
Euclidean/slant range, ground-path length, distance along the driving corridor, or horizontal
forward world distance. Thus the two optional methods at least describe the same depth quantity;
their models and systematic errors still differ. Pixel centre motion from Stage 4.0 is NEVER
reported in m/s. A signed range-rate is relative to the camera, not an independent animal speed;
no vehicle-speed sensor is required or used.

## Ground-plane ray intersection

For a full, non-clipped current bbox `(x1,y1,x2,y2)`, use its apparent foot/contact pixel
`u=(x1+x2)/2`, `v=y2`. The normalized camera ray is `(x,y,1)` with
`x=(u-cx)/fx`, `y=(v-cy)/fy` (image y increases down). With camera pitch `theta` and a *locally*
flat, horizontal road at downward distance `h` from camera origin:

```
downRay    = sin(theta) + y cos(theta)
forwardRay = cos(theta) - y sin(theta)
lambda     = h / downRay                  [m: camera optical-axis depth]
groundRight   = lambda * x                [m]
groundForward = lambda * forwardRay       [m]
```

Only finite, positive forward intersections with `downRay > horizonRayMargin` are accepted.
Default horizon margin `0.03` is a **dimensionless ray component**, not metres or degrees. Near-
horizon extrapolation, rays at/above the horizon, rays pointing behind the vehicle, nonfinite
intersection, degenerate/small boxes, edge-clipped feet or object heights, and frame/calibration
mismatch are rejected, NOT converted to infinite/zero range. `GroundIntersection` exposes
right/forward coordinates for geometry tests; `DistanceEstimate.meters` is `lambda`. The method
assumes a visible foot is *on the same plane as the camera road reference*. Road inclines,
crests, potholes, roadside elevation, a raised leg/occluded foot, bbox jitter, roll, and camera
pitch changes can invalidate the result. No quantified calibration uncertainty was supplied, so
**ground bounds are unavailable (NaN)** rather than a fictitious `[lambda,lambda]` interval.
Measured-input geometry is at most engineering `MEDIUM`; FOV-derived geometry is `LOW` and does
not enter metric rate/TTC under default quality gates.

## Optional object-height size prior

`ObjectSizePrior(class, HEIGHT, min, nominal, max, source)` is explicitly injected; UNKNOWN
cannot have one. The nominal pinhole approximation and interval, with apparent bbox height
`p=y2-y1` source pixels, are:

```
Z_nominal = fy * nominalHeight / p
Z_min     = fy * minimumHeight / p
Z_max     = fy * maximumHeight / p      [all camera optical-axis depths in metres]
```

This assumes an upright full-height object, locally constant apparent height, negligible
perspective/pitch effect over its body, accurate feet/head box and a meaningful object-specific
prior. It is unreliable for varied animal morphology/posture/orientation, juveniles, partial
occlusion, depth-dependent camera pitch, truncation and label errors. The interval represents
**only prior size range**, NOT statistical coverage or a bound on actual ranging error. Focal
intrinsics are still required. `ObjectSizePriors.experimentalUnvalidated()` contains optional,
broad engineering guesses for known classes, deliberately NOT activated in the Android app; these
were **not sourced from measured biological data** and are always `LOW` quality. No UNKNOWN
class-size ranging. An explicitly supplied `MEASURED_FOR_OBJECT` prior with relative width
`(max-min)/nominal <= 0.50` **and measured intrinsics** can be `MEDIUM` in this experimental
model; FOV-derived intrinsics always leave it `LOW`. Mere enum selection does
not prove that prior was truly measured/validated. Neither source is safety-certified.

`ConservativeDistanceEstimator` prefers ground geometry. If both methods are available, a ground
value outside the size interval expanded by `fusionRelativeTolerance=0.35` is
`CONFLICTING_ESTIMATES` → unavailable; if they agree it publishes the **unchanged** ground value
as `GROUND_PLANE_CROSS_CHECKED` with **no bounds**. No averaging, probability multiplication, or
claimed independence. When ground geometry fails, an explicitly supplied size prior can be a
method-labelled fallback with bounds. Without calibration neither method produces a range.

## Metric range-rate (not image speed)

For a currently observed, confirmed Stage 3 track, retain at most 8 lightweight
`(trackId, sourceTimestampNanos, opticalDepthMeters, quality, method)` samples. At most 128 active
track histories; capacity overflow rejects the frame rather than silently dropping another
track. The in-memory history never contains `Frame`, image, tensor, or video bytes. Only
`MEDIUM`-or-better distances enter the fit by default. Do **not** bridge missing/low-quality
ranges, lost/removed tracks, a successful empty road, detector or trajectory errors, recreated
IDs (track observation continuity), class/method changes, or source gaps >2 seconds. Reversed,
duplicate, inconsistent or overflowed times are rejected. The tracking/trajectory source
nanoseconds (Stage 3) are used; `System.nanoTime()` is **only** a processing telemetry clock.

With `n>=3` distances `d_i` and seconds `t_i=(timestamp_i-timestamp_0)/1e9`, require span
`>=0.5 s`. Centred ordinary least squares computes

```
b = sum[(t_i - mean(t))(d_i - mean(d))] / sum[(t_i - mean(t))^2]   [m/s]
rms = sqrt(sum[(d_i - mean(d) - b*(t_i - mean(t)))^2] / n)          [m]
closing = max(0, -b)                                               [m/s, only when b is available]
```

Reject nonfinite fits and RMS >2m (engineering thresholds). `b<0` means camera-relative
approach; `b>0` means receding. `closing=0` for an *available* non-closing fit is an observed
zero closing speed, NOT the unknown sentinel; an unavailable rate instead has **NaN** for rate,
closing speed and RMS, plus a reason. Camera pitch/height changes, road slope, lateral motion,
scale-ambiguous labels or inconsistent method errors can bias this relative rate.

## Two distinct TTCs, conflict policy

**Metric range TTC:** only with current `MEDIUM`-or-better physical depth and a current
`MEDIUM`-or-better same-track regression, matching source timestamps, positive closing speed
`>=0.5 m/s`, and `depth/closing <=30 s`:

```
TTC_metric = current opticalDepthMeters / positive closingSpeedMps  [seconds]
```

Constant relative optical-depth rate is assumed. It is NOT guaranteed collision: lateral
trajectory, ego-motion, relative heading, roadside objects and road geometry are not resolved.
Non-closing or insufficient/poor data is unavailable with explicit reason and NaN (not zero or
Infinity). This is not a vehicle speed or independent animal speed measurement.

**Optical-expansion TTC:** when Stage 4.0 has an *accepted fit* and **sustained** `GROWING`
(every image-area step passes its configured growth test), its log-area slope `g` is at least
`0.2 1/s`, log-area RMS <=0.12 and resulting TTC <=30s:

```
TTC_optical = 2 / g   [seconds; method IMAGE_SCALE, always LOW quality]
```

This is an **uncalibrated optical diagnostic**, not a physical distance, a range-rate, a metric
TTC or a warning. It assumes apparent area proportional to inverse square depth and a stable
object silhouette; pose/occlusion/turning/deformation can mimic growth. The two TTCs remain
separate in `PhysicalObjectEstimate.metricTtc` and `.imageScaleTtc`. Diagnostic `.selectedTtc`
chooses metric when both are available and agree within relative difference
`abs(metric-optical)/max(metric,optical) <=0.50`; if they disagree, the selected value is
`CONFLICTING_ESTIMATES` and NaN while the two method-labelled originals remain visible for
debugging. If only optical exists, selected retains `IMAGE_SCALE/LOW`. **No averaging** or Risk
Engine/`TrackedObject.estimatedTtc` update. The debug overlay prints each separately, with
"EXPERIMENTAL, NOT WARNINGS".

## Contracts, integration and failure behaviour

All new config, geometry and results are immutable Java 17 records/enums under
`kz.zholsafe.physical`, with pure-Java `PhysicalEstimationConfig`. Available numbers are finite,
positive where appropriate; unavailable values use NaN, a `NOT_AVAILABLE` method,
`UNAVAILABLE` quality and an explicit `PhysicalReason`. No Infinity or zero substitutes.
`DistanceEstimate` has optional size-prior bounds; `RangeRateEstimate` has signed m/s, positive
closing speed m/s, fit RMS metres, count and time; `TtcEstimate` has seconds and distinct metric
or image method; all carry source nanoseconds. EvidenceQuality is an **engineering tier**, not a
probability. `PhysicalEstimationSnapshot` READY + empty list means a successful empty road;
`TRACKING_UNAVAILABLE`, `TRAJECTORY_UNAVAILABLE`, `INVALID_TIMESTAMP`, `ESTIMATOR_ERROR` and
`NOT_STARTED` are distinct. LOST and tentative objects are explicitly unavailable.

`RoadDetectionProcessor` calls `PhysicalEstimationProcessor.analyze` on the existing thread
**after** tracking and image trajectory; latest physical snapshot uses an atomic reference for
readers. Failures do not turn into READY empty observations, and metric histories are cleared.
The existing Stage 0 `DistanceEstimator`/`TtcEstimator` scalar `Estimate` placeholders,
`TrackedObject.estimatedDistance`/`.estimatedTtc`, `VehicleContext` and Risk Engine inputs are
untouched. App default intentionally cannot output metric depth/rate/TTC. To run a controlled
experiment, inject a validated matching upright calibration (and optional justified per-object
prior) through the `RoadDetectionProcessor(..., PhysicalEstimationProcessor)` constructor;
no automatic runtime calibration UI or unverified activation path was added.

## Tests, not field validation

Synthetic geometry, resolution/pitch/horizon, prior intervals/conflicts, least-squares at
irregular and very large source timestamps, duplicate/reversed/stale times, lifecycle resets,
capacity/immutability, receding/stationary, scale-growth gating and upstream pipeline failure
are covered by `PhysicalEstimationTest` and `RoadDetectionProcessorTest`. These are **analytic
unit tests**, not calibrated road measurements. No Stage 2.5 31-image inference rerun (no
inference model/decoder changes). **203 core methods** and **5 image-frame smoke methods** passed
under a temporary JDK 17/JUnit-compatible shim (not official Gradle/JUnit), **9 Python tests**
and the contract check passed; core and `PipelineController` compiled. See
`docs/DEVELOPMENT.md` for actually executed test results,
build limitations and device validation still needed before any safety application.
