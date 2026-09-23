# Stage 4.2 — explainable ROAD hazard fusion (experimental)

**Engineering severity, NOT collision probability, certified safety level or alert authorization.**
Stage 4.2 publishes a diagnostic `RoadRiskSnapshot`, not audio/haptic warnings. No DriverGuard,
ZholNet, speed acquisition, lane detection, ego-motion decomposition or model change. Stage 4.1
calibration is absent by default, Android/device and real physical accuracy remain **unverified**.

## Architecture and legacy contracts

The Stage 0 `RiskEngine.evaluate(RiskInput)` / `BaselineRiskEngine` and `RiskAssessment`,
`RiskConfig`, `RiskLevel`, `RiskReason` are retained for source compatibility. Their aggregate
`RiskInput` has no Stage 3/4.0/4.1 snapshot status, source lineage or physical estimate quality,
and includes a driver state that Stage 4.2 is forbidden to use. Silently adapting it would lose
failure provenance and accidentally fuse driver signals. The **road-only** `RoadRiskEvaluator`
port and stateless `RoadRiskEngine` therefore reuse the existing `RiskLevel` and `RiskReason`
contracts but accept the three synchronized upstream snapshots directly. This is the Stage 4.2
pipeline path; the baseline is *not* called from it. No `VehicleContext` is needed or fabricated.

On the existing RoadDetectionProcessor processing thread:
`TrackingSnapshot + TrajectorySnapshot + PhysicalEstimationSnapshot -> RoadRiskSnapshot`.
A reader gets the latest immutable snapshot through an atomic reference. The engineering overlay
shows level, track ID, evidence quality and reason enum names and explicitly says **NOT
PROBABILITY / NO ALERT**; it does not show a percentage or call an optical TTC metric.

`RoadRiskSnapshot.READY` is only published when all three upstream snapshots are READY with
**identical source timestamp and upright dimensions**, and track identity/class/current box and
source observation lineage agree. Every currently observed `CONFIRMED` track gets one assessment.
LOST/tentative tracks are excluded from active risk, not turned into stale WARNING/CRITICAL.
READY with no objects is a successful empty road and has level NORMAL. Non-READY statuses
(`NOT_STARTED`, `TRACKING_UNAVAILABLE`, `TRAJECTORY_UNAVAILABLE`, `PHYSICAL_UNAVAILABLE`,
`INVALID_TIMESTAMP`, `ENGINE_ERROR`) have **no level** (`Optional.empty`), no track ID, and no
object risks. Thus failure is **not NORMAL**. The physical *processor* being READY without metric
range due to absent calibration is **not** a pipeline failure. This distinction is essential.

## Approximate normalized corridor and trajectory

`NormalizedDrivingCorridor(centerX,topY,topHalfWidth,bottomHalfWidth)` is a trapezoid in [0,1]
upright image fractions, widening toward the bottom. Defaults: centre `.50`, top y `.30`,
top half-width `.12`, bottom half-width `.40`. It is an approximate forward path region, **not
lane detection**. At bbox foot y `v=y2/H`, horizontal half-width is
`h(v)=topHalfWidth+(bottomHalfWidth-topHalfWidth)*(v-topY)/(1-topY)`.
`classify` uses normalized bbox horizontal interval `[x1/W,x2/W]` at the foot, and contact
`((x1+x2)/2W, y2/H)`: CENTRAL if contact lies in central `.40*h(v)` and y>=top;
INTERSECTING if bbox touches the corridor interval **including the boundary** and y>=top;
NEAR if horizontal gap <=`.06` (or foot within `.06` above top); otherwise OUTSIDE. Evaluation
rejects invalid/out-of-frame geometry. This is resolution-independent, not a road/lane mask.

From an **available, FIT_ACCEPTED** Stage 4.0 trajectory only, a `GROWING` bbox supplies
apparent approach (NOT metric closing). For non-intersecting objects with horizontal normalized
velocity `vx` at least `.025 frame widths/s` directed toward corridor centre, record
`MOVING_TOWARD_CORRIDOR`. Predict foot translation over **1 second**:
`xFuture = contactX + vx * horizon`, `yFuture = contactY + vy * horizon`.
If the extrapolated foot enters the trapezoid, record `PREDICTED_CORRIDOR_ENTRY`. This is a
bounded constant-image-velocity diagnostic, **not** a collision location, animal speed or lane
crossing guarantee. Insufficient trajectory is an explicit unavailable evidence item, **never
stationary**. SHRINKING supplies APPARENT_RECEDE, not numeric negative risk.

## Interpretable bounded components (all defaults experimental)

`RoadRiskConfig.defaults()` is the sole source of thresholds and engineering contribution
weights. `RiskComponents` records six bounded [0,1] terms; score is their sum capped to [0,1]:

| Component | Default maximum / policy | Meaning |
|---|---|---|
| corridor | CENTRAL .26; INTERSECTING .23; NEAR .15; OUTSIDE 0 | geometric relevance only |
| trajectory | GROWING .20, toward .12, predicted entry .16; combined cap .32 | accepted image-only motion, not physical speed |
| relativeClosing | medium-or-better rate with physical depth and >=.5 m/s: .18, >=3 m/s: .22 | source-timed camera-relative closing; not independent animal speed |
| ttc | metric <=2.5s .54; <=5s .38; <=9s .19; optical <=2.5s .30; <=4.5s .22; <=8s .12 | method-labelled selected TTC only; optical only when in/entering corridor and sustained growth |
| appearance | normalized bbox area >=.16 AND near/in/moving toward: .06 | never a metric distance |
| classModifier | HORSE/COW/CAMEL near/in/moving toward: .04; other classes incl PERSON/UNKNOWN: 0 | experimental, never overrides strong geometric evidence |

Metric TTC component is multiplied by `.80` when quality is MEDIUM, and by `.60` again if the
range method is `OBJECT_SIZE`; size-prior relative closing is also multiplied by `.60`.
LOW quality cannot contribute metric risk. HIGH does not get the
MEDIUM discount. A class-size prior alone cannot enable CRITICAL. Stage 4.1 `IMAGE_SCALE` is
always LOW and can contribute only through its *selected* optical diagnostic, never as a metric
TTC. A LOW-quality FOV-derived range is separately flagged as low-quality, not silently used as
metric risk. Missing range, rate, trajectory or TTC is not zero/infinity and is never used in numeric
arithmetic; unavailable evidence is a typed flag with NaN/NONE, not a number.

`RiskEvidence` carries typed source, engineering quality and either a finite value with
matching unit (metres optical-axis depth, relative m/s, normalized fraction, normalized
fractions/s, **log area per second**, or seconds) **or** NaN/NONE for a nonnumeric flag.
Physical failure flags also retain the exact Stage 4.1 `PhysicalReason` (e.g.
`NO_CALIBRATION`, `LOW_QUALITY`, `CONFLICTING_ESTIMATES`). `RiskReason` is an enum, not
free-form text. Each CAUTION/WARNING/CRITICAL object has >=1 reason. Score is **not** a calibrated
probability; UI intentionally displays the level rather than a spurious percent or precision.

Detection confidence below `.35` suppresses active scoring (NORMAL with LOW-quality evidence),
not a high-risk assertion; confirmed low-confidence tracks still receive an assessment.
`ScoreBands`: `NORMAL < .18`, `CAUTION >= .18`, `WARNING >= .43`, `CRITICAL >= .72`.
All cutoffs strictly increase. The raw engineering score alone does **not** authorize escalation:
WARNING also requires an intersecting object with approach/closing/short TTC, or a predicted
corridor entry with corroborating movement. CRITICAL additionally requires either an object
intersecting the corridor with **<=2.5s acceptable metric TTC, physical closing and ground-plane
(not size-prior-only) depth**, or an intersecting object with **accepted sustained growth and
<=2.5s LOW optical TTC**. The latter is deliberately an unvalidated, image-only diagnostic,
not a certified collision warning. Caps lower an otherwise unsupported level; an isolated class,
large box, close-but-receding outside object or optical TTC outside corridor cannot trigger
CRITICAL. `NORMAL` means insufficient hazard evidence, **not guaranteed safety**.

If the Stage 4.1 *selectedTtc* is `CONFLICTING_ESTIMATES`, neither original TTC contributes:
`TTC_CONFLICT` is reported and no scarier one is chosen. Other separately valid geometric or
relative closing evidence may still yield CAUTION/WARNING, but cannot unlock TTC-based CRITICAL.
All physical estimates retain Stage 4.1 provenance; the Risk Engine never manufactures metres,
speed, TTC or probability. A measured quality label is not a field accuracy certification.

## Frame-level aggregation and synthetic examples

The frame-level severity is **max per-object RiskLevel**, never sum or multi-hazard bonus.
`highestRiskTrackId` is absent for NORMAL, otherwise the highest engineering score within that level, then lower ID on exact score ties.
Two WARNING objects remain WARNING; CRITICAL+NORMAL yields CRITICAL linked to CRITICAL track.

- **No calibration, outside stable PERSON:** no physical depth/rate/TTC; corridor 0, trajectory 0,
  modifier 0 -> score 0 -> NORMAL. Not a safety guarantee.
- **No calibration, central sustained expansion:** corridor .26 + growth .20 + selected <=2.5s
  optical TTC .30 = .76 -> CRITICAL *engineering diagnostic* if the Stage 4.0/4.1 quality gates
  passed. No metres or metric TTC; no alert.
- **PERSON central, measured-input ground depth 12m, closing 8m/s, metric TTC 1.5s MEDIUM:**
  corridor .26 + rapid closing .22 + metric TTC .54*.80=.432 = .912 -> CRITICAL. Same evidence
  outside corridor gives lower level (WARNING eligibility requires geometric conflict/entry).
- **Close but receding outside:** metric TTC unavailable NOT_CLOSING; no rate/ttc contribution,
  outside corridor -> NORMAL. A close depth alone is not CRITICAL.
- **HORSE outside, stable:** class contribution 0 and corridor 0 -> NORMAL. Large livestock near
  corridor adds only .04, not a standalone CRITICAL condition.
- **Metric TTC 1s versus optical TTC 7s:** Stage 4.1 selected TTC is conflict/NaN. Risk engine
  contributes neither TTC, records the conflict; it may still use valid corridor/growth/rate but
  cannot cherry-pick 1s or 7s to trigger TTC-based CRITICAL.

## Verification and limitations

`RoadRiskEngineTest` covers central/outside/boundary, two resolutions, trapezoid widening,
toward/away/prediction/jitter, unavailable trajectory, metric in/out/receding/far-fast,
no-calibration stable/expanding/outside optical, conflict, HORSE/PERSON/DOG/UNKNOWN, multi-object
max/tie/WARNING+WARNING, upstream/dimension/time errors, LOST/tentative/empty, deterministic
immutability, quality/units/config validation. `RoadDetectionProcessorTest` covers same-thread
publishing and detector/trajectory/engine errors. See `docs/DEVELOPMENT.md` for **actually
executed** regressions and separate official Gradle/Android statuses. No Stage 2.5 31-image
inference rerun is required; inference and preprocessing are unchanged. This sandbox ran
**218 core test methods (0 failures)** with a temporary JDK 17/JUnit-compatible runner,
**5 synthetic image-frame smoke methods**, **9 Python tests** and the hazard-event contract check;
core and `PipelineController` compiled. Official Gradle/JUnit and Android build: **NOT EXECUTED**.

Limitations: no field calibration, measured road truth, device validation, accurate lane/corridor,
vehicle speed acquisition, ego-motion/pitch compensation, driver monitoring, active alert gating,
trained probability calibration or measured false-warning rate. All thresholds/weights are
EXPERIMENTAL and must be validated before production or driver-facing warnings.
