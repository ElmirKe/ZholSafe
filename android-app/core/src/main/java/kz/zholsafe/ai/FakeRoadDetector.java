package kz.zholsafe.ai;

import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.Detection;
import kz.zholsafe.model.ObjectClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Deterministic scripted detector for JVM tests and clearly-labelled DEMO use ONLY. Never used in
 * LIVE mode (PipelineController refuses). Produces whatever the supplied script returns for a
 * frame; the default script returns no detections.
 */
public final class FakeRoadDetector implements RoadDetector {

    private final Function<Frame, List<Detection>> script;
    private final LabelMap labelMap = new LabelMap(List.of("person", "dog", "horse", "cow", "sheep"));
    private volatile DetectorState state = DetectorState.NOT_LOADED;
    private volatile boolean failLoad;
    private volatile RuntimeException throwOnDetect;
    private int closeCalls;

    public FakeRoadDetector() {
        this(f -> List.of());
    }

    public FakeRoadDetector(Function<Frame, List<Detection>> script) {
        this.script = Objects.requireNonNull(script);
    }

    /** Convenience script: one PERSON box centred in the upright image with the given confidence. */
    public static Function<Frame, List<Detection>> centeredPerson(float confidence) {
        return f -> {
            float w = f.uprightWidth();
            float h = f.uprightHeight();
            List<Detection> l = new ArrayList<>(1);
            l.add(new Detection(0, ObjectClass.PERSON, confidence,
                    new BoundingBox(w * 0.4f, h * 0.3f, w * 0.6f, h * 0.9f), f.timestampNanos()));
            return l;
        };
    }

    public void setFailLoad(boolean failLoad) {
        this.failLoad = failLoad;
    }

    public void setThrowOnDetect(RuntimeException e) {
        this.throwOnDetect = e;
    }

    public int closeCalls() {
        return closeCalls;
    }

    @Override
    public void load() throws ModelNotAvailableException {
        if (failLoad) {
            state = DetectorState.ERROR;
            throw new ModelNotAvailableException("fake", "scripted load failure");
        }
        state = DetectorState.READY;
    }

    @Override
    public DetectorState state() {
        return state;
    }

    @Override
    public List<Detection> detect(Frame frame) throws DetectionException {
        if (state != DetectorState.READY) throw new DetectionException("fake not ready");
        RuntimeException t = throwOnDetect;
        if (t != null) throw new DetectionException("scripted failure", t);
        return script.apply(frame);
    }

    @Override
    public LabelMap labelMap() {
        return labelMap;
    }

    @Override
    public DetectorInfo info() {
        return new DetectorInfo("fake-road-detector", "fake", "test", 0, 0, "SCRIPTED", true, "NONE",
                labelMap.supportedClasses().toString());
    }

    @Override
    public DetectorTimings lastTimings() {
        return DetectorTimings.ZERO;
    }

    @Override
    public synchronized void close() {
        closeCalls++;
        state = DetectorState.CLOSED;
    }
}
