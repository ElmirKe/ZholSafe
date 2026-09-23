package kz.zholsafe.config;

import kz.zholsafe.ai.ModelDescriptor;
import kz.zholsafe.model.Contracts;

/**
 * Detector-side thresholds and model locations.
 *
 * @param roadModel              road-scene detector descriptor
 * @param driverModel            driver-face detector descriptor (may be a placeholder until Stage 3)
 * @param confidenceThreshold    detections below this are dropped in postprocessing
 * @param nmsIouThreshold        IoU threshold for non-maximum suppression
 * @param maxDetectionsPerFrame  hard cap to bound downstream work
 * @param inferenceQueueCapacity bounded queue between camera and inference (drop-oldest policy)
 */
public record DetectorConfig(
        String roadModelDir,
        String executionProvider,
        ModelDescriptor roadModel,
        ModelDescriptor driverModel,
        float confidenceThreshold,
        float nmsIouThreshold,
        int maxDetectionsPerFrame,
        int inferenceQueueCapacity) {

    public DetectorConfig {
        java.util.Objects.requireNonNull(roadModelDir, "roadModelDir");
        java.util.Objects.requireNonNull(executionProvider, "executionProvider");
        java.util.Objects.requireNonNull(roadModel, "roadModel");
        java.util.Objects.requireNonNull(driverModel, "driverModel");
        Contracts.unit("confidenceThreshold", confidenceThreshold);
        Contracts.unit("nmsIouThreshold", nmsIouThreshold);
        Contracts.positive("maxDetectionsPerFrame", maxDetectionsPerFrame);
        Contracts.positive("inferenceQueueCapacity", inferenceQueueCapacity);
    }

    public static final String DEFAULT_ROAD_MODEL_FILE = "zholsafe-road.onnx";
    public static final String DEFAULT_ROAD_LABELS_FILE = "zholsafe-road-classes.txt";
    public static final String DEFAULT_DRIVER_MODEL_FILE = "zholsafe-driver.onnx";
    public static final String DEFAULT_DRIVER_LABELS_FILE = "zholsafe-driver-classes.txt";

    /**
     * Stage 2: directory (relative to the app's model root / assets) holding {@code model.onnx},
     * {@code model-spec.json} and {@code labels.txt} for the ACTIVE road model. Switching between
     * candidate models (yolo26n / yolo11n) is a config change only. See models/README.md.
     */
    public static final String DEFAULT_ROAD_MODEL_DIR = "models/road/yolo11n";
    /** ONNX Runtime execution provider requested; "CPU" is the only one verified so far. */
    public static final String DEFAULT_EXECUTION_PROVIDER = "CPU";

    public static DetectorConfig defaults() {
        return new DetectorConfig(
                DEFAULT_ROAD_MODEL_DIR,
                DEFAULT_EXECUTION_PROVIDER,
                new ModelDescriptor("road", "models/road/" + DEFAULT_ROAD_MODEL_FILE,
                        "models/road/" + DEFAULT_ROAD_LABELS_FILE, 640, 640, true),
                new ModelDescriptor("driver", "models/driver/" + DEFAULT_DRIVER_MODEL_FILE,
                        "models/driver/" + DEFAULT_DRIVER_LABELS_FILE, 224, 224, true),
                0.35f,
                0.45f,
                32,
                2);
    }
}
