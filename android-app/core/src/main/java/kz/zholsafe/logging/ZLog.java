package kz.zholsafe.logging;

import java.util.Objects;

/**
 * Minimal logging façade for the core module (no Android dependency).
 *
 * <p>The app module installs an Android Logcat-backed {@link Sink}; server code uses SLF4J
 * directly. Traceable event names are standardised in {@link Event}. Never log frame pixels or
 * personal video data.
 */
public final class ZLog {

    public enum Level { DEBUG, INFO, WARN, ERROR }

    /** Standard traceable events (see docs/DEVELOPMENT.md → Logging). */
    public enum Event {
        MODEL_LOADED,
        MODEL_LOAD_FAILED,
        CAMERA_STARTED,
        CAMERA_STOPPED,
        CAMERA_ERROR,
        DETECTION_GENERATED,
        RISK_LEVEL_CHANGED,
        LOCAL_ALERT_GENERATED,
        HAZARD_EVENT_SUBMITTED,
        HAZARD_EVENT_QUEUED,
        HAZARD_EVENT_DROPPED,
        SERVER_CONNECTION_LOST,
        SERVER_CONNECTION_RESTORED,
        GPS_UNAVAILABLE,
        FRAME_DROPPED
    }

    public interface Sink {
        void log(Level level, String tag, String message, Throwable error);
    }

    private static volatile Sink sink = (level, tag, message, error) -> {
        System.out.println("[" + level + "] " + tag + ": " + message);
        if (error != null) {
            error.printStackTrace(System.out);
        }
    };

    private ZLog() { }

    public static void install(Sink newSink) {
        sink = Objects.requireNonNull(newSink, "sink");
    }

    public static void event(Event event, String tag, String details) {
        sink.log(Level.INFO, tag, event.name() + (details == null || details.isEmpty() ? "" : " " + details), null);
    }

    public static void d(String tag, String message) {
        sink.log(Level.DEBUG, tag, message, null);
    }

    public static void i(String tag, String message) {
        sink.log(Level.INFO, tag, message, null);
    }

    public static void w(String tag, String message) {
        sink.log(Level.WARN, tag, message, null);
    }

    public static void e(String tag, String message, Throwable error) {
        sink.log(Level.ERROR, tag, message, error);
    }
}
