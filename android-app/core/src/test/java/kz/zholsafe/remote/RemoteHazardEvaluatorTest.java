package kz.zholsafe.remote;

import kz.zholsafe.location.LocationFix;
import kz.zholsafe.location.LocationQuality;
import kz.zholsafe.network.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

class RemoteHazardEvaluatorTest {
    private static final Instant NOW = Instant.parse("2026-09-23T12:01:00Z");
    private static final long ELAPSED = 100_000_000_000L;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RemoteHazardConfig config = RemoteHazardConfig.defaults();
    private final RecentPublishedEventRegistry registry = RecentPublishedEventRegistry.defaults(clock);
    private final RemoteHazardEvaluator evaluator = new RemoteHazardEvaluator(config, registry, clock);

    @Test void freshInsideAheadWarningEvaluatesButOutsideStaleExpiredAndBehindAreIgnored() {
        LocationFix north = location(43.0000, 76.0000, 0, ELAPSED);
        RemoteHazardSnapshot ahead = evaluator.evaluate(Optional.of(north), ELAPSED,
                List.of(hazard("ahead", 43.0038, 76.0000, NetworkSeverity.WARNING, NOW.minusSeconds(20), NOW.plusSeconds(60))));
        RemoteHazardWarning warning = ahead.selected().orElseThrow();
        assertEquals(RemoteHazardWarningLevel.WARNING, warning.level());
        assertEquals(BearingRelation.AHEAD, warning.bearingRelation());
        assertEquals(423, warning.distanceMeters(), 8);

        assertEmpty(north, hazard("outside", 43.03, 76.0, NetworkSeverity.CRITICAL, NOW.minusSeconds(5), NOW.plusSeconds(60)));
        assertEmpty(north, hazard("stale", 43.003, 76.0, NetworkSeverity.WARNING, NOW.minusSeconds(121), NOW.plusSeconds(60)));
        assertEmpty(north, hazard("expired", 43.003, 76.0, NetworkSeverity.WARNING, NOW.minusSeconds(5), NOW.minusSeconds(1)));
        assertEmpty(north, hazard("behind", 42.997, 76.0, NetworkSeverity.CRITICAL, NOW.minusSeconds(5), NOW.plusSeconds(60)));
    }

    @Test void missingAndStaleVehicleLocationAreExplicitlyUnavailable() {
        assertEquals(RemoteHazardSnapshot.Status.LOCATION_UNAVAILABLE,
                evaluator.evaluate(Optional.empty(), ELAPSED, List.of()).status());
        assertEquals(RemoteHazardSnapshot.Status.LOCATION_STALE,
                evaluator.evaluate(Optional.of(location(43, 76, 0, ELAPSED - 6_000_000_000L)),
                        ELAPSED, List.of()).status());
    }

    @Test void haversineKnownCaseBearingAndAngularWrapAreCorrect() {
        assertEquals(111_195, GeoMath.distanceMeters(0, 0, 0, 1), 100);
        assertEquals(0, GeoMath.initialBearingDegrees(43, 76, 44, 76), 0.01);
        assertEquals(2, GeoMath.absoluteAngularDifference(359, 1), 1e-9);
    }

    @Test void missingHeadingNeverFabricatesAheadAndCapsAtCaution() {
        LocationFix noHeading = new LocationFix(43, 76, NOW, ELAPSED, 5,
                OptionalDouble.empty(), OptionalDouble.empty(), LocationQuality.PRECISE);
        RemoteHazardWarning warning = evaluator.evaluate(Optional.of(noHeading), ELAPSED,
                List.of(hazard("unknown-heading", 43.001, 76, NetworkSeverity.CRITICAL,
                        NOW.minusSeconds(5), NOW.plusSeconds(60)))).selected().orElseThrow();
        assertEquals(BearingRelation.UNKNOWN, warning.bearingRelation());
        assertEquals(RemoteHazardWarningLevel.CAUTION, warning.level());
        assertTrue(warning.reasons().contains(RemoteHazardReason.HEADING_UNAVAILABLE));
    }

    @Test void ownEventSuppressedButUnknownEventAccepted() {
        registry.register("mine");
        LocationFix fix = location(43, 76, 0, ELAPSED);
        RemoteHazardSnapshot snapshot = evaluator.evaluate(Optional.of(fix), ELAPSED, List.of(
                hazard("mine", 43.001, 76, NetworkSeverity.WARNING, NOW.minusSeconds(5), NOW.plusSeconds(60)),
                hazard("other", 43.002, 76, NetworkSeverity.WARNING, NOW.minusSeconds(5), NOW.plusSeconds(60))));
        assertEquals("other", snapshot.selected().orElseThrow().eventId());
        assertEquals(1, snapshot.warnings().size());
    }

    @Test void priorityUsesJustifiedLevelBeforeDistanceAndStableEventIdTieBreak() {
        LocationFix fix = location(43, 76, 0, ELAPSED);
        NearbyHazard closeInfo = hazard("close", 43.0005, 76, NetworkSeverity.NORMAL,
                NOW.minusSeconds(5), NOW.plusSeconds(60));
        NearbyHazard fartherWarning = hazard("strong", 43.003, 76, NetworkSeverity.WARNING,
                NOW.minusSeconds(5), NOW.plusSeconds(60));
        assertEquals("strong", evaluator.evaluate(Optional.of(fix), ELAPSED,
                List.of(closeInfo, fartherWarning)).selected().orElseThrow().eventId());

        NearbyHazard b = hazard("b", 43.002, 76, NetworkSeverity.WARNING, NOW.minusSeconds(5), NOW.plusSeconds(60));
        NearbyHazard a = hazard("a", 43.002, 76, NetworkSeverity.WARNING, NOW.minusSeconds(5), NOW.plusSeconds(60));
        assertEquals("a", evaluator.evaluate(Optional.of(fix), ELAPSED, List.of(b, a))
                .selected().orElseThrow().eventId());
    }

    @Test void remoteDomainCannotCreateLocalCriticalOrExposeRemoteTtcAsVehicleTtc() {
        assertTrue(java.util.Arrays.stream(RemoteHazardWarningLevel.values())
                .noneMatch(v -> v.name().equals("CRITICAL")));
        assertTrue(java.util.Arrays.stream(RemoteHazardWarning.class.getRecordComponents())
                .noneMatch(v -> v.getName().toLowerCase().contains("ttc")));
        assertTrue(java.util.Arrays.stream(RemoteHazardEvaluator.class.getDeclaredMethods())
                .noneMatch(v -> v.getReturnType().getName().contains("RoadRisk")));
    }

    @Test void deterministicTwoVehicleHorseDemoProducesExpectedAdvisoryText() {
        LocationFix vehicleB = location(43.0000, 76.0000, 0, ELAPSED);
        NearbyHazard vehicleAHorse = hazard("vehicle-a-horse", 43.0038, 76.0000,
                NetworkSeverity.WARNING, NOW.minusSeconds(10), NOW.plusSeconds(90));
        RemoteHazardSnapshot snapshot = evaluator.evaluate(Optional.of(vehicleB), ELAPSED, List.of(vehicleAHorse));
        assertEquals(RemoteHazardWarningLevel.WARNING, snapshot.selected().orElseThrow().level());
        assertTrue(RemoteHazardDisplayText.render(snapshot).contains("ЛОШАДЬ"));
        assertTrue(RemoteHazardDisplayText.render(snapshot).contains("≈ 420 м"));
    }

    private void assertEmpty(LocationFix fix, NearbyHazard hazard) {
        assertTrue(evaluator.evaluate(Optional.of(fix), ELAPSED, List.of(hazard)).warnings().isEmpty());
    }
    static LocationFix location(double lat, double lon, double bearing, long elapsed) {
        return new LocationFix(lat, lon, NOW, elapsed, 5, OptionalDouble.of(bearing),
                OptionalDouble.empty(), LocationQuality.PRECISE);
    }
    static NearbyHazard hazard(String id, double lat, double lon, NetworkSeverity severity,
                               Instant received, Instant expires) {
        return new NearbyHazard(id, 1, NetworkHazardType.HORSE, severity, lat, lon,
                received.minusSeconds(1), received, expires, .82f, 0f, 20f, 4f, 1, false);
    }
}
