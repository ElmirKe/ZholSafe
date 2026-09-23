package kz.zholsafe.network;

import java.time.Duration;

/** EXPERIMENTAL MVP CONFIGURATION for metadata-only bounded retry. */
public record RetryQueueConfig(int capacity, int maximumAttempts, Duration initialBackoff,
                               Duration maximumEventAge) {
    public RetryQueueConfig {
        if (capacity <= 0 || maximumAttempts <= 0 || initialBackoff == null
                || initialBackoff.isNegative() || maximumEventAge == null || maximumEventAge.isNegative()) {
            throw new IllegalArgumentException("invalid retry queue configuration");
        }
    }
    public static RetryQueueConfig defaults() {
        return new RetryQueueConfig(32, 3, Duration.ofSeconds(1), Duration.ofMinutes(2));
    }
}
