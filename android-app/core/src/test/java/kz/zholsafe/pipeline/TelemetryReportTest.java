package kz.zholsafe.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryReportTest {

    @Test
    void rendersAllRequiredFields() {
        PipelineTelemetry t = new PipelineTelemetry();
        t.setState(PipelineState.RUNNING, "");
        t.onFrameReceived(1280, 720, 90, 5L, 0);
        String r = TelemetryReport.render("LIVE", "ROAD (rear)", t.snapshot(),
                new DiagnosticFrameProcessor().statusLine());
        assertTrue(r.contains("MODE: LIVE"));
        assertTrue(r.contains("CAMERA: ROAD (rear)"));
        assertTrue(r.contains("PIPELINE: RUNNING"));
        assertTrue(r.contains("RESOLUTION: 1280x720  ROTATION: 90°"));
        assertTrue(r.contains("RECEIVED 1  PROCESSED 0  REPLACED 0  ERRORS 0"));
        assertTrue(r.contains("AI detector: NOT LOADED — STAGE 2"));
    }

    @Test
    void showsDetailWhenUnavailable() {
        PipelineTelemetry t = new PipelineTelemetry();
        t.setState(PipelineState.UNAVAILABLE, "CAMERA permission denied");
        String r = TelemetryReport.render("LIVE", "ROAD (rear)", t.snapshot(), "x");
        assertTrue(r.contains("PIPELINE: UNAVAILABLE — CAMERA permission denied"));
        assertTrue(r.contains("RESOLUTION: —"));
    }
}
