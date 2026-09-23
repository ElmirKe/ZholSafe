package kz.zholsafe.driver;

import kz.zholsafe.ai.Frame;

import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Deterministic scripted {@link DriverObservationProvider} for the Stage 4.3 demo and JVM tests.
 * The script maps "at or after source-time T" → observation template; {@link #provide(Frame)}
 * selects the latest scripted entry whose time is ≤ the frame's source timestamp and re-stamps
 * the template with the frame timestamp. Frames before the first entry yield
 * {@link DriverObservation#noFace}.
 *
 * <p>It feeds the SAME production chain as a real landmark backend
 * ({@link DriverStateAnalyzer} → driver risk engine → combined risk engine) — synthetic
 * OBSERVATIONS only; it never fabricates {@link DriverState} or final risk snapshots.
 * Frame pixel data is never read or retained.
 */
public final class SyntheticDriverObservationProvider implements DriverObservationProvider {

    private final String sourceId;
    private final NavigableMap<Long, DriverObservation> script;

    public SyntheticDriverObservationProvider(NavigableMap<Long, DriverObservation> script) {
        this("synthetic-script", script);
    }

    public SyntheticDriverObservationProvider(String sourceId, NavigableMap<Long, DriverObservation> script) {
        if (Objects.requireNonNull(sourceId, "sourceId").isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        Objects.requireNonNull(script, "script");
        if (script.isEmpty()) {
            throw new IllegalArgumentException("script must not be empty");
        }
        this.sourceId = sourceId;
        this.script = new TreeMap<>(script);
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public DriverObservation provide(Frame frame) {
        Objects.requireNonNull(frame, "frame");
        long ts = frame.timestampNanos();
        Map.Entry<Long, DriverObservation> entry = script.floorEntry(ts);
        if (entry == null) {
            return DriverObservation.noFace(ts);
        }
        return entry.getValue().withTimestamp(ts);
    }
}
