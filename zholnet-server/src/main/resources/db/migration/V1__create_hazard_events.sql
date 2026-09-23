CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE hazard_event (
    id                          BIGSERIAL PRIMARY KEY,
    event_id                    VARCHAR(128) NOT NULL UNIQUE,
    anonymous_source_id         VARCHAR(128) NOT NULL,
    hazard_type                 VARCHAR(32) NOT NULL,
    severity                    VARCHAR(16) NOT NULL,
    severity_rank               SMALLINT NOT NULL CHECK (severity_rank BETWEEN 0 AND 3),
    confidence                  REAL NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    risk                        REAL NOT NULL CHECK (risk BETWEEN 0 AND 1),
    location                    geography(Point, 4326) NOT NULL,
    source_timestamp            TIMESTAMPTZ NOT NULL,
    received_at                 TIMESTAMPTZ NOT NULL,
    expires_at                  TIMESTAMPTZ NOT NULL,
    heading_degrees             REAL CHECK (heading_degrees IS NULL OR
                                    (heading_degrees >= 0 AND heading_degrees < 360)),
    approximate_distance_meters REAL CHECK (approximate_distance_meters IS NULL OR
                                    approximate_distance_meters > 0),
    ttc_seconds                 REAL CHECK (ttc_seconds IS NULL OR
                                    ttc_seconds >= 0),
    schema_version              INTEGER NOT NULL DEFAULT 1 CHECK (schema_version = 1),
    report_count                INTEGER NOT NULL DEFAULT 1 CHECK (report_count > 0),
    row_version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT hazard_event_expiry_after_receive CHECK (expires_at > received_at)
);

CREATE INDEX hazard_event_location_gix ON hazard_event USING GIST (location);
CREATE INDEX hazard_event_expiry_idx ON hazard_event (expires_at);
CREATE INDEX hazard_event_nearby_idx ON hazard_event (severity_rank, received_at DESC);
CREATE INDEX hazard_event_dedup_idx
    ON hazard_event (anonymous_source_id, hazard_type, received_at DESC);
