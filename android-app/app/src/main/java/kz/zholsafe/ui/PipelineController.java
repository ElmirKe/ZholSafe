package kz.zholsafe.ui;

import kz.zholsafe.config.ZholSafeConfig;
import kz.zholsafe.pipeline.DiagnosticFrameProcessor;
import kz.zholsafe.pipeline.FramePipeline;
import kz.zholsafe.pipeline.FrameProcessor;
import kz.zholsafe.pipeline.FrameSource;
import kz.zholsafe.pipeline.PipelineState;
import kz.zholsafe.pipeline.PipelineTelemetry;
import kz.zholsafe.pipeline.SyntheticFrameSource;
import kz.zholsafe.pipeline.TelemetryReport;

import java.util.function.Supplier;

/**
 * Glue between the Activity and the core pipeline: picks the {@link FrameSource} for the mode,
 * owns the {@link FramePipeline} and renders telemetry text. No Android imports on purpose — the
 * Activity injects a factory for the LIVE source (CameraX) so this class stays plain Java.
 *
 * <p>LIVE and DEMO differ ONLY in the {@link FrameSource}; processor, queue, telemetry and state
 * handling are identical.
 */
final class PipelineController {

    /** Synthetic DEMO stream parameters (engineering placeholder, not a video file). */
    static final int DEMO_WIDTH = 640;
    static final int DEMO_HEIGHT = 480;
    static final int DEMO_ROTATION = 0;
    static final double DEMO_FPS = 20;

    private final Supplier<FrameSource> liveSourceFactory;
    private final PipelineTelemetry telemetry = new PipelineTelemetry();
    private ZholSafeConfig.OperatingMode mode;
    private FramePipeline pipeline;
    private FrameProcessor processor;

    PipelineController(ZholSafeConfig.OperatingMode initialMode, Supplier<FrameSource> liveSourceFactory) {
        this.mode = initialMode;
        this.liveSourceFactory = liveSourceFactory;
    }

    ZholSafeConfig.OperatingMode mode() {
        return mode;
    }

    PipelineTelemetry telemetry() {
        return telemetry;
    }

    boolean isRunning() {
        return pipeline != null && pipeline.isRunning();
    }

    /** Main thread. Requires camera permission to have been granted when mode == LIVE. */
    void start() {
        if (pipeline != null) {
            return;
        }
        FrameSource source = mode == ZholSafeConfig.OperatingMode.LIVE
                ? liveSourceFactory.get()
                : new SyntheticFrameSource(DEMO_WIDTH, DEMO_HEIGHT, DEMO_ROTATION, DEMO_FPS);
        processor = new DiagnosticFrameProcessor();
        pipeline = new FramePipeline(source, processor, telemetry);
        pipeline.start();
    }

    /** Main thread. Idempotent. */
    void stop() {
        if (pipeline != null) {
            pipeline.stop();
            pipeline = null;
        }
    }

    /** Records a non-crashing terminal state (e.g. permission denied) without starting anything. */
    void markUnavailable(String reason) {
        telemetry.setState(PipelineState.UNAVAILABLE, reason);
    }

    void setMode(ZholSafeConfig.OperatingMode newMode) {
        if (newMode != mode) {
            stop();
            mode = newMode;
            telemetry.setState(PipelineState.NOT_STARTED, "");
        }
    }

    String cameraLabel() {
        return mode == ZholSafeConfig.OperatingMode.LIVE
                ? "ROAD (rear, CameraX)"
                : "DEMO synthetic " + DEMO_WIDTH + "x" + DEMO_HEIGHT + " (no hardware)";
    }

    String renderTelemetry() {
        String status = processor != null ? processor.statusLine() : "AI detector: NOT LOADED — STAGE 2";
        return TelemetryReport.render(mode.name(), cameraLabel(), telemetry.snapshot(), status);
    }
}
