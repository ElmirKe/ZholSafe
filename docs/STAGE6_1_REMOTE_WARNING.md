# Stage 6.1 — second-vehicle remote hazard warning

> **REMOTE ZHOLNET WARNINGS ARE ADVISORY METADATA.**
>
> **REMOTE EVENTS DO NOT ENTER `RoadRiskEngine` OR `CombinedRiskEngine`.**
>
> **LOCAL SAFETY CONTINUES WITHOUT INTERNET.**

Stage 6.1 adds the Vehicle B path while preserving the accepted offline-first local pipeline:

```text
LocationProvider -> bounded REST nearby polling -> RemoteHazardEvaluator
                 -> RemoteHazardSnapshot -> separate ZHOLNET advisory panel
```

Camera detections, tracking, trajectory/TTC, RoadRisk, DriverGuard and CombinedRisk do not depend
on this path. A remote event is never converted to a local detection or risk snapshot and its TTC,
if supplied by Vehicle A, is not exposed as Vehicle B's TTC.

## Polling and lifecycle

`RemoteHazardPoller` uses the Stage 6.0 `ZholNetClient` and
`GET /api/v1/hazards/nearby`. Experimental MVP defaults are a 2,000 m radius, a two-second
interval, a 1.5-second HTTP call timeout and at most 50 results. One bounded scheduler is created
while the activity is foregrounded. An atomic in-flight gate skips ticks instead of stacking
requests. `MainActivity.onStart()` starts polling and `onStop()` stops and interrupts its scheduler;
there is no background service or background location.

Missing or older-than-five-second vehicle location produces an explicit unavailable/stale state
without making an HTTP request. Network errors, timeouts and malformed responses produce a
network-unavailable remote snapshot and do not modify local risk.

## Relevance evaluation

`RemoteHazardEvaluator` applies deterministic rules using an injected UTC `Clock`:

- server expiry must be in the future, and `receivedTimestamp` age must be 0–2 minutes;
- Haversine distance (mean Earth radius 6,371,008.8 m) must be within the configured fetch radius;
- initial great-circle bearing is compared with Vehicle B's bearing using the smallest 0–180°
  angular difference, including correct 0/360 wraparound;
- the experimental ahead cone is ±60°. Events at least 120° behind are suppressed; lateral events
  remain low-strength advisories;
- without a vehicle heading, direction remains `UNKNOWN`; the UI says “nearby,” never “ahead,” and
  the advisory is capped at `CAUTION`;
- `WARNING` requires an elevated network severity, an ahead relation and distance at or below
  500 m. Ahead events through 1,200 m with at least `CAUTION` network severity become `CAUTION`.
  Other accepted events are `INFO`.

All valid Stage 5 hazard vocabulary values are eligible for the same relevance rules; Stage 6.1
does not invent evidence-based type weights. The type is retained for selection output and the
localized label, while network severity still cannot bypass freshness, distance or direction.

These values and levels are **EXPERIMENTAL MVP CONFIGURATION**, not collision probabilities,
regulatory thresholds or production road-safety claims. The geographic distance is Vehicle B to
the reported coordinate, not the Stage 4.1 camera-object distance.

## Multiple events and stability

The complete evaluated warning list is retained for later map work. Selection is deterministic:
advisory level, ahead relation, distance, freshness, network severity and stable `eventId` tie-break.
Thus the closest event does not automatically win over a more strongly justified warning.

To avoid display flicker, the selected advisory may be held for up to three seconds after a clean
poll no longer contains it. The hold never extends an event beyond its server expiry or the
client maximum advisory age.

## Self-event filtering and privacy

The Stage 5 public nearby response remains unchanged and does not expose the anonymous source
token. `RecentPublishedEventRegistry` instead remembers event IDs after a successful Stage 6.0
publication. It is thread-safe, metadata-only, capped at 256 IDs and expires entries after ten
minutes. It is memory-only, so exact self-filtering is not guaranteed across application restart.

No remote DTO or UI adds source tokens, device identifiers, driver data, video, images, faces,
eyes, PERCLOS, phone numbers, VINs or license plates.

## Android UI and notifications

The existing activity has a visually separate `ZHOLNET` panel with independent INFO/CAUTION/WARNING
colors. It displays a hazard label and deliberately rounded approximate distance (nearest 10 m
below 1 km or 0.1 km above). Unavailable network/GPS text explicitly says local safety continues.
No audio or vibration was added in Stage 6.1; the advisory is visual only.

## Deterministic two-vehicle demo and tests

Pure-JVM fixtures represent Vehicle A reporting a fresh `HORSE` warning near 43° N, 76° E and
Vehicle B roughly 420 m south, heading north. The evaluator produces a `WARNING` with “Впереди:
ЛОШАДЬ” and an approximate 420 m distance. The same event behind Vehicle B, an old event and a
recently published own event are rejected. This fixture does not fake success in production code.

Tests cover freshness/expiry, missing and stale location, known-distance Haversine calculation,
bearing and wraparound, absent heading, warning policy, self filtering and registry bounds/expiry,
priority and stable tie-break, display semantics, polling overlap/lifecycle/failure, malformed JSON,
and the existing Stage 6.0 publication contract.

## Known limitations

- REST polling is authoritative; live STOMP subscription remains deferred.
- Self-event memory is process-local and is lost on restart.
- No final map, route reasoning, background service, audio or vibration is implemented.
- A server report is unverified advisory metadata and may be inaccurate or malicious.
- Device UI/GPS behavior and real PostgreSQL/PostGIS end-to-end behavior require their respective
  runtime tests; JVM fixtures do not substitute for them.
- Stage 6.1 does not establish production road-safety reliability or certification.
