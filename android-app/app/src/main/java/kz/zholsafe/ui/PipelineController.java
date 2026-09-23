package kz.zholsafe.ui;

import kz.zholsafe.ai.RoadDetector;
import kz.zholsafe.config.ZholSafeConfig;
import kz.zholsafe.config.TrackingConfig;
import kz.zholsafe.config.TrajectoryConfig;
import kz.zholsafe.pipeline.DetectionReport;
import kz.zholsafe.pipeline.DetectionSnapshot;
import kz.zholsafe.pipeline.FramePipeline;
import kz.zholsafe.pipeline.FrameSource;
import kz.zholsafe.pipeline.PipelineState;
import kz.zholsafe.pipeline.PipelineTelemetry;
import kz.zholsafe.pipeline.RoadDetectionProcessor;
import kz.zholsafe.pipeline.SyntheticFrameSource;
import kz.zholsafe.pipeline.TelemetryReport;
import kz.zholsafe.pipeline.TrackingSnapshot;

import java.util.function.Supplier;

/**
 * Glue between the Activity and the core pipeline: picks the {@link FrameSource} for the mode,
 * owns the {@link FramePipeline} + {@link RoadDetectionProcessor} and renders telemetry text.
 * No Android imports — the Activity injects factories for the LIVE source (CameraX) and the
 * detector (ONNX Runtime).
 *
 * <p>LIVE and DEMO differ ONLY in the {@link FrameSource}. Both use the SAME real detector
 * factory: DEMO runs the real ONNX model on synthetic frames. There is no fake detector in the
 * app at all (FakeRoadDetector exists only in core tests).
 */
final class PipelineController {

    static final int DEMO_WIDTH = 640;
    static final int DEMO_HEIGHT = 480;
    static final int DEMO_ROTATION = 0;
    static final double DEMO_FPS = 20;

    private final Supplier<FrameSource> liveSourceFactory;
    private final Supplier<RoadDetector> detectorFactory;
    private final TrackingConfig trackingConfig;
    private final PipelineTelemetry telemetry = new PipelineTelemetry();
    private ZholSafeConfig.OperatingMode mode;
    private FramePipeline pipeline;
    private RoadDetectionProcessor processor;

    PipelineController(ZholSafeConfig.OperatingMode initialMode, Supplier<FrameSource> liveSourceFactory,
                       Supplier<RoadDetector> detectorFactory, TrackingConfig trackingConfig) {
        this.mode = initialMode;
        this.liveSourceFactory = liveSourceFactory;
        this.detectorFactory = detectorFactory;
        this.trackingConfig = trackingConfig;
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

    /**
     * Main thread. Model loading happens synchronously here (first start only, ~hundreds of ms);
     * acceptable for the engineering build, to be moved off-thread with a LOADING screen later.
     */
    void start() {
        if (pipeline != null) {
            return;
        }
        FrameSource source = mode == ZholSafeConfig.OperatingMode.LIVE
                ? liveSourceFactory.get()
                : new SyntheticFrameSource(DEMO_WIDTH, DEMO_HEIGHT, DEMO_ROTATION, DEMO_FPS);
        // Stage 4.1 physical diagnostics are wired inline by RoadDetectionProcessor, but this
        // default has NO measured calibration/prior: no metric depth/rate/TTC is invented.
        processor = new RoadDetectionProcessor(detectorFactory.get(), trackingConfig, TrajectoryConfig.defaults());
        processor.load(); // failure → processor reports MODEL NOT AVAILABLE; pipeline still runs and degrades
        pipeline = new FramePipeline(source, processor, telemetry);
        pipeline.start();
    }

    void stop() {
        if (pipeline != null) {
            pipeline.stop(); // closes processor → closes detector/session
            pipeline = null;
            processor = null;
        }
    }

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
                : "DEMO synthetic " + DEMO_WIDTH + "x" + DEMO_HEIGHT + " (no hardware; real model)";
    }

    /** Latest detection snapshot or null when no processor exists. */
    DetectionSnapshot latestDetections() {
        return processor == null ? null : processor.latest();
    }

    TrackingSnapshot latestTracking() {
        return processor == null ? null : processor.latestTracking();
    }

    String renderTelemetry() {
        String det = processor != null ? DetectionReport.render(processor) : "MODEL: —  STATUS: NOT_LOADED";
        return TelemetryReport.render(mode.name(), cameraLabel(), telemetry.snapshot(), det);
    }
}
