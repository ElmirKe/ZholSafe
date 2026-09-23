package kz.zholsafe.ai;

/**
 * Thrown when an ONNX model cannot be loaded (missing file, corrupt file, unsupported opset,
 * runtime not present). The application must surface this to the user as a clear diagnostic
 * and must NOT fall back to fabricated detections in live mode.
 */
public class ModelNotAvailableException extends Exception {

    private final String modelPath;

    public ModelNotAvailableException(String modelPath, String message) {
        super("Model '" + modelPath + "' not available: " + message);
        this.modelPath = modelPath;
    }

    public ModelNotAvailableException(String modelPath, String message, Throwable cause) {
        super("Model '" + modelPath + "' not available: " + message, cause);
        this.modelPath = modelPath;
    }

    public String modelPath() {
        return modelPath;
    }
}
