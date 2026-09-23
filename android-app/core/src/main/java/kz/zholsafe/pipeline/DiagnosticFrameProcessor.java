package kz.zholsafe.pipeline;

import kz.zholsafe.ai.Frame;

import java.nio.ByteBuffer;

/**
 * Stage 1 placeholder consumer. Proves camera → Frame → queue → consumer without any AI.
 *
 * <p>It reads dimensions/rotation/timestamp and computes a very cheap diagnostic: the mean
 * luma of a sparse sample of the Y plane (NV21) or the first channel (RGB). This is NOT object
 * detection and must never be presented as such. Stage 2 replaces it with the ONNX detector.
 */
public final class DiagnosticFrameProcessor implements FrameProcessor {

    /** Sample every Nth byte so the cost stays negligible even at 1080p. */
    private static final int SAMPLE_STRIDE = 97;

    private volatile double lastMeanLuma = Double.NaN;
    private volatile int lastWidth;
    private volatile int lastHeight;
    private volatile int lastRotation;

    @Override
    public void process(Frame frame) {
        lastWidth = frame.width();
        lastHeight = frame.height();
        lastRotation = frame.rotationDegrees();
        ByteBuffer d = frame.data();
        int lumaBytes = frame.format() == Frame.PixelFormat.NV21
                ? Math.min(frame.width() * frame.height(), d.limit())
                : d.limit();
        if (lumaBytes <= 0) {
            lastMeanLuma = Double.NaN;
            return;
        }
        long sum = 0;
        int n = 0;
        for (int i = 0; i < lumaBytes; i += SAMPLE_STRIDE) {
            sum += d.get(i) & 0xFF;
            n++;
        }
        lastMeanLuma = n == 0 ? Double.NaN : (double) sum / n;
    }

    public double lastMeanLuma() {
        return lastMeanLuma;
    }

    public int lastWidth() {
        return lastWidth;
    }

    public int lastHeight() {
        return lastHeight;
    }

    public int lastRotation() {
        return lastRotation;
    }

    @Override
    public String statusLine() {
        return "AI detector: NOT LOADED — STAGE 2 (diagnostic consumer active)";
    }
}
