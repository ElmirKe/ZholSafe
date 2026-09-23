package kz.zholsafe.ai;

import java.util.Objects;

/**
 * Static description of a loaded detector for telemetry, benchmarks and reports.
 *
 * @param modelId          e.g. "zholsafe-yolo11n"
 * @param modelFamily      e.g. "yolo11", "yolo26"
 * @param modelVersion     free-form version string from the spec
 * @param inputWidth       model input width
 * @param inputHeight      model input height
 * @param decoder          decoder type name
 * @param nmsInModel       true if the export already performs NMS/top-k
 * @param executionProvider runtime provider actually in use (e.g. "CPU"); "NONE" if not loaded
 * @param supportedClasses human-readable list of canonical classes this model can emit
 */
public record DetectorInfo(
        String modelId,
        String modelFamily,
        String modelVersion,
        int inputWidth,
        int inputHeight,
        String decoder,
        boolean nmsInModel,
        String executionProvider,
        String supportedClasses) {

    public DetectorInfo {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(modelFamily, "modelFamily");
        Objects.requireNonNull(modelVersion, "modelVersion");
        Objects.requireNonNull(decoder, "decoder");
        Objects.requireNonNull(executionProvider, "executionProvider");
        Objects.requireNonNull(supportedClasses, "supportedClasses");
    }
}
