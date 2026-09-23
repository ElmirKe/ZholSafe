package kz.zholsafe.ai;

import android.content.Context;
import android.graphics.Bitmap;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import kz.zholsafe.driver.DriverObservation;
import kz.zholsafe.driver.DriverObservationProvider;
import kz.zholsafe.driver.HeadPose;
import kz.zholsafe.logging.ZLog;

/**
 * {@link DriverObservationProvider} backed by Google AI Edge (MediaPipe) Face Landmarker, running
 * fully on-device from the bundled {@code face_landmarker.task} asset.
 *
 * <h2>Mapping to the Stage 4.3 contract</h2>
 * <ul>
 *   <li>eye openness = {@code 1 − eyeBlinkLeft/Right} blendshape score;</li>
 *   <li>mouth-open score = {@code jawOpen} blendshape score;</li>
 *   <li>head pose = Euler angles of the facial transformation matrix, converted to the core
 *       convention (see {@link #headPose(float[])}) and taken RELATIVE to the driver's neutral
 *       pose (see "Neutral pose").</li>
 * </ul>
 * Anything the landmarker did not return is reported as unavailable (NaN + flag), never guessed.
 *
 * <h2>Neutral pose</h2>
 * The matrix is relative to the camera, not to the road: with the phone mounted above the
 * driver's eyes a driver looking straight ahead already reads as ~+30–40° pitch ("down"), which
 * would trip the core's head-down threshold permanently. The first {@value #NEUTRAL_POSE_FRAMES}
 * frames with a face (~2 s) record the driver's usual pose (median yaw/pitch); afterwards the
 * provider reports the deviation from it. Until that calibration is complete the head pose is
 * reported UNAVAILABLE — never a guessed zero.
 *
 * <h2>Confidence</h2>
 * Face Landmarker exposes no per-face score; it only returns faces whose presence score passed
 * {@link #MIN_FACE_PRESENCE}. The observation therefore carries that threshold as a documented
 * lower bound, not a measured probability.
 *
 * <h2>Threading</h2>
 * {@link #provide} is called serially from the driver pipeline thread (contract of the port). The
 * RGB conversion buffers are reused between calls; nothing from the {@link Frame} is retained.
 */
public final class MediaPipeDriverObservationProvider implements DriverObservationProvider, AutoCloseable {

    public static final String MODEL_ASSET = "models/driver/face_landmarker.task";
    static final float MIN_FACE_PRESENCE = 0.6f;
    private static final String TAG = "MediaPipeDriver";

    static final int NEUTRAL_POSE_FRAMES = 30;

    private final FaceLandmarker landmarker;
    private final float[] neutralYaw = new float[NEUTRAL_POSE_FRAMES];
    private final float[] neutralPitch = new float[NEUTRAL_POSE_FRAMES];
    private int neutralSamples;
    private float baseYaw;
    private float basePitch;
    private int[] argb = new int[0];
    private Bitmap bitmap;
    private long lastTimestampMs = Long.MIN_VALUE;

    public MediaPipeDriverObservationProvider(Context context) {
        FaceLandmarker created;
        try {
            created = create(context, Delegate.GPU);
        } catch (RuntimeException e) {
            ZLog.w(TAG, "GPU delegate unavailable, falling back to CPU: " + e.getMessage());
            created = create(context, Delegate.CPU);
        }
        landmarker = created;
    }

    private static FaceLandmarker create(Context context, Delegate delegate) {
        FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder()
                        .setModelAssetPath(MODEL_ASSET)
                        .setDelegate(delegate)
                        .build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(MIN_FACE_PRESENCE)
                .setMinTrackingConfidence(0.5f)
                .setOutputFaceBlendshapes(true)
                .setOutputFacialTransformationMatrixes(true)
                .build();
        return FaceLandmarker.createFromOptions(context, options);
    }

    @Override
    public String sourceId() {
        return "mediapipe-face";
    }

    @Override
    public DriverObservation provide(Frame frame) throws DetectionException {
        Objects.requireNonNull(frame, "frame");
        if (frame.format() != Frame.PixelFormat.NV21) {
            throw new DetectionException("driver frames must be NV21, got " + frame.format());
        }
        // VIDEO mode requires strictly increasing timestamps; the camera clock is monotonic, but
        // two frames can share a millisecond.
        long timestampMs = Math.max(frame.timestampNanos() / 1_000_000L, lastTimestampMs + 1);
        lastTimestampMs = timestampMs;

        MPImage image = new BitmapImageBuilder(toBitmap(frame)).build();
        ImageProcessingOptions rotation = ImageProcessingOptions.builder()
                .setRotationDegrees(frame.rotationDegrees())
                .build();
        FaceLandmarkerResult result;
        try {
            result = landmarker.detectForVideo(image, rotation, timestampMs);
        } catch (RuntimeException e) {
            throw new DetectionException("face landmarker failed: " + e.getMessage(), e);
        }
        DriverObservation raw = toObservation(result, frame.timestampNanos());
        return relativeToNeutralPose(raw);
    }

    /** Replaces the camera-relative head pose with the deviation from the driver's neutral pose. */
    private DriverObservation relativeToNeutralPose(DriverObservation o) {
        HeadPose pose = o.headPose();
        if (!pose.available()) {
            return o;
        }
        HeadPose relative;
        if (neutralSamples < NEUTRAL_POSE_FRAMES) {
            neutralYaw[neutralSamples] = pose.yawDeg();
            neutralPitch[neutralSamples] = pose.pitchDeg();
            neutralSamples++;
            if (neutralSamples == NEUTRAL_POSE_FRAMES) {
                baseYaw = median(neutralYaw);
                basePitch = median(neutralPitch);
                ZLog.i(TAG, "neutral head pose: yaw=" + baseYaw + " pitch=" + basePitch);
            }
            relative = HeadPose.UNAVAILABLE;
        } else {
            relative = HeadPose.of(pose.yawDeg() - baseYaw, pose.pitchDeg() - basePitch, pose.rollDeg());
        }
        return new DriverObservation(o.timestampNanos(), o.faceDetected(), o.eyeOpennessAvailable(),
                o.leftEyeOpenness(), o.rightEyeOpenness(), o.mouthAvailable(), o.mouthOpenScore(),
                relative, o.confidence());
    }

    private static float median(float[] values) {
        float[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    static DriverObservation toObservation(FaceLandmarkerResult result, long timestampNanos) {
        if (result.faceLandmarks().isEmpty()) {
            return DriverObservation.noFace(timestampNanos);
        }
        Optional<List<Category>> shapes = result.faceBlendshapes().flatMap(all -> all.stream().findFirst());
        float leftBlink = score(shapes, "eyeBlinkLeft");
        float rightBlink = score(shapes, "eyeBlinkRight");
        float jaw = score(shapes, "jawOpen");
        boolean eyes = !Float.isNaN(leftBlink) && !Float.isNaN(rightBlink);
        boolean mouth = !Float.isNaN(jaw);

        HeadPose pose = result.facialTransformationMatrixes()
                .flatMap(all -> all.stream().findFirst())
                .map(MediaPipeDriverObservationProvider::headPose)
                .orElse(HeadPose.UNAVAILABLE);

        return new DriverObservation(timestampNanos, true,
                eyes, eyes ? clamp(1f - leftBlink) : Float.NaN, eyes ? clamp(1f - rightBlink) : Float.NaN,
                mouth, mouth ? clamp(jaw) : Float.NaN,
                pose, MIN_FACE_PRESENCE);
    }

    private static float score(Optional<List<Category>> shapes, String name) {
        if (shapes.isEmpty()) {
            return Float.NaN;
        }
        for (Category c : shapes.get()) {
            if (name.equals(c.categoryName())) {
                return c.score();
            }
        }
        return Float.NaN;
    }

    /**
     * Euler angles (degrees) from MediaPipe's 4×4 facial transformation matrix (column-major).
     * Core convention: positive pitch = head tilted DOWN, positive yaw = head turned to the
     * driver's left as seen by the camera. Pitch sign checked on real photos with the same model
     * and matrix layout: looking down +22°, frontal +10°, looking up −13°.
     */
    static HeadPose headPose(float[] m) {
        if (m == null || m.length < 16) {
            return HeadPose.UNAVAILABLE;
        }
        // r(row, col) of the rotation part, column-major storage.
        float r10 = m[1], r20 = m[2], r21 = m[6], r22 = m[10], r00 = m[0];
        double pitch = Math.toDegrees(Math.atan2(r21, r22));
        double yaw = Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, -r20))));
        double roll = Math.toDegrees(Math.atan2(r10, r00));
        if (!Double.isFinite(pitch) || !Double.isFinite(yaw) || !Double.isFinite(roll)) {
            return HeadPose.UNAVAILABLE;
        }
        return HeadPose.of((float) yaw, (float) (PITCH_SIGN * pitch), (float) roll);
    }

    /** +1 or −1: maps MediaPipe's pitch to "positive = looking down". Set from the photo check. */
    static final int PITCH_SIGN = 1;

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** NV21 → ARGB_8888 (BT.601 limited range). Buffers are reused across frames. */
    private Bitmap toBitmap(Frame frame) {
        int w = frame.width();
        int h = frame.height();
        if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            argb = new int[w * h];
        }
        ByteBuffer data = frame.data();
        int frameSize = w * h;
        for (int j = 0; j < h; j++) {
            int uvRow = frameSize + (j >> 1) * w;
            for (int i = 0; i < w; i++) {
                int y = Math.max(0, (data.get(j * w + i) & 0xff) - 16);
                int uvIndex = uvRow + (i & ~1);
                int v = (data.get(uvIndex) & 0xff) - 128;
                int u = (data.get(uvIndex + 1) & 0xff) - 128;
                int y1192 = 1192 * y;
                int r = clampByte((y1192 + 1634 * v) >> 10);
                int g = clampByte((y1192 - 833 * v - 400 * u) >> 10);
                int b = clampByte((y1192 + 2066 * u) >> 10);
                argb[j * w + i] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        bitmap.setPixels(argb, 0, w, 0, 0, w, h);
        return bitmap;
    }

    private static int clampByte(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }

    @Override
    public void close() {
        landmarker.close();
    }
}
