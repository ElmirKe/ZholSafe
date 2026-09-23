package kz.zholsafe.pipeline;

import java.util.Locale;

/**
 * Renders a {@link PipelineTelemetry.Snapshot} as the multi-line engineering overlay text.
 * Pure Java so the exact wording is unit-tested and shared by LIVE and DEMO screens.
 */
public final class TelemetryReport {

    private TelemetryReport() { }

    public static String render(String mode, String cameraLabel, PipelineTelemetry.Snapshot s, String processorStatus) {
        StringBuilder b = new StringBuilder(320);
        b.append("MODE: ").append(mode).append('\n');
        b.append("CAMERA: ").append(cameraLabel).append('\n');
        b.append("PIPELINE: ").append(s.state());
        if (!s.stateDetail().isEmpty()) {
            b.append(" — ").append(s.stateDetail());
        }
        b.append('\n');
        if (s.frameWidth() > 0) {
            b.append(String.format(Locale.ROOT, "RESOLUTION: %dx%d  ROTATION: %d°%n",
                    s.frameWidth(), s.frameHeight(), s.rotationDegrees()));
        } else {
            b.append("RESOLUTION: —  ROTATION: —\n");
        }
        b.append(String.format(Locale.ROOT, "CAMERA FPS ~%.1f  PROCESSED FPS ~%.1f  LAST %.1f ms%n",
                s.receivedFps(), s.processedFps(), s.lastProcessingMillis()));
        b.append(String.format(Locale.ROOT, "RECEIVED %d  PROCESSED %d  REPLACED %d  ERRORS %d%n",
                s.receivedFrames(), s.processedFrames(), s.droppedOrReplacedFrames(), s.processingErrors()));
        b.append(processorStatus);
        return b.toString();
    }
}
