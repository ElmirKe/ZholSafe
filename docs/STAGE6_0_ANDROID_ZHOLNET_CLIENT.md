# Stage 6.0 — Android ZholNet client, location and hazard bridge

> **LOCAL SAFETY DOES NOT DEPEND ON INTERNET OR ZHOLNET.** Detector, tracking, trajectory,
> physical diagnostics, RoadRiskEngine, DriverGuard, CombinedRiskEngine and local risk state run
> before and independently of this layer.

> **REMOTE HAZARD EVENTS ARE ADVISORY METADATA, NOT VERIFIED COLLISION PREDICTIONS.** Stage 6.0 is
> an experimental hackathon integration, not production road-safety evidence or certification.

## Architecture and thread boundary

`RoadRiskSnapshot + TrackingSnapshot + PhysicalEstimationSnapshot + LocationFix`
`→ HazardEventBridge → bounded QueuedHazardPublisher → asynchronous OkHttp → ZholNet`

The existing risk engines contain no network calls. Android invokes the coordinator only after an
immutable local risk snapshot exists. Mapping/enqueue work is constant-time; OkHttp callbacks and
the single retry scheduler run off the camera/inference thread. A server failure cannot alter or
replace the local snapshot.

## Location

`LocationProvider` is a non-blocking latest-fix interface. `LocationFix` validates WGS84 coordinates,
accuracy, optional bearing/speed, wall time and Android monotonic elapsed time. The bridge compares
the observation and fix in the monotonic domain and rejects missing, future or older-than-configured
fixes. Wall-clock event time comes from an explicit `SourceTimeMapper` anchor; it is not confused
with `elapsedRealtimeNanos`.

`AndroidLocationProvider` uses foreground fused location and returns no fix when fine-location
permission is absent. `SyntheticLocationProvider` supports deterministic JVM/demo operation.
Permission denial disables only network publication.

The main manifest declares only `CAMERA`, `ACCESS_FINE_LOCATION` and `INTERNET`; no coarse or
background location permission is requested. Debug builds alone allow cleartext HTTP for local
demo servers. Release builds retain Android's default cleartext prohibition and should use HTTPS.

## Anonymous source token and privacy

Android creates `anon-<random UUID>` and stores it in app-private SharedPreferences file
`zholnet_privacy` solely for conservative server deduplication. `rotate()` replaces it. The token is
not a name, phone number, advertising ID, IMEI, VIN or plate, and is not cryptographic anonymity.

The wire DTO contains compact hazard metadata only. There are no video/frame/image/audio, driver,
face, eye, PERCLOS, identity or biometric fields. `evidenceReference` is emitted as JSON null to
match the Stage 5 schema.

## Bridge and experimental publication policy

Default eligibility is WARNING or CRITICAL. NORMAL and CAUTION are not published. The bridge
selects the already-computed highest-risk track, requires current confirmed tracking evidence and
a fresh location, and suppresses repeated reports for the same track for 10 seconds. The suppression
map is capped at 256 tracks. Defaults are **EXPERIMENTAL MVP CONFIGURATION**, not safety thresholds.

Local `RiskLevel` maps name-for-name to network severity. Detector confidence remains detector
confidence. The road-risk engineering score remains an engineering severity signal, **not an
accident probability**. Event IDs are fresh UUIDs and contain no hardware identifier.

Current verified YOLO11n classes map as `PERSON`, `DOG`, `HORSE`, `COW`, `SHEEP`. `CAMEL`, `GOAT`
and unknown/unmapped output are explicitly rejected by the detector bridge. The server vocabulary
remains broader for future/manual/other sensors; that does not claim current detector support.

## HTTP and nearby foundation

`OkHttpZholNetClient` uses asynchronous `enqueue()` for:

- `POST /api/v1/hazards`
- `GET /api/v1/hazards/nearby`

Results explicitly distinguish success, HTTP error, transport error and invalid response. Nearby
responses are exposed through the typed `NearbyHazardProvider`; Stage 6.0 adds no remote-warning UI.

Configure the ZholNet base URL for the environment:

- Desktop JVM: `http://localhost:8080`
- Android emulator: `http://10.0.2.2:8080`
- Physical phone: `http://<SERVER_LAN_IP>:8080` (same LAN; never commit a personal IP)

For the Android build, set Gradle property `zholnetBaseUrl` or environment variable
`ZHOLNET_BASE_URL`; the emulator URL is the demo default.

## Retry, queue and backpressure

The default metadata-only queue capacity is 32, maximum attempts is 3, initial backoff is one
second, and maximum event age is two minutes. Only one event is dispatched at a time. There is no
retry sleep, frame retention, media storage, unbounded executor, or infinite retry. Stale events are
rejected/dropped. On a full queue, the oldest waiting item is dropped; when capacity is one and its
only item is in flight, the new item is rejected. All outcomes are explicit.

## WebSocket status

Stage 6.0 provides the typed `HazardSubscription` extension point only. Full STOMP subscription is
deferred to Stage 6.1 to avoid adding another protocol stack. REST nearby polling is the implemented
authoritative foundation.

## Demo and tests

DEMO mode attaches a deterministic Almaty-area synthetic location to the existing synthetic local
pipeline. JVM fixtures create a deterministic track 42 HORSE/WARNING observation with known 0.82
confidence, pass it through the real bridge and test the HTTP transport with MockWebServer. No
production code fakes a server response.

The client-generated JSON is validated by the repository's actual Stage 5 JSON Schema using a JSON
Schema 2020-12 validator. Android debug assembly and JVM tests are build-time verification only;
no physical device, GPS hardware, camera, real road or production reliability validation is claimed.

## Known limitations

- No final second-car UI, audio/vibration warning, map or navigation.
- No live STOMP implementation, background location/tracking, authentication or telemetry.
- The UI currently samples the latest immutable snapshot at its bounded 500 ms refresh cadence.
- Real device lifecycle, permission UX, GPS accuracy and LAN behavior remain unverified.
- Docker/PostGIS integration remains unexecuted where Docker is unavailable.
