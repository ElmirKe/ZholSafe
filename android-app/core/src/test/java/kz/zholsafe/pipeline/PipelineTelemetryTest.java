package kz.zholsafe.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineTelemetryTest {

    @Test
    void countersAndDimensions() {
        PipelineTelemetry t = new PipelineTelemetry();
        t.onFrameReceived(640, 480, 90, 123L, 0);
        t.onFrameReceived(640, 480, 90, 456L, 100_000_000L);
        t.onFrameDropped();
        t.onFrameProcessed(200_000_000L, 4.5);
        t.onProcessingError();
        PipelineTelemetry.Snapshot s = t.snapshot();
        assertEquals(2, s.receivedFrames());
        assertEquals(1, s.processedFrames());
        assertEquals(1, s.droppedOrReplacedFrames());
        assertEquals(1, s.processingErrors());
        assertEquals(456L, s.latestFrameTimestampNanos());
        assertEquals(640, s.frameWidth());
        assertEquals(480, s.frameHeight());
        assertEquals(90, s.rotationDegrees());
        assertEquals(4.5, s.lastProcessingMillis());
    }

    @Test
    void fpsIsZeroUntilTwoTicksThenConvergesToRate() {
        PipelineTelemetry.FpsEstimator f = new PipelineTelemetry.FpsEstimator();
        assertEquals(0d, f.fps());
        f.tick(0);
        assertEquals(0d, f.fps());
        long t = 0;
        for (int i = 0; i < 50; i++) {
            t += 33_333_333L; // 30 fps
            f.tick(t);
        }
        assertEquals(30.0, f.fps(), 0.05);
    }

    @Test
    void fpsIgnoresNonMonotonicTicks() {
        PipelineTelemetry.FpsEstimator f = new PipelineTelemetry.FpsEstimator();
        f.tick(1_000_000_000L);
        f.tick(500_000_000L); // clock went backwards → ignored
        assertEquals(0d, f.fps());
        f.tick(1_000_000_000L);
        assertTrue(f.fps() > 0);
    }

    @Test
    void stateDetailNeverNull() {
        PipelineTelemetry t = new PipelineTelemetry();
        t.setState(PipelineState.UNAVAILABLE, null);
        assertEquals("", t.snapshot().stateDetail());
        assertEquals(PipelineState.UNAVAILABLE, t.state());
    }
}
