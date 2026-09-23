package kz.zholsafe.ai;

import java.util.Objects;

/**
 * Describes where a model lives and what tensor layout it expects. Filled from configuration;
 * see {@code models/README.md} for the expected files.
 *
 * @param name         human-readable name for logs
 * @param path         path or asset name of the .onnx file
 * @param labelsPath   path or asset name of the label list (one label per line, index = line)
 * @param inputWidth   network input width in pixels
 * @param inputHeight  network input height in pixels
 * @param normalize01  whether pixel values are scaled to [0,1] (true for YOLO-family exports)
 */
public record ModelDescriptor(
        String name,
        String path,
        String labelsPath,
        int inputWidth,
        int inputHeight,
        boolean normalize01) {

    public ModelDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(labelsPath, "labelsPath");
        if (inputWidth <= 0 || inputHeight <= 0) {
            throw new IllegalArgumentException("model input size must be positive");
        }
    }
}
