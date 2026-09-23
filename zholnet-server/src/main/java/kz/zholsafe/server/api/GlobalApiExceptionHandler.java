package kz.zholsafe.server.api;

import jakarta.servlet.http.HttpServletRequest;
import kz.zholsafe.server.hazard.HazardConflictException;
import kz.zholsafe.server.hazard.InvalidHazardRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalApiExceptionHandler.class);
    private final Clock clock;

    public GlobalApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(InvalidHazardRequestException.class)
    ResponseEntity<ApiError> invalid(InvalidHazardRequestException error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", error.getMessage(),
                request.getRequestURI(), error.errors());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> malformed(Exception error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request",
                request.getRequestURI(), List.of());
    }

    @ExceptionHandler(HazardConflictException.class)
    ResponseEntity<ApiError> conflict(HazardConflictException error, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "EVENT_ID_CONFLICT", error.getMessage(),
                request.getRequestURI(), List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception error, HttpServletRequest request) {
        LOG.error("Unexpected API failure on {}", request.getRequestURI(), error);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Unexpected server error", request.getRequestURI(), List.of());
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String code, String message,
                                              String path, List<String> details) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(clock), status.value(),
                code, message, path, details));
    }
}
