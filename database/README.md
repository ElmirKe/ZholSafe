# database — PostgreSQL + PostGIS

Target production database for ZholNet Server. **Not wired to the server in Stage 0** (the
server has no datasource yet). This directory freezes the conceptual schema so that Stage 5 can
implement persistence without redesign.

## Entities

| Entity        | Purpose                                                               |
|---------------|-----------------------------------------------------------------------|
| `vehicle`     | Registered vehicle / device identity and last-seen state              |
| `hazard_event`| One reported hazard (compact event; no video). Geospatial point.       |
| `road_segment`| Optional reference geometry for "hazards near a road segment" queries |
| `risk_zone`   | Aggregated, time-decayed hazard density used by the risk map          |

## Query families the schema must support (Stage 5/6)

- active hazards within N km of a point (`ST_DWithin` on `hazard_event.location`)
- hazards near a road segment (`ST_DWithin` against `road_segment.geom`)
- repeated detections of the same class at the same location (cluster by `ST_SnapToGrid`)
- detections by hour of day / day of week (`reported_at`)
- historical hazard density per grid cell / segment → `risk_zone`

## Files

- `schema/001_init.sql` — initial DDL (idempotent, PostGIS extension, indexes). Reviewed, not yet
  applied by any automated migration; Stage 5 will move it into Flyway/Liquibase.

## Local development (Stage 5)

```bash
docker run --name zholnet-db -e POSTGRES_PASSWORD=dev -e POSTGRES_DB=zholnet -p 5432:5432 -d postgis/postgis:16-3.4
psql postgresql://postgres:dev@localhost:5432/zholnet -f database/schema/001_init.sql
```
(`dev` is a local-only development password; production credentials come from the environment.)
