package kz.zholsafe.remote;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record RemoteHazardSnapshot(Status status, Instant evaluatedAt,
        List<RemoteHazardWarning> warnings, Optional<RemoteHazardWarning> selected,
        String detail) {
    public enum Status { AVAILABLE, LOCATION_UNAVAILABLE, LOCATION_STALE, NETWORK_UNAVAILABLE, STOPPED }
    public RemoteHazardSnapshot {
        Objects.requireNonNull(status); Objects.requireNonNull(evaluatedAt);
        warnings = List.copyOf(Objects.requireNonNull(warnings));
        Objects.requireNonNull(selected); detail = detail == null ? "" : detail;
        if (status != Status.AVAILABLE && (!warnings.isEmpty() || selected.isPresent())) {
            throw new IllegalArgumentException("unavailable snapshot cannot contain advisories");
        }
        if (selected.isPresent() && !warnings.contains(selected.get())) {
            throw new IllegalArgumentException("selected advisory must be in list");
        }
    }
    public static RemoteHazardSnapshot unavailable(Status status, Instant now, String detail) {
        if (status == Status.AVAILABLE) throw new IllegalArgumentException("AVAILABLE is not unavailable");
        return new RemoteHazardSnapshot(status, now, List.of(), Optional.empty(), detail);
    }
}
