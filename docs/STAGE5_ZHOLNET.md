# Stage 5 — ZholNet hazard server

> **LOCAL SAFETY DOES NOT DEPEND ON ZHOLNET.** RoadGuard, DriverGuard, tracking, physical
> diagnostics, risk fusion and local alerts must continue when the network, server, GPS or database
> is unavailable. ZholNet is supplementary metadata sharing, outside the local warning path.

Stage 5 is a production-shaped hackathon backend, not proof of production road-safety reliability,
availability, security or real-road effectiveness.

## Architecture

One Java 21 Spring Boot application receives compact anonymous hazard reports, validates and stores
them in PostgreSQL/PostGIS, answers bounded nearby queries, and broadcasts accepted or updated
events over STOMP. There are no queues, caches, microservices or Python/Node runtime services.

`vehicle → POST → validation/dedup service → JPA/PostGIS → REST nearby + STOMP broadcast`

REST nearby queries are authoritative for geographic filtering. The Stage 5 WebSocket topic is a
global compact-event broadcast; client-specific geographic subscriptions are deferred to Stage 6.

## Compact event and privacy model

Input retains the established contract-v1 names (`eventId`, `vehicleId`, `hazardType`,
`confidence`, `risk`, `latitude`, `longitude`, `timestamp`). `vehicleId` is treated only as an
untrusted, anonymous, preferably rotating source token; it is stored as `anonymous_source_id` and
never returned by public response/notification DTOs. This is data minimisation, **not a claim of
cryptographic anonymity**.

Optional server extensions are `schemaVersion` (currently 1), engineering `severity`,
`headingDegrees`, `approximateDistanceMeters`, and `ttcSeconds`. Severity is engineering priority,
not probability. A v1 client that omits severity gets a documented backward-compatible mapping from
its self-reported `risk` value. Server responses add `receivedTimestamp`, `expiresAt` and
`reportCount`.

Server vocabulary is `PERSON, DOG, HORSE, COW, SHEEP, GOAT, CAMEL, STOPPED_VEHICLE, OBSTACLE,
OTHER, UNKNOWN`. This is intentionally broader than the currently verified YOLO model vocabulary:
manual, future-model or other-sensor reports may use the additional network types. It does **not**
claim that the current detector recognizes them.

ZholNet does not accept or persist video, frames, images, audio, driver state, faces, eye data,
names, phone numbers, license plates, VINs or raw biometrics. Non-empty `evidenceReference` is
rejected in Stage 5.

## REST API

### `POST /api/v1/hazards`

Validates required fields, strict type vocabulary, finite/ranged numbers and coordinates. The
server records its own receive time and computes expiry from that time; the client source timestamp
is preserved only for diagnostics. A new event returns `201`; an idempotent/deduplicated report
returns `200`; a conflicting reused event ID returns `409`.

### `GET /api/v1/hazards/nearby`

Required query parameters: `latitude`, `longitude`, `radiusMeters`. Optional: RFC-3339 `since`,
`minimumSeverity`, repeated/comma-converted `eventTypes`, and `limit`. Radius is capped at 10 km and
results at 100 by demo defaults. Ordering is deterministic: distance ascending, server receive time
descending, then event ID ascending. `since` filters the trusted server receive time, not the client
source clock.

The repository uses PostGIS `geography(Point,4326)`, `ST_DWithin` and `ST_Distance`, so radius units
are metres. It never loads the full table or converts degrees to metres in Java.

## TTL and cleanup

`received_at` is server time. `expires_at = received_at + default TTL`; arbitrary client clocks do
not control retention. Every nearby query requires `expires_at > current server time`, independently
of cleanup. A scheduled job deletes expired rows periodically only to reclaim storage.

## Conservative MVP deduplication

An exact repeated `eventId` is idempotent only when source/type/coordinates match; conflicting reuse
is `409`. Spatial/time deduplication requires the same anonymous source token, same hazard type,
configured short time/radius gates, and matching heading and approximate-distance cues (10 degrees
and 5 metres). Without both cues, reports remain distinct. This intentionally prefers duplicates
over collapsing nearby animals in a herd. It is not object re-identification or distributed
clustering; cross-vehicle corroboration is deferred.

## WebSocket

STOMP handshake endpoint: `/ws/hazards`; broadcast destination: `/topic/hazards`. New and updated
events publish the same compact public metadata as REST, without source tokens, media, driver state
or biometrics. Geographic WebSocket filtering and per-user sessions are not implemented.

## Persistence and migration

Flyway migration `V1__create_hazard_events.sql` enables the PostGIS extension, creates the event
table, a GiST spatial index, expiry index, nearby-query index and conservative-dedup index. Hibernate
is configured with `ddl-auto=validate`; it is not the schema creator.

## Configuration

All values under `zholnet.hazards` in `application.yml` are externalizable: default TTL, cleanup
interval, dedup window/radius, maximum nearby radius/results and WebSocket endpoint/topic. These are
**EXPERIMENTAL / MVP CONFIGURATION**, not regulatory or validated safety thresholds. Database URL,
user and password come from environment variables.

## Run

Copy `.env.example` to `.env`, choose a local password, then:

```bash
docker compose up --build
curl http://localhost:8080/actuator/health
```

Actuator exposes only `health`; its database health contributor shows whether PostgreSQL is
reachable. `GET /api/v1/health` remains a lightweight service/contract liveness endpoint.

Without Docker, start a PostgreSQL 14+ database with PostGIS 3+, export `ZHOLNET_DB_URL`,
`ZHOLNET_DB_USER`, `ZHOLNET_DB_PASSWORD`, then run `mvn spring-boot:run` from `zholnet-server`.

## Test

```bash
cd zholnet-server
mvn test
mvn package
```

Unit, service, MVC, mapping and native-query contract tests run without a database. A real
PostgreSQL/PostGIS run is still required to verify Flyway, Hibernate geography binding, spatial
index/query execution and database health on the target environment. Docker was unavailable in the
Stage 5 authoring environment, so no PostGIS integration result is claimed.

## Known limitations / not implemented

- No production authentication, rate limiting, cryptographic device identity or abuse controls.
- No cross-vehicle clustering/corroboration and no geofiltered WebSocket subscriptions.
- No Android ZholNet client, second-car UI, interactive map, navigation or cloud deployment.
- Scheduled cleanup is single-application MVP housekeeping; correctness does not depend on it.
- No real-road, load, failover, penetration, Android/device or production-safety validation.
