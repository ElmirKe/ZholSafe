package kz.zholsafe.network;

import kz.zholsafe.risk.RiskLevel;

import java.time.Duration;
import java.util.Objects;

/** EXPERIMENTAL MVP CONFIGURATION; values are not regulatory safety thresholds. */
public record PublicationPolicy(RiskLevel minimumLevel, Duration cooldown,
                                Duration maximumLocationAge, int suppressionTrackCapacity) {
    public PublicationPolicy {
        Objects.requireNonNull(minimumLevel, "minimumLevel");
        if (cooldown == null || cooldown.isNegative() || maximumLocationAge == null
                || maximumLocationAge.isNegative() || suppressionTrackCapacity <= 0) {
            throw new IllegalArgumentException("invalid publication policy");
        }
    }
    public static PublicationPolicy defaults() {
        return new PublicationPolicy(RiskLevel.WARNING, Duration.ofSeconds(10),
                Duration.ofSeconds(5), 256);
    }
}
