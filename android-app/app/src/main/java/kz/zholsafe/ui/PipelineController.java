package kz.zholsafe.ui;

import kz.zholsafe.ai.RoadDetector;
import kz.zholsafe.config.CombinedRiskConfig;
import kz.zholsafe.config.DriverGuardConfig;
import kz.zholsafe.config.TrackingConfig;
import kz.zholsafe.config.TrajectoryConfig;
import kz.zholsafe.config.ZholSafeConfig;
import kz.zholsafe.driver.DriverObservationProvider;
import kz.zholsafe.logging.ZLog;
import kz.zholsafe.pipeline.DetectionReport;
import kz.zholsafe.pipeline.DetectionSnapshot;
import kz.zholsafe.pipeline.DriverGuardProcessor;
import kz.zholsafe.pipeline.FramePipeline;
import kz.zholsafe.pipeline.FrameSource;
import kz.zholsafe.pipeline.PipelineState;
import kz.zholsafe.pipeline.PipelineTelemetry;
import kz.zholsafe.pipeline.RoadDetectionProcessor;
import kz.zholsafe.pipeline.SyntheticFrameSource;
import kz.zholsafe.pipeline.TelemetryReport;
import kz.zholsafe.pipeline.TrackingSnapshot;
import kz.zholsafe.risk.CombinedRiskProcessor;
import kz.zholsafe.risk.CombinedRiskSnapshot;
import kz.zholsafe.risk.DriverRiskSnapshot;

import java.util.function.Supplier;

/**
 * Glue between the Activity and the core pipelines. Owns up to two independent
 * {@link FramePipeline}s — road ({@link RoadDetectionProcessor}) and driver
 * ({@link DriverGuardProcessor}) — and the {@link CombinedRiskProcessor} that fuses their latest
 * snapshots. No Android imports: the Activity injects factories for the LIVE sources (CameraX),
 * the road detector (ONNX Runtime) and the driver observation provider (MediaPipe).
 *
 * <p>Road and driver pipelines never share a queue or thread, so a slow model on one side cannot
 * stall the other. Which cameras run is chosen by {@link Cameras}: many phones cannot stream the
 * front and rear cameras at the same time, so {@link Cameras#BOTH} may degrade to one side — the
 * failing side is reported UNAVAILABLE by its own pipeline, never as "all clear".
 *
 * <p>LIVE and DEMO differ ONLY in the road {@link FrameSource}; DEMO has no driver camera.
 */
final class PipelineController {

    /** Which cameras the drive session uses. */
    enum Cameras { DRIVER, ROAD, BOTH;
        boolean driver() { return this != ROAD; }
        boolean road() { return this != DRIVER; }
    }

    static final int DEMO_WIDTH = 640;
    static final int DEMO_HEIGHT = 480;
    static final int DEMO_ROTATION = 0;
    static final double DEMO_FPS = 20;
    private static final String TAG = "PipelineController";

    private final Supplier<FrameSource> liveSourceFactory;
    private final Supplier<RoadDetector> detectorFactory;
    private final Supplier<FrameSource> driverSourceFactory;
    private final Supplier<DriverObservationProvider> driverProviderFactory;
    private final TrackingConfig trackingConfig;
    private final PipelineTelemetry telemetry = new PipelineTelemetry();
    private final PipelineTelemetry driverTelemetry = new PipelineTelemetry();
    private final CombinedRiskProcessor combined = new CombinedRiskProcessor(CombinedRiskConfig.defaults());
    private ZholSafeConfig.OperatingMode mode;
    private Cameras cameras;
    private FramePipeline pipeline;
    private RoadDetectionProcessor processor;
    private FramePipeline driverPipeline;
    private DriverGuardProcessor driverProcessor;
    private DriverObservationProvider driverProvider;

    PipelineController(ZholSafeConfig.OperatingMode initialMode, Cameras initialCameras,
                       Supplier<FrameSource> liveSourceFactory, Supplier<RoadDetector> detectorFactory,
                       Supplier<FrameSource> driverSourceFactory,
                       Supplier<DriverObservationProvider> driverProviderFactory,
                       TrackingConfig trackingConfig) {
        this.mode = initialMode;
        this.cameras = initialCameras;
        this.liveSourceFactory = liveSourceFactory;
        this.detectorFactory = detectorFactory;
        this.driverSourceFactory = driverSourceFactory;
        this.driverProviderFactory = driverProviderFactory;
        this.trackingConfig = trackingConfig;
    }

    ZholSafeConfig.OperatingMode mode() {
        return mode;
    }

    Cameras cameras() {
        return cameras;
    }

    PipelineTelemetry telemetry() {
        return telemetry;
    }

    boolean isRunning() {
        return (pipeline != null && pipeline.isRunning()) || (driverPipeline != null && driverPipeline.isRunning());
    }

    private boolean live() {
        return mode == ZholSafeConfig.OperatingMode.LIVE;
    }

    /**
     * Main thread. Model loading happens synchronously here (first start only, ~hundreds of ms);
     * acceptable for the engineering build, to be moved off-thread with a LOADING screen later.
     */
    void start() {
        if (isRunning()) {
            return;
        }
        if (!live() || cameras.road()) {
            startRoad();
        }
        if (live() && cameras.driver()) {
            startDriver();
        }
    }

    private void startRoad() {
        FrameSource source = live()
                ? liveSourceFactory.get()
                : new SyntheticFrameSource(DEMO_WIDTH, DEMO_HEIGHT, DEMO_ROTATION, DEMO_FPS);
        // Stage 4.1 physical diagnostics are wired inline by RoadDetectionProcessor, but this
        // default has NO measured calibration/prior: no metric depth/rate/TTC is invented.
        processor = new RoadDetectionProcessor(detectorFactory.get(), trackingConfig, TrajectoryConfig.defaults());
        processor.load(); // failure → processor reports MODEL NOT AVAILABLE; pipeline still runs and degrades
        pipeline = new FramePipeline(source, processor, telemetry);
        pipeline.start();
    }

    private void startDriver() {
        try {
            driverProvider = driverProviderFactory.get();
        } catch (RuntimeException e) {
            // Missing/corrupt face model: driver monitoring is UNAVAILABLE, never silently "fine".
            ZLog.e(TAG, "driver provider failed to load", e);
            driverTelemetry.setState(PipelineState.UNAVAILABLE, "face model not available: " + e.getMessage());
            return;
        }
        driverProcessor = new DriverGuardProcessor(driverProvider, DriverGuardConfig.defaults());
        driverPipeline = new FramePipeline(driverSourceFactory.get(), driverProcessor, driverTelemetry);
        driverPipeline.start();
    }

    void stop() {
        if (pipeline != null) {
            pipeline.stop(); // closes processor → closes detector/session
            pipeline = null;
            processor = null;
        }
        if (driverPipeline != null) {
            driverPipeline.stop();
            driverPipeline = null;
            driverProcessor = null;
        }
        if (driverProvider instanceof AutoCloseable) {
            try {
                ((AutoCloseable) driverProvider).close();
            } catch (Exception e) {
                ZLog.w(TAG, "driver provider close failed: " + e);
            }
        }
        driverProvider = null;
    }

    void markUnavailable(String reason) {
        telemetry.setState(PipelineState.UNAVAILABLE, reason);
        driverTelemetry.setState(PipelineState.UNAVAILABLE, reason);
    }

    void setMode(ZholSafeConfig.OperatingMode newMode) {
        if (newMode != mode) {
            stop();
            mode = newMode;
            telemetry.setState(PipelineState.NOT_STARTED, "");
            driverTelemetry.setState(PipelineState.NOT_STARTED, "");
        }
    }

    void setCameras(Cameras newCameras) {
        if (newCameras != cameras) {
            stop();
            cameras = newCameras;
            telemetry.setState(PipelineState.NOT_STARTED, "");
            driverTelemetry.setState(PipelineState.NOT_STARTED, "");
        }
    }

    /**
     * Fuses the latest road and driver snapshots. Call periodically (UI tick). Sides that are not
     * running keep their NOT_STARTED snapshot, which the combined engine treats as unavailable —
     * the result then degrades to ROAD_ONLY / DRIVER_ONLY / UNAVAILABLE instead of inventing data.
     */
    CombinedRiskSnapshot evaluateRisk() {
        if (processor != null) {
            combined.updateRoad(processor.latestRoadRisk());
        }
        combined.updateDriver(driverProcessor != null ? driverProcessor.latestRisk() : DriverRiskSnapshot.notStarted());
        return combined.evaluate();
    }

    /** Driver pipeline state for the UI (camera error, model missing, running…). */
    PipelineState driverPipelineState() {
        return driverTelemetry.snapshot().state();
    }

    PipelineState roadPipelineState() {
        return telemetry.snapshot().state();
    }

    /**
     * True while the driver pipeline runs but its provider is still learning this driver's normal
     * eyes and head pose (providers without calibration are never "calibrating").
     */
    boolean driverCalibrating() {
        return driverRunning() && driverProvider instanceof kz.zholsafe.ai.MediaPipeDriverObservationProvider
                && !((kz.zholsafe.ai.MediaPipeDriverObservationProvider) driverProvider).calibrated();
    }

    boolean driverRunning() {
        return driverPipeline != null && driverPipeline.isRunning();
    }

    boolean roadRunning() {
        return pipeline != null && pipeline.isRunning();
    }

    String cameraLabel() {
        return live()
                ? "LIVE " + cameras
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
        String road = TelemetryReport.render(mode.name(), cameraLabel(), telemetry.snapshot(), det);
        String driver = driverProcessor != null ? driverProcessor.statusLine() : "DriverGuard: not running";
        return road + "\n" + driver + "  pipeline=" + driverTelemetry.snapshot().state();
    }
}
