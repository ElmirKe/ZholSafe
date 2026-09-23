-- ZholNet initial schema (Stage 0 design; applied in Stage 5).
-- Idempotent where practical. Requires PostgreSQL 14+ with PostGIS 3+.

CREATE EXTENSION IF NOT EXISTS postgis;

-- ---------------------------------------------------------------------------
-- vehicle: device/vehicle identity. vehicle_id is CLIENT-DECLARED and untrusted until a
-- device-identity mechanism binds it to a credential (see server SecurityExtensionPoints).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS vehicle (
    id              BIGSERIAL PRIMARY KEY,
    vehicle_id      TEXT        NOT NULL UNIQUE,
    registered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ,
    last_location   geography(Point, 4326),
    trusted         BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ---------------------------------------------------------------------------
-- hazard_event: one compact hazard report. Mirrors the HazardEvent v1 contract.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS hazard_event (
    id                  BIGSERIAL PRIMARY KEY,
    event_id            TEXT        NOT NULL UNIQUE,          -- client UUID (dedupe key)
    vehicle_id          TEXT        NOT NULL,                 -- untrusted, see above
    hazard_type         TEXT        NOT NULL,                 -- PERSON|DOG|HORSE|COW|SHEEP|GOAT|CAMEL|UNKNOWN
    confidence          REAL        NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    risk                REAL        NOT NULL CHECK (risk BETWEEN 0 AND 1),
    location            geography(Point, 4326) NOT NULL,
    reported_at         TIMESTAMPTZ NOT NULL,                 -- client timestamp
    received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),   -- server timestamp
    status              TEXT        NOT NULL DEFAULT 'ACTIVE' -- ACTIVE|EXPIRED|CONFIRMED|DISMISSED
                        CHECK (status IN ('ACTIVE','EXPIRED','CONFIRMED','DISMISSED')),
    expires_at          TIMESTAMPTZ,                          -- set from server expiration policy
    confirmation_count  INTEGER     NOT NULL DEFAULT 0,       -- future multi-vehicle fusion
    cluster_id          BIGINT,                               -- future: groups reports of one physical hazard
    evidence_reference  TEXT,                                 -- optional, NOT used in MVP
    contract_version    SMALLINT    NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS hazard_event_location_gix ON hazard_event USING GIST (location);
CREATE INDEX IF NOT EXISTS hazard_event_status_idx   ON hazard_event (status, expires_at);
CREATE INDEX IF NOT EXISTS hazard_event_reported_idx ON hazard_event (reported_at);
CREATE INDEX IF NOT EXISTS hazard_event_type_idx     ON hazard_event (hazard_type);

-- ---------------------------------------------------------------------------
-- road_segment: optional reference geometry (imported from OSM or similar in a later stage).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS road_segment (
    id          BIGSERIAL PRIMARY KEY,
    external_id TEXT,
    name        TEXT,
    geom        geography(LineString, 4326) NOT NULL
);
CREATE INDEX IF NOT EXISTS road_segment_geom_gix ON road_segment USING GIST (geom);

-- ---------------------------------------------------------------------------
-- risk_zone: aggregated, time-decayed hazard density for the risk map (materialised in Stage 6).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS risk_zone (
    id              BIGSERIAL PRIMARY KEY,
    geom            geography(Polygon, 4326) NOT NULL,
    dominant_type   TEXT,
    event_count     INTEGER     NOT NULL DEFAULT 0,
    risk_score      REAL        NOT NULL DEFAULT 0 CHECK (risk_score BETWEEN 0 AND 1),
    window_start    TIMESTAMPTZ NOT NULL,
    window_end      TIMESTAMPTZ NOT NULL,
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS risk_zone_geom_gix ON risk_zone USING GIST (geom);
