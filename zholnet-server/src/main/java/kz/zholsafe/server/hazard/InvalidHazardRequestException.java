package kz.zholsafe.server.hazard;

import java.util.List;

public class InvalidHazardRequestException extends RuntimeException {
    private final List<String> errors;

    public InvalidHazardRequestException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
