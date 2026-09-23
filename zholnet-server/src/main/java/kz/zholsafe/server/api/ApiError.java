package kz.zholsafe.server.api;

import java.time.Instant;
import java.util.List;

public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        List<String> details) {
    public ApiError {
        details = details == null ? List.of() : List.copyOf(details);
    }
}
