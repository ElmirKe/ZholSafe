package kz.zholsafe.remote;

import org.junit.jupiter.api.Test;

import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

class RecentPublishedEventRegistryTest {
    @Test void registryIsBoundedAndExpiresWithoutPersistingSourceData() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-23T12:00:00Z"));
        RecentPublishedEventRegistry registry = new RecentPublishedEventRegistry(2, Duration.ofSeconds(10), clock);
        registry.register("one"); registry.register("two"); registry.register("three");
        assertEquals(2, registry.size()); assertFalse(registry.contains("one"));
        assertTrue(registry.contains("three"));
        clock.now = clock.now.plusSeconds(11);
        assertFalse(registry.contains("three")); assertEquals(0, registry.size());
    }

    static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
