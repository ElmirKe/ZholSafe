package kz.zholsafe.risk;

import kz.zholsafe.config.CombinedRiskConfig;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Stage 4.3 deterministic road/driver risk fusion. No score addition, no probabilities — fusion
 * is an explicit conservative rule matrix over the two component {@link RiskLevel}s:
 *
 * <pre>
 *   combined = max(road, driver), EXCEPT road ≥ WARNING AND driver ≥ WARNING ⇒ CRITICAL
 * </pre>
 *
 * <p>In full (road × driver; symmetric in the two components):
 * <pre>
 *   NORMAL  + NORMAL   → NORMAL       CAUTION  + CAUTION  → CAUTION (NOT CRITICAL)
 *   CAUTION + NORMAL   → CAUTION      WARNING  + CAUTION  → WARNING
 *   WARNING + NORMAL   → WARNING      CAUTION  + WARNING  → WARNING
 *   CRITICAL+ NORMAL   → CRITICAL     WARNING  + WARNING  → CRITICAL (+ COMBINED_HAZARD_ESCALATION)
 *   NORMAL  + CAUTION  → CAUTION      CRITICAL + any      → CRITICAL (max component)
 *   NORMAL  + WARNING  → WARNING      any      + CRITICAL → CRITICAL (max component)
 *   NORMAL  + CRITICAL → CRITICAL
 * </pre>
 *
 * <h2>Freshness (source-time only)</h2>
 * Road and driver cameras are independent; exact timestamp equality is never required. The later
 * of the two source timestamps is the reference. A component older than its configured maximum
 * age — or a road/driver skew beyond the configured maximum — is excluded with an explicit
 * STALE/SKEW reason and the fresher component is preserved single-source. A stale WARNING or
 * CRITICAL can therefore never silently influence the current fused level.
 */
public final class CombinedRiskEngine implements CombinedRiskEvaluator {

    private final long maxRoadAgeNanos;
    private final long maxDriverAgeNanos;
    private final long maxSkewNanos;

    public CombinedRiskEngine(CombinedRiskConfig config) {
        Objects.requireNonNull(config, "config");
        this.maxRoadAgeNanos = nanos(config.maximumRoadAgeSeconds());
        this.maxDriverAgeNanos = nanos(config.maximumDriverAgeSeconds());
        this.maxSkewNanos = nanos(config.maximumRoadDriverSkewSeconds());
    }

    @Override
    public CombinedRiskSnapshot evaluate(RoadRiskSnapshot road, DriverRiskSnapshot driver) {
        Objects.requireNonNull(road, "road");
        Objects.requireNonNull(driver, "driver");
        long roadTs = road.frameTimestampNanos();
        long driverTs = driver.timestampNanos();
        long reference = Math.max(roadTs, driverTs);

        Set<CombinedRiskReason> reasons = EnumSet.noneOf(CombinedRiskReason.class);
        boolean roadOk = road.available();
        boolean driverOk = driver.available();
        Optional<RiskLevel> roadLevel = roadOk ? road.highestLevel() : Optional.empty();
        Optional<RiskLevel> driverLevel = driverOk ? driver.level() : Optional.empty();

        // ---- source availability ----
        if (!roadOk && !driverOk) {
            reasons.add(CombinedRiskReason.ROAD_UNAVAILABLE);
            reasons.add(CombinedRiskReason.DRIVER_UNAVAILABLE);
            return snapshot(reference, CombinedRiskSnapshot.Status.UNAVAILABLE, roadLevel, driverLevel,
                    Optional.empty(), roadTs, driverTs, reasons, road, driver);
        }
        if (!roadOk) {
            reasons.add(CombinedRiskReason.ROAD_UNAVAILABLE);
            addContribution(reasons, driverLevel.get(), false);
            return snapshot(reference, CombinedRiskSnapshot.Status.DRIVER_ONLY, roadLevel, driverLevel,
                    driverLevel, roadTs, driverTs, reasons, road, driver);
        }
        if (!driverOk) {
            reasons.add(CombinedRiskReason.DRIVER_UNAVAILABLE);
            addContribution(reasons, roadLevel.get(), true);
            return snapshot(reference, CombinedRiskSnapshot.Status.ROAD_ONLY, roadLevel, driverLevel,
                    roadLevel, roadTs, driverTs, reasons, road, driver);
        }

        // ---- skew: road/driver source times too far apart ⇒ explicit single-source fallback ----
        long skew = Math.abs(roadTs - driverTs);
        if (skew > maxSkewNanos) {
            boolean roadFresher = roadTs >= driverTs;
            reasons.add(CombinedRiskReason.ROAD_DRIVER_TIMESTAMP_SKEW);
            reasons.add(roadFresher
                    ? CombinedRiskReason.STALE_DRIVER_STATE : CombinedRiskReason.STALE_ROAD_STATE);
            if (roadFresher) {
                addContribution(reasons, roadLevel.get(), true);
                return snapshot(reference, CombinedRiskSnapshot.Status.ROAD_ONLY, roadLevel, driverLevel,
                        roadLevel, roadTs, driverTs, reasons, road, driver);
            }
            addContribution(reasons, driverLevel.get(), false);
            return snapshot(reference, CombinedRiskSnapshot.Status.DRIVER_ONLY, roadLevel, driverLevel,
                    driverLevel, roadTs, driverTs, reasons, road, driver);
        }

        // ---- staleness vs the reference (only the older component can exceed its age budget) ----
        if (reference - roadTs > maxRoadAgeNanos) {
            reasons.add(CombinedRiskReason.STALE_ROAD_STATE);
            addContribution(reasons, driverLevel.get(), false);
            return snapshot(reference, CombinedRiskSnapshot.Status.DRIVER_ONLY, roadLevel, driverLevel,
                    driverLevel, roadTs, driverTs, reasons, road, driver);
        }
        if (reference - driverTs > maxDriverAgeNanos) {
            reasons.add(CombinedRiskReason.STALE_DRIVER_STATE);
            addContribution(reasons, roadLevel.get(), true);
            return snapshot(reference, CombinedRiskSnapshot.Status.ROAD_ONLY, roadLevel, driverLevel,
                    roadLevel, roadTs, driverTs, reasons, road, driver);
        }

        // ---- fused: deterministic rule matrix ----
        RiskLevel combined = combine(roadLevel.get(), driverLevel.get());
        addContribution(reasons, roadLevel.get(), true);
        addContribution(reasons, driverLevel.get(), false);
        if (roadLevel.get().isAtLeast(RiskLevel.WARNING) && driverLevel.get().isAtLeast(RiskLevel.WARNING)) {
            reasons.add(CombinedRiskReason.COMBINED_HAZARD_ESCALATION);
        }
        if (roadLevel.get().isAtLeast(RiskLevel.CAUTION) && driverLevel.get().isAtLeast(RiskLevel.WARNING)) {
            reasons.add(CombinedRiskReason.DRIVER_IMPAIRMENT_WITH_ROAD_HAZARD);
        }
        return snapshot(reference, CombinedRiskSnapshot.Status.READY, roadLevel, driverLevel,
                Optional.of(combined), roadTs, driverTs, reasons, road, driver);
    }

    /** Deterministic rule matrix: max of the two, except WARNING+WARNING (or above) ⇒ CRITICAL. */
    public static RiskLevel combine(RiskLevel road, RiskLevel driver) {
        if (road.isAtLeast(RiskLevel.WARNING) && driver.isAtLeast(RiskLevel.WARNING)) {
            return RiskLevel.CRITICAL;
        }
        return road.ordinal() >= driver.ordinal() ? road : driver;
    }

    private static void addContribution(Set<CombinedRiskReason> reasons, RiskLevel level, boolean road) {
        if (level.isAtLeast(RiskLevel.CAUTION)) {
            reasons.add(road ? CombinedRiskReason.ROAD_HAZARD_PRESENT
                    : CombinedRiskReason.DRIVER_RISK_PRESENT);
        }
    }

    private static CombinedRiskSnapshot snapshot(long reference, CombinedRiskSnapshot.Status status,
            Optional<RiskLevel> roadLevel, Optional<RiskLevel> driverLevel,
            Optional<RiskLevel> combined, long roadTs, long driverTs,
            Set<CombinedRiskReason> reasons, RoadRiskSnapshot road, DriverRiskSnapshot driver) {
        return new CombinedRiskSnapshot(reference, status, roadLevel, driverLevel, combined,
                roadTs, driverTs, List.copyOf(reasons), road, driver);
    }

    private static long nanos(double seconds) {
        return Math.round(seconds * 1_000_000_000d);
    }
}
