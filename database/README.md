# database — PostgreSQL + PostGIS

PostgreSQL/PostGIS database for the Stage 5 ZholNet server. The server applies its versioned
Flyway migration and uses Hibernate only to validate the resulting schema.

## Entities

| Entity        | Purpose                                                               |
|---------------|-----------------------------------------------------------------------|
| `hazard_event`| One reported hazard (compact event; no video). Geospatial point.       |

## Stage 5 query families

- active hazards within N km of a point (`ST_DWithin` on `hazard_event.location`)
- conservative same-source/type/time/radius duplicate candidates
- expiry cleanup by server-owned `expires_at`

## Files

- `schema/001_init.sql` — readable reference copy.
- `../zholnet-server/src/main/resources/db/migration/V1__create_hazard_events.sql` — executable
  Flyway migration and authoritative schema.

## Local development (Stage 5)

```bash
cp .env.example .env
docker compose up --build
```

Credentials are environment-driven. Do not commit the generated `.env` file.
