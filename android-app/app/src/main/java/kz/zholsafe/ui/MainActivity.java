package kz.zholsafe.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import kz.zholsafe.R;
import kz.zholsafe.BuildConfig;
import kz.zholsafe.ai.AssetModelFiles;
import kz.zholsafe.ai.OnnxRoadDetector;
import kz.zholsafe.ai.OrtSessionFactory;
import kz.zholsafe.app.ZholSafeApplication;
import kz.zholsafe.camera.RoadCamera;
import kz.zholsafe.config.DetectorConfig;
import kz.zholsafe.config.ZholSafeConfig;
import kz.zholsafe.logging.ZLog;
import kz.zholsafe.location.AndroidLocationProvider;
import kz.zholsafe.location.LocationFix;
import kz.zholsafe.location.LocationQuality;
import kz.zholsafe.location.SyntheticLocationProvider;
import kz.zholsafe.network.*;
import okhttp3.OkHttpClient;

import java.time.Clock;
import java.time.Instant;
import java.util.OptionalDouble;

/**
 * Stage 1 engineering screen: CameraX preview + telemetry overlay. No business logic here —
 * the Activity only handles permission UX, lifecycle and rendering; {@link PipelineController}
 * owns the pipeline.
 *
 * <p>Permission policy: CAMERA is requested once when LIVE mode starts. If denied, the pipeline
 * is marked UNAVAILABLE with a clear message and the app keeps running (DEMO still works). There
 * is no automatic re-prompt; the user must tap "Retry camera" explicitly.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_CAMERA = 1001;
    private static final int REQ_LOCATION = 1002;
    private static final long UI_REFRESH_MS = 500;

    private PipelineController controller;
    private PreviewView previewView;
    private DetectionOverlayView overlay;
    private TextView telemetryText;
    private Button modeButton;
    private Button retryButton;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean permissionRequestedThisSession;
    private boolean locationPermissionRequestedThisSession;
    private AndroidLocationProvider androidLocation;
    private SyntheticLocationProvider syntheticLocation;
    private RoadHazardNetworkCoordinator networkCoordinator;
    private QueuedHazardPublisher networkPublisher;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            telemetryText.setText(controller.renderTelemetry());
            overlay.setSnapshot(controller.latestTracking());
            dispatchLatestHazardMetadata();
            ui.postDelayed(this, UI_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        previewView = findViewById(R.id.previewView);
        overlay = findViewById(R.id.detectionOverlay);
        overlay.setSupportedScaleType(previewView.getScaleType());
        telemetryText = findViewById(R.id.telemetryText);
        modeButton = findViewById(R.id.modeButton);
        retryButton = findViewById(R.id.retryButton);

        ZholSafeConfig config = ((ZholSafeApplication) getApplication()).config();
        DetectorConfig det = config.detector();
        AssetModelFiles files = new AssetModelFiles(this);
        controller = new PipelineController(config.mode(),
                () -> new RoadCamera(this, this, previewView),
                () -> new OnnxRoadDetector(det.roadModelDir(), files, new OrtSessionFactory(det.executionProvider(), 2)), config.tracking());
        configureZholNet(config);

        modeButton.setOnClickListener(v -> toggleMode());
        retryButton.setOnClickListener(v -> {
            permissionRequestedThisSession = false;
            startForCurrentMode();
        });
        updateButtons();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startForCurrentMode();
        if (controller.mode() == ZholSafeConfig.OperatingMode.LIVE && hasCameraPermission()) {
            startLocationIfPermitted();
        }
        ui.post(refresh);
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(refresh);
        controller.stop();
        androidLocation.close();
        super.onStop();
    }

    @Override protected void onDestroy() {
        networkPublisher.close();
        super.onDestroy();
    }

    private void toggleMode() {
        ZholSafeConfig.OperatingMode next = controller.mode() == ZholSafeConfig.OperatingMode.LIVE
                ? ZholSafeConfig.OperatingMode.DEMO : ZholSafeConfig.OperatingMode.LIVE;
        controller.setMode(next);
        ZLog.i(TAG, "mode switched to " + next);
        updateButtons();
        startForCurrentMode();
        if (controller.mode() == ZholSafeConfig.OperatingMode.LIVE) startLocationIfPermitted();
    }

    private void startForCurrentMode() {
        if (controller.isRunning()) {
            return;
        }
        if (controller.mode() == ZholSafeConfig.OperatingMode.DEMO) {
            previewView.setVisibility(View.INVISIBLE);
            controller.start();
            return;
        }
        previewView.setVisibility(View.VISIBLE);
        if (hasCameraPermission()) {
            controller.start();
        } else if (!permissionRequestedThisSession) {
            permissionRequestedThisSession = true;
            ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.CAMERA }, REQ_CAMERA);
        } else {
            controller.markUnavailable(getString(R.string.status_camera_permission_denied));
        }
        updateButtons();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                androidLocation.start();
            } else {
                ZLog.w(TAG, "LOCATION permission denied; local safety continues, ZholNet publication disabled");
            }
            return;
        }
        if (requestCode != REQ_CAMERA) {
            return;
        }
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        ZLog.i(TAG, "CAMERA permission " + (granted ? "granted" : "denied"));
        if (granted) {
            controller.start();
        } else {
            controller.markUnavailable(getString(R.string.status_camera_permission_denied));
        }
        startLocationIfPermitted();
        updateButtons();
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void configureZholNet(ZholSafeConfig config) {
        androidLocation = new AndroidLocationProvider(this);
        syntheticLocation = new SyntheticLocationProvider();
        SourceTimeMapper time = new SourceTimeMapper(System.nanoTime(), Instant.now());
        AnonymousSourceIdProvider source = new SharedPreferencesAnonymousSourceIdProvider(this);
        HazardEventBridge bridge = new HazardEventBridge(PublicationPolicy.defaults(), source, time);
        ZholNetClient client = new OkHttpZholNetClient(new OkHttpClient(),
                BuildConfig.ZHOLNET_BASE_URL, new HazardJsonCodec());
        networkPublisher = new QueuedHazardPublisher(client, RetryQueueConfig.defaults(), Clock.systemUTC());
        networkCoordinator = new RoadHazardNetworkCoordinator(
                () -> controller.mode() == ZholSafeConfig.OperatingMode.DEMO
                        ? syntheticLocation.latestFix() : androidLocation.latestFix(),
                bridge, networkPublisher);
    }

    private void startLocationIfPermitted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            androidLocation.start();
        } else if (!locationPermissionRequestedThisSession) {
            locationPermissionRequestedThisSession = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
        }
    }

    private void dispatchLatestHazardMetadata() {
        var risk = controller.latestRoadRisk();
        var tracking = controller.latestTracking();
        var physical = controller.latestPhysical();
        if (risk == null || tracking == null || physical == null) return;
        if (controller.mode() == ZholSafeConfig.OperatingMode.DEMO && risk.frameTimestampNanos() > 0) {
            syntheticLocation.set(new LocationFix(43.238, 76.945, Instant.now(),
                    risk.frameTimestampNanos(), 5, OptionalDouble.of(90), OptionalDouble.empty(),
                    LocationQuality.PRECISE));
        }
        networkCoordinator.afterLocalRisk(risk, tracking, physical);
    }

    private void updateButtons() {
        boolean live = controller.mode() == ZholSafeConfig.OperatingMode.LIVE;
        modeButton.setText(live ? R.string.action_switch_to_demo : R.string.action_switch_to_live);
        retryButton.setVisibility(live && !hasCameraPermission() ? View.VISIBLE : View.GONE);
    }
}
