package kz.zholsafe.ai;

/** A single inference attempt failed. Counted by the pipeline; never converted into an empty result. */
public class DetectionException extends Exception {
    public DetectionException(String message) {
        super(message);
    }

    public DetectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
