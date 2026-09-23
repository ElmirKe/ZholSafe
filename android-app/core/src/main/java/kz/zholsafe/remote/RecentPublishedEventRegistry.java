package kz.zholsafe.remote;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Thread-safe, bounded, time-expiring event-ID memory. Metadata only; not persisted across restart. */
public final class RecentPublishedEventRegistry {
    private final int capacity;
    private final Duration retention;
    private final Clock clock;
    private final LinkedHashMap<String, Instant> ids = new LinkedHashMap<>();

    public RecentPublishedEventRegistry(int capacity, Duration retention, Clock clock) {
        if (capacity <= 0 || retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("invalid registry configuration");
        }
        this.capacity = capacity; this.retention = retention; this.clock = Objects.requireNonNull(clock);
    }
    public static RecentPublishedEventRegistry defaults(Clock clock) {
        return new RecentPublishedEventRegistry(256, Duration.ofMinutes(10), clock);
    }
    public synchronized void register(String eventId) {
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("eventId required");
        Instant now = clock.instant(); prune(now); ids.remove(eventId); ids.put(eventId, now);
        while (ids.size() > capacity) ids.remove(ids.keySet().iterator().next());
    }
    public synchronized boolean contains(String eventId) {
        Instant now = clock.instant(); prune(now); return eventId != null && ids.containsKey(eventId);
    }
    public synchronized int size() { prune(clock.instant()); return ids.size(); }
    private void prune(Instant now) {
        Iterator<Map.Entry<String, Instant>> it = ids.entrySet().iterator();
        while (it.hasNext()) {
            if (Duration.between(it.next().getValue(), now).compareTo(retention) > 0) it.remove();
        }
    }
}
