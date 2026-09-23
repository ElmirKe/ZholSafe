package kz.zholsafe.camera;

import android.content.Context;
import android.util.Size;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import kz.zholsafe.ai.Frame;
import kz.zholsafe.logging.ZLog;
import kz.zholsafe.pipeline.FrameBufferRecycler;
import kz.zholsafe.pipeline.FrameSource;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LIVE road-facing (rear) camera as a core {@link FrameSource}, implemented with CameraX.
 *
 * <h2>Lifecycle</h2>
 * {@link #start} and {@link #stop} must be called on the main thread. Use cases are bound to the
 * supplied {@link LifecycleOwner} (the Activity); CameraX itself pauses/resumes them with the
 * lifecycle. {@link #stop} unbinds everything and terminates the analysis executor.
 *
 * <h2>Threading</h2>
 * ImageAnalysis callbacks run on a single dedicated executor ("zs-camera-analysis"). The
 * callback converts the ImageProxy into a pooled NV21 {@link Frame}, hands it to the listener
 * (which only enqueues into {@code LatestFrameQueue}) and returns. Nothing heavy happens here.
 *
 * <h2>Backpressure</h2>
 * {@link ImageAnalysis#STRATEGY_KEEP_ONLY_LATEST}: CameraX itself drops frames while the analyzer
 * is busy, and the core queue drops again if processing is slower. Two independent bounded stages,
 * no accumulation anywhere.
 *
 * <h2>ImageProxy ownership</h2>
 * Every ImageProxy is closed in a {@code finally} block inside the analyzer, including when
 * conversion throws, when the buffer pool is exhausted, and when the camera is already stopping.
 * The proxy is never stored or passed to core.
 *
 * <h2>Timestamps</h2>
 * {@link Frame#timestampNanos()} is {@code ImageInfo.getTimestamp()} — the camera image timestamp
 * in nanoseconds. Its clock domain is device-dependent (typically {@code CLOCK_BOOTTIME} or
 * {@code CLOCK_MONOTONIC}, see Camera2 {@code SENSOR_INFO_TIMESTAMP_SOURCE}); it is therefore
 * only guaranteed comparable with other frames from the same camera source. It is never mixed
 * with {@code System.nanoTime()}, which the pipeline uses solely for processing-duration / FPS
 * telemetry. Wall-clock time is not used for frames at all.
 *
 * <h2>Rotation</h2>
 * {@code ImageInfo.getRotationDegrees()} is copied into {@link Frame#rotationDegrees()}. It is the
 * clockwise rotation needed to make the buffer upright given the current display rotation
 * (the Activity is locked to landscape, so on most phones this is 0 or 180 for the rear camera,
 * and 90/270 if the device is held in portrait). Pixels are NOT rotated here.
 */
public final class RoadCamera implements FrameSource, FrameBufferRecycler {

    private static final String TAG = "RoadCamera";
    /** Analysis target: enough for detection, cheap to copy. Falls back to nearest supported. */
    private static final Size TARGET_ANALYSIS_SIZE = new Size(1280, 720);
    private static final long ERROR_LOG_EVERY = 60;

    private final Context appContext;
    private final LifecycleOwner lifecycleOwner;
    @Nullable private final PreviewView previewView;
    private final CameraFrameAdapter adapter = new CameraFrameAdapter();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Listener listener;
    private ExecutorService analysisExecutor;
    private ProcessCameraProvider provider;
    private ImageAnalysis analysis;
    private long analyzerErrors;

    public RoadCamera(@NonNull Context context, @NonNull LifecycleOwner lifecycleOwner, @Nullable PreviewView previewView) {
        this.appContext = context.getApplicationContext();
        this.lifecycleOwner = lifecycleOwner;
        this.previewView = previewView;
    }

    @MainThread
    @Override
    public void start(Listener l) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        this.listener = l;
        analysisExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "zs-camera-analysis");
            t.setDaemon(true);
            return t;
        });
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(appContext);
        future.addListener(() -> {
            if (!running.get()) {
                return; // stopped before the provider resolved
            }
            try {
                provider = future.get();
                bind(provider);
            } catch (ExecutionException | InterruptedException | RuntimeException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                fail("camera init failed: " + rootMessage(e), e);
            }
        }, ContextCompat.getMainExecutor(appContext));
    }

    @MainThread
    private void bind(ProcessCameraProvider p) {
        CameraSelector rear = CameraSelector.DEFAULT_BACK_CAMERA;
        boolean hasRear;
        try {
            hasRear = p.hasCamera(rear);
        } catch (RuntimeException e) {
            hasRear = false;
        }
        if (!hasRear) {
            fail("no rear camera available", null);
            return;
        }
        ResolutionSelector resolution = new ResolutionSelector.Builder()
                .setResolutionStrategy(new ResolutionStrategy(TARGET_ANALYSIS_SIZE,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build();
        analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setResolutionSelector(resolution)
                .build();
        analysis.setAnalyzer(analysisExecutor, this::analyze);

        p.unbindAll();
        try {
            Camera camera;
            if (previewView != null) {
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                camera = p.bindToLifecycle(lifecycleOwner, rear, preview, analysis);
            } else {
                camera = p.bindToLifecycle(lifecycleOwner, rear, analysis);
            }
            int sensor = camera.getCameraInfo().getSensorRotationDegrees();
            ZLog.i(TAG, "bound rear camera; sensorRotation=" + sensor + " target=" + TARGET_ANALYSIS_SIZE);
        } catch (IllegalArgumentException | IllegalStateException e) {
            fail("camera bind failed: " + e.getMessage(), e);
        }
    }

    /** Analysis executor thread. */
    private void analyze(@NonNull ImageProxy image) {
        try {
            Listener l = listener;
            if (!running.get() || l == null) {
                return; // closed in finally
            }
            // Camera image timestamp (ImageInfo.getTimestamp(), nanoseconds, from the camera HAL /
            // Camera2 SENSOR_TIMESTAMP). This is the capture time, NOT the callback time, and is what
            // Stage 3 tracking and future Stage 4.1 TTC will difference between consecutive frames of this source.
            // Processing-duration and FPS telemetry use System.nanoTime() separately (FramePipeline).
            long ts = image.getImageInfo().getTimestamp();
            Frame frame = adapter.convert(image, ts);
            if (frame != null) {
                l.onFrame(frame);
            }
        } catch (Throwable t) {
            analyzerErrors++;
            if (analyzerErrors == 1 || analyzerErrors % ERROR_LOG_EVERY == 0) {
                ZLog.e(TAG, "frame conversion failed (" + analyzerErrors + ")", t);
            }
            if (t instanceof Error && !(t instanceof AssertionError)) {
                throw (Error) t;
            }
        } finally {
            image.close();
        }
    }

    @MainThread
    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        listener = null;
        try {
            if (analysis != null) {
                analysis.clearAnalyzer();
            }
            if (provider != null) {
                provider.unbindAll();
            }
        } catch (RuntimeException e) {
            ZLog.w(TAG, "unbind failed: " + e);
        } finally {
            analysis = null;
            provider = null;
        }
        ExecutorService ex = analysisExecutor;
        analysisExecutor = null;
        if (ex != null) {
            ex.shutdown();
            try {
                if (!ex.awaitTermination(1, TimeUnit.SECONDS)) {
                    ex.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ex.shutdownNow();
            }
        }
        adapter.clear();
        ZLog.i(TAG, "stopped; analyzerErrors=" + analyzerErrors + " skippedNoBuffer=" + adapter.skippedNoBufferCount());
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void recycle(Frame frame) {
        adapter.recycle(frame);
    }

    private void fail(String diagnostic, @Nullable Throwable cause) {
        ZLog.w(TAG, diagnostic);
        Listener l = listener;
        if (l != null) {
            l.onSourceError(diagnostic, cause);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
    }
}
