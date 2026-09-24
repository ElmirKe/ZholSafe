package kz.zholsafe.ui;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import kz.zholsafe.R;
import kz.zholsafe.ai.AssetModelFiles;
import kz.zholsafe.ai.MediaPipeDriverObservationProvider;
import kz.zholsafe.ai.OnnxRoadDetector;
import kz.zholsafe.ai.OrtSessionFactory;
import kz.zholsafe.alert.AlertController;
import kz.zholsafe.app.ZholSafeApplication;
import kz.zholsafe.camera.ConcurrentCameraGroup;
import kz.zholsafe.camera.LiveCamera;
import kz.zholsafe.config.DetectorConfig;
import kz.zholsafe.config.ZholSafeConfig;
import kz.zholsafe.logging.ZLog;
import kz.zholsafe.risk.CombinedRiskSnapshot;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Driver screen: camera preview(s), one status card, a coloured frame, a full-screen alarm and a
 * few large buttons. The Activity only handles permission UX, lifecycle and rendering;
 * {@link PipelineController} owns the pipelines, {@link DriveStatus} decides what to show and
 * {@link AlertController} makes the sound, voice and vibration.
 *
 * <p>Permission policy: CAMERA is requested once when LIVE mode starts. If denied, the pipelines
 * are marked UNAVAILABLE with a clear message (grey, never green) and the user can tap
 * "Разрешить камеру" explicitly.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_CAMERA = 1001;
    private static final long UI_REFRESH_MS = 150;
    private static final long TELEMETRY_REFRESH_MS = 500;
    private static final String PREFS = "zholsafe";
    private static final String PREF_CAMERAS = "cameras";
    private static final String PREF_MUTED = "muted";
    /** App languages in button order; the first is the default. */
    private static final String[] LANGUAGES = { "ru", "kk", "en" };

    private PipelineController controller;
    private AlertController alerts;
    private SharedPreferences prefs;
    private PreviewView previewView;
    private PreviewView driverPreview;
    private DetectionOverlayView overlay;
    private View statusFrame;
    private View statusDot;
    private TextView statusTitle;
    private TextView statusDetail;
    private View alarmOverlay;
    private TextView alarmTitle;
    private TextView alarmDetail;
    private TextView telemetryText;
    private Button camerasButton;
    private Button muteButton;
    private Button modeButton;
    private Button retryButton;
    private final Map<DriveStatus.Text, String> texts = new EnumMap<>(DriveStatus.Text.class);
    private final Map<DriveStatus.Text, String> russianTexts = new EnumMap<>(DriveStatus.Text.class);
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Shared by both cameras in BOTH mode (CameraX concurrent binding); null otherwise. */
    private ConcurrentCameraGroup cameraGroup;
    private boolean permissionRequestedThisSession;
    private long lastTelemetryAt;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, UI_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        previewView = findViewById(R.id.previewView);
        driverPreview = findViewById(R.id.driverPreview);
        overlay = findViewById(R.id.detectionOverlay);
        overlay.setSupportedScaleType(previewView.getScaleType());
        statusFrame = findViewById(R.id.statusFrame);
        statusDot = findViewById(R.id.statusDot);
        statusTitle = findViewById(R.id.statusTitle);
        statusDetail = findViewById(R.id.statusDetail);
        alarmOverlay = findViewById(R.id.alarmOverlay);
        alarmTitle = findViewById(R.id.alarmTitle);
        alarmDetail = findViewById(R.id.alarmDetail);
        telemetryText = findViewById(R.id.telemetryText);
        camerasButton = findViewById(R.id.camerasButton);
        muteButton = findViewById(R.id.muteButton);
        modeButton = findViewById(R.id.modeButton);
        retryButton = findViewById(R.id.retryButton);

        loadTexts();
        alerts = new AlertController(this);
        alerts.setMuted(prefs.getBoolean(PREF_MUTED, false));
        alerts.setSpeechLocale(speechLocale(currentLanguage()));

        ZholSafeConfig config = ((ZholSafeApplication) getApplication()).config();
        DetectorConfig det = config.detector();
        AssetModelFiles files = new AssetModelFiles(this);
        PipelineController.Cameras cameras = PipelineController.Cameras.valueOf(
                prefs.getString(PREF_CAMERAS, PipelineController.Cameras.DRIVER.name()));
        controller = new PipelineController(config.mode(), cameras,
                () -> new LiveCamera(this, this, previewView, LiveCamera.Lens.ROAD, cameraGroup),
                () -> new OnnxRoadDetector(det.roadModelDir(), files, new OrtSessionFactory(det.executionProvider(), 2)),
                () -> new LiveCamera(this, this, driverPreviewTarget(), LiveCamera.Lens.DRIVER, cameraGroup),
                () -> new MediaPipeDriverObservationProvider(this),
                config.tracking());

        camerasButton.setOnClickListener(v -> cycleCameras());
        muteButton.setOnClickListener(v -> {
            alerts.setMuted(!alerts.isMuted());
            prefs.edit().putBoolean(PREF_MUTED, alerts.isMuted()).apply();
            updateButtons();
        });
        modeButton.setOnClickListener(v -> toggleMode());
        findViewById(R.id.languageButton).setOnClickListener(v -> cycleLanguage());
        findViewById(R.id.telemetryButton).setOnClickListener(v -> {
            boolean show = telemetryText.getVisibility() != View.VISIBLE;
            telemetryText.setVisibility(show ? View.VISIBLE : View.GONE);
            modeButton.setVisibility(show ? View.VISIBLE : View.GONE);
        });
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
        ui.post(refresh);
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(refresh);
        controller.stop();
        alerts.silence();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        alerts.release();
        super.onDestroy();
    }

    // ---- rendering ----

    private void render() {
        CombinedRiskSnapshot snap = controller.evaluateRisk();
        boolean live = controller.mode() == ZholSafeConfig.OperatingMode.LIVE;
        PipelineController.Cameras cams = controller.cameras();
        DriveStatus status = DriveStatus.from(snap,
                live && cams.driver(), !live || cams.road(),
                controller.driverPipelineState(), controller.roadPipelineState(),
                controller.driverCalibrating());

        int color = ContextCompat.getColor(this, colorFor(status.tone()));
        statusTitle.setText(texts.get(status.title()));
        statusDetail.setText(texts.get(status.detail()));
        statusDot.setBackgroundTintList(ColorStateList.valueOf(color));
        GradientDrawable frame = (GradientDrawable) statusFrame.getBackground().mutate();
        frame.setStroke(dp(status.alarm() ? 12 : 6), color);

        alarmOverlay.setVisibility(status.alarm() ? View.VISIBLE : View.GONE);
        if (status.alarm()) {
            alarmTitle.setText(texts.get(status.title()));
            alarmDetail.setText(texts.get(status.detail()));
        }

        AlertController.Level level = status.alarm() ? AlertController.Level.ALARM
                : status.tone() == DriveStatus.Tone.CAUTION ? AlertController.Level.CAUTION
                : AlertController.Level.NONE;
        alerts.update(level, status.kind(),
                status.speechText().map(texts::get).orElse(null),
                status.speechText().map(russianTexts::get).orElse(null));

        overlay.setSnapshot(controller.latestTracking());
        long now = System.currentTimeMillis();
        if (telemetryText.getVisibility() == View.VISIBLE && now - lastTelemetryAt >= TELEMETRY_REFRESH_MS) {
            lastTelemetryAt = now;
            telemetryText.setText(controller.renderTelemetry() + "\nRISK " + snap.status()
                    + " combined=" + snap.combinedLevel().map(Enum::name).orElse("—")
                    + " reasons=" + snap.reasons());
        }
    }

    @ColorRes
    private static int colorFor(DriveStatus.Tone tone) {
        switch (tone) {
            case OK: return R.color.zs_ok;
            case CAUTION: return R.color.zs_caution;
            case ALARM: return R.color.zs_danger;
            case UNKNOWN:
            default: return R.color.zs_unknown;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---- language ----

    /** Resolves every status message once for the app language and once in Russian (TTS fallback). */
    private void loadTexts() {
        Configuration ru = new Configuration(getResources().getConfiguration());
        ru.setLocale(new Locale("ru"));
        Context russian = createConfigurationContext(ru);
        for (DriveStatus.Text t : DriveStatus.Text.values()) {
            int id = getResources().getIdentifier(t.resourceName(), "string", getPackageName());
            if (id == 0) {
                throw new IllegalStateException("missing string resource " + t.resourceName());
            }
            texts.put(t, getString(id));
            russianTexts.put(t, russian.getString(id));
        }
    }

    private String currentLanguage() {
        LocaleListCompat app = AppCompatDelegate.getApplicationLocales();
        String tag = app.isEmpty() ? Locale.getDefault().getLanguage() : app.get(0).getLanguage();
        for (String lang : LANGUAGES) {
            if (lang.equals(tag)) {
                return lang;
            }
        }
        return LANGUAGES[0];
    }

    private static Locale speechLocale(String language) {
        switch (language) {
            case "kk": return new Locale("kk", "KZ");
            case "en": return Locale.US;
            default: return new Locale("ru", "RU");
        }
    }

    /** RU → KK → EN. AppCompat stores the choice and recreates the Activity in the new language. */
    private void cycleLanguage() {
        String current = currentLanguage();
        int i = 0;
        while (!LANGUAGES[i].equals(current)) {
            i++;
        }
        String next = LANGUAGES[(i + 1) % LANGUAGES.length];
        ZLog.i(TAG, "language switched to " + next);
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next));
    }

    // ---- camera selection & layout ----

    /** With only the driver camera the front preview is the main view; otherwise a small inset. */
    private PreviewView driverPreviewTarget() {
        return controller.cameras() == PipelineController.Cameras.DRIVER ? previewView : driverPreview;
    }

    private void applyPreviewLayout() {
        PipelineController.Cameras cams = controller.cameras();
        boolean live = controller.mode() == ZholSafeConfig.OperatingMode.LIVE;
        previewView.setVisibility(live ? View.VISIBLE : View.INVISIBLE);
        driverPreview.setVisibility(live && cams == PipelineController.Cameras.BOTH ? View.VISIBLE : View.GONE);
        overlay.setVisibility(cams.road() || !live ? View.VISIBLE : View.GONE);
        // Front camera looks like a mirror to the driver; road preview must not be mirrored.
        previewView.setScaleX(live && cams == PipelineController.Cameras.DRIVER ? -1f : 1f);
    }

    private void cycleCameras() {
        PipelineController.Cameras[] all = PipelineController.Cameras.values();
        PipelineController.Cameras next = all[(controller.cameras().ordinal() + 1) % all.length];
        controller.setCameras(next);
        prefs.edit().putString(PREF_CAMERAS, next.name()).apply();
        ZLog.i(TAG, "cameras switched to " + next);
        updateButtons();
        startForCurrentMode();
    }

    private void toggleMode() {
        ZholSafeConfig.OperatingMode next = controller.mode() == ZholSafeConfig.OperatingMode.LIVE
                ? ZholSafeConfig.OperatingMode.DEMO : ZholSafeConfig.OperatingMode.LIVE;
        controller.setMode(next);
        ZLog.i(TAG, "mode switched to " + next);
        updateButtons();
        startForCurrentMode();
    }

    private void startForCurrentMode() {
        if (controller.isRunning()) {
            return;
        }
        applyPreviewLayout();
        boolean both = controller.mode() == ZholSafeConfig.OperatingMode.LIVE
                && controller.cameras() == PipelineController.Cameras.BOTH;
        cameraGroup = both ? new ConcurrentCameraGroup(this) : null;
        if (controller.mode() == ZholSafeConfig.OperatingMode.DEMO) {
            controller.start();
            return;
        }
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
        updateButtons();
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void updateButtons() {
        boolean live = controller.mode() == ZholSafeConfig.OperatingMode.LIVE;
        modeButton.setText(live ? R.string.action_switch_to_demo : R.string.action_switch_to_live);
        retryButton.setVisibility(live && !hasCameraPermission() ? View.VISIBLE : View.GONE);
        switch (controller.cameras()) {
            case DRIVER: camerasButton.setText(R.string.action_cameras_driver); break;
            case ROAD: camerasButton.setText(R.string.action_cameras_road); break;
            case BOTH:
            default: camerasButton.setText(R.string.action_cameras_both); break;
        }
        camerasButton.setEnabled(live);
        muteButton.setText(alerts.isMuted() ? R.string.action_unmute : R.string.action_mute);
    }
}
