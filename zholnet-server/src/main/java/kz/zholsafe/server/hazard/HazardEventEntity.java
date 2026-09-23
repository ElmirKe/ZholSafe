package kz.zholsafe.server.hazard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.locationtech.jts.geom.Point;

import java.time.Instant;

/** Persistence-only representation of compact hazard metadata. No media or biometrics. */
@Entity
@Table(name = "hazard_event")
public class HazardEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 128)
    private String eventId;

    @Column(name = "anonymous_source_id", nullable = false, length = 128)
    private String anonymousSourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "hazard_type", nullable = false, length = 32)
    private HazardType hazardType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private HazardSeverity severity;

    @Column(name = "severity_rank", nullable = false)
    private short severityRank;

    @Column(nullable = false)
    private float confidence;

    @Column(nullable = false)
    private float risk;

    @Column(columnDefinition = "geography(Point,4326)", nullable = false)
    private Point location;

    @Column(name = "source_timestamp", nullable = false)
    private Instant sourceTimestamp;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "heading_degrees")
    private Float headingDegrees;

    @Column(name = "approximate_distance_meters")
    private Float approximateDistanceMeters;

    @Column(name = "ttc_seconds")
    private Float ttcSeconds;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "report_count", nullable = false)
    private int reportCount;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected HazardEventEntity() { }

    public HazardEventEntity(String eventId, String anonymousSourceId, HazardType hazardType,
                             HazardSeverity severity, float confidence, float risk, Point location,
                             Instant sourceTimestamp, Instant receivedAt, Instant expiresAt,
                             Float headingDegrees, Float approximateDistanceMeters, Float ttcSeconds,
                             int schemaVersion) {
        this.eventId = eventId;
        this.anonymousSourceId = anonymousSourceId;
        this.hazardType = hazardType;
        this.severity = severity;
        this.severityRank = severity.rank();
        this.confidence = confidence;
        this.risk = risk;
        this.location = location;
        this.sourceTimestamp = sourceTimestamp;
        this.receivedAt = receivedAt;
        this.expiresAt = expiresAt;
        this.headingDegrees = headingDegrees;
        this.approximateDistanceMeters = approximateDistanceMeters;
        this.ttcSeconds = ttcSeconds;
        this.schemaVersion = schemaVersion;
        this.reportCount = 1;
    }

    public void mergeReport(float newConfidence, float newRisk, HazardSeverity newSeverity,
                            Instant newSourceTimestamp, Instant newReceivedAt, Instant newExpiresAt,
                            Float newHeading, Float newDistance, Float newTtc) {
        confidence = Math.max(confidence, newConfidence);
        risk = Math.max(risk, newRisk);
        if (newSeverity.rank() > severityRank) {
            severity = newSeverity;
            severityRank = newSeverity.rank();
        }
        sourceTimestamp = newSourceTimestamp;
        receivedAt = newReceivedAt;
        expiresAt = newExpiresAt;
        headingDegrees = newHeading;
        approximateDistanceMeters = newDistance;
        ttcSeconds = newTtc;
        reportCount++;
    }

    public Long id() { return id; }
    public String eventId() { return eventId; }
    public String anonymousSourceId() { return anonymousSourceId; }
    public HazardType hazardType() { return hazardType; }
    public HazardSeverity severity() { return severity; }
    public short severityRank() { return severityRank; }
    public float confidence() { return confidence; }
    public float risk() { return risk; }
    public Point location() { return location; }
    public Instant sourceTimestamp() { return sourceTimestamp; }
    public Instant receivedAt() { return receivedAt; }
    public Instant expiresAt() { return expiresAt; }
    public Float headingDegrees() { return headingDegrees; }
    public Float approximateDistanceMeters() { return approximateDistanceMeters; }
    public Float ttcSeconds() { return ttcSeconds; }
    public int schemaVersion() { return schemaVersion; }
    public int reportCount() { return reportCount; }
}
