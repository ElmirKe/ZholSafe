package kz.zholsafe.network;

import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.risk.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HazardEventBridgeTest {
    private static final Instant ANCHOR = Instant.parse("2026-09-23T12:00:00Z");
    private static final long T = 20 * Stage6TestFixtures.SECOND;

    @Test void missingAndStaleLocationSkipNetworkWhileLocalRiskRemainsAvailable() {
        var pipeline = Stage6TestFixtures.warning(T, ObjectClass.HORSE);
        HazardEventBridge bridge = bridge();
        assertTrue(pipeline.risk().available(), "local risk exists independently of location");
        assertEquals(HazardEventBridge.Outcome.MISSING_LOCATION,
                bridge.create(pipeline.risk(), pipeline.tracking(), pipeline.physical(), Optional.empty()).outcome());
        assertEquals(HazardEventBridge.Outcome.STALE_LOCATION,
                bridge.create(pipeline.risk(), pipeline.tracking(), pipeline.physical(),
                        Optional.of(Stage6TestFixtures.location(T - 6 * Stage6TestFixtures.SECOND))).outcome());
    }

    @Test void normalDoesNotPublishButWarningAndCriticalDo() {
        var normal = Stage6TestFixtures.normal(T, ObjectClass.HORSE);
        assertEquals(RiskLevel.NORMAL, normal.risk().highestLevel().orElseThrow());
        assertEquals(HazardEventBridge.Outcome.BELOW_POLICY, bridge().create(normal.risk(), normal.tracking(),
                normal.physical(), Optional.of(Stage6TestFixtures.location(T))).outcome());

        var warning = Stage6TestFixtures.warning(T, ObjectClass.HORSE);
        var warningResult = bridge().create(warning.risk(), warning.tracking(), warning.physical(),
                Optional.of(Stage6TestFixtures.location(T)));
        assertEquals(RiskLevel.WARNING, warning.risk().highestLevel().orElseThrow());
        assertEquals(NetworkSeverity.WARNING, warningResult.event().orElseThrow().severity());

        var critical = Stage6TestFixtures.critical(T, ObjectClass.PERSON);
        var criticalResult = bridge().create(critical.risk(), critical.tracking(), critical.physical(),
                Optional.of(Stage6TestFixtures.location(T)));
        assertEquals(RiskLevel.CRITICAL, critical.risk().highestLevel().orElseThrow());
        assertEquals(NetworkSeverity.CRITICAL, criticalResult.event().orElseThrow().severity());
    }

    @Test void sameTrackSuppressedUntilCooldownThenPublishesWithDeterministicWallClock() {
        HazardEventBridge bridge = bridge();
        var first = Stage6TestFixtures.warning(T, ObjectClass.HORSE);
        NetworkHazardEvent event = bridge.create(first.risk(), first.tracking(), first.physical(),
                Optional.of(Stage6TestFixtures.location(T))).event().orElseThrow();
        assertEquals(ANCHOR.plusSeconds(20), event.timestamp());

        var rapid = Stage6TestFixtures.warning(T + 9 * Stage6TestFixtures.SECOND, ObjectClass.HORSE);
        assertEquals(HazardEventBridge.Outcome.COOLDOWN_SUPPRESSED,
                bridge.create(rapid.risk(), rapid.tracking(), rapid.physical(),
                        Optional.of(Stage6TestFixtures.location(rapid.risk().frameTimestampNanos()))).outcome());
        var later = Stage6TestFixtures.warning(T + 10 * Stage6TestFixtures.SECOND, ObjectClass.HORSE);
        assertEquals(HazardEventBridge.Outcome.CREATED,
                bridge.create(later.risk(), later.tracking(), later.physical(),
                        Optional.of(Stage6TestFixtures.location(later.risk().frameTimestampNanos()))).outcome());
    }

    @Test void verifiedClassesMapAndCamelGoatUnknownRemainUnsupportedDetectorOutput() {
        assertEquals(NetworkHazardType.HORSE, HazardClassMapper.fromVerifiedDetector(ObjectClass.HORSE).orElseThrow());
        assertEquals(NetworkHazardType.DOG, HazardClassMapper.fromVerifiedDetector(ObjectClass.DOG).orElseThrow());
        assertEquals(NetworkHazardType.PERSON, HazardClassMapper.fromVerifiedDetector(ObjectClass.PERSON).orElseThrow());
        assertTrue(HazardClassMapper.fromVerifiedDetector(ObjectClass.CAMEL).isEmpty());
        assertTrue(HazardClassMapper.fromVerifiedDetector(ObjectClass.GOAT).isEmpty());
        assertTrue(HazardClassMapper.fromVerifiedDetector(ObjectClass.UNKNOWN).isEmpty());
        var camel = Stage6TestFixtures.warning(T, ObjectClass.CAMEL);
        assertEquals(HazardEventBridge.Outcome.UNSUPPORTED_CLASS, bridge().create(camel.risk(), camel.tracking(),
                camel.physical(), Optional.of(Stage6TestFixtures.location(T))).outcome());
    }

    @Test void anonymousSourceIsRandomAppTokenAndRotatableWithoutHardwareInput() {
        RandomAnonymousSourceIdProvider provider = new RandomAnonymousSourceIdProvider();
        String first = provider.current();
        String second = provider.rotate();
        assertTrue(first.matches("anon-[0-9a-f-]{36}"));
        assertNotEquals(first, second);
    }

    private static HazardEventBridge bridge() {
        AnonymousSourceIdProvider source = new AnonymousSourceIdProvider() {
            @Override public String current() { return "anon-test"; }
            @Override public String rotate() { return "anon-rotated"; }
        };
        return new HazardEventBridge(new PublicationPolicy(RiskLevel.WARNING, Duration.ofSeconds(10),
                Duration.ofSeconds(5), 4), source, new SourceTimeMapper(0, ANCHOR));
    }
}
