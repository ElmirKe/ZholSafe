package kz.zholsafe.alert;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.tts.TextToSpeech;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import java.util.Locale;

import kz.zholsafe.logging.ZLog;

/**
 * Local, offline driver alerts: siren and beeps on the ALARM stream (audible even when media
 * volume is muted), vibration and text-to-speech in the app language (RU/KK/EN, Russian fallback).
 * No network, server or GPS involved.
 *
 * <p>Policy: an ALARM repeats the siren every {@value #SIREN_REPEAT_MS} ms while it lasts; a
 * CAUTION beeps once. The spoken phrase is said once per new situation (kind change), not on
 * every refresh, so the driver is warned without being drowned in speech.
 */
public final class AlertController {

    public enum Level { NONE, CAUTION, ALARM }

    private static final String TAG = "AlertController";
    static final long SIREN_REPEAT_MS = 1200;
    private static final long[] ALARM_VIBRATION = { 0, 400, 150, 400 };

    @Nullable private ToneGenerator tones;
    @Nullable private final Vibrator vibrator;
    private static final Locale RUSSIAN = new Locale("ru", "RU");

    @Nullable private TextToSpeech tts;
    private boolean ttsReady;
    private Locale speechLocale = RUSSIAN;
    private boolean primaryVoice;
    private boolean russianVoice;
    private boolean muted;
    private Level lastLevel = Level.NONE;
    @Nullable private Object lastKind;
    private long lastSirenAt;

    public AlertController(Context context) {
        Context app = context.getApplicationContext();
        try {
            tones = new ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME);
        } catch (RuntimeException e) {
            ZLog.w(TAG, "tone generator unavailable: " + e);
            tones = null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
        }
        tts = new TextToSpeech(app, status -> {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                ttsReady = true;
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                checkVoices();
            }
        });
    }

    /**
     * Language for spoken alerts (the app language). Kazakh voices are often not installed on
     * Android; then alerts are spoken with the Russian fallback phrase instead of staying silent.
     */
    public void setSpeechLocale(Locale locale) {
        speechLocale = locale;
        checkVoices();
    }

    /** True when alerts can be spoken in the app language itself (not via the Russian fallback). */
    public boolean speaksAppLanguage() {
        return !ttsReady || primaryVoice;
    }

    private void checkVoices() {
        if (!ttsReady || tts == null) {
            return;
        }
        primaryVoice = available(speechLocale);
        russianVoice = available(RUSSIAN);
        if (!primaryVoice && !russianVoice) {
            ZLog.w(TAG, "no TTS voice for " + speechLocale + " or Russian; alerts use sound and vibration only");
        }
    }

    private boolean available(Locale locale) {
        return tts != null && tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE;
    }

    /** Mute sound and speech (vibration and the red screen stay). */
    public void setMuted(boolean muted) {
        this.muted = muted;
        if (muted) {
            silence();
        }
    }

    public boolean isMuted() {
        return muted;
    }

    /**
     * Called on every UI refresh with the current alert.
     *
     * @param kind     identity of the situation (e.g. SLEEP vs ROAD); a change re-announces
     * @param speech   phrase in the app language to say once for this situation, or null
     * @param speechRu the same phrase in Russian, used when no voice exists for the app language
     */
    @MainThread
    public void update(Level level, Object kind, @Nullable String speech, @Nullable String speechRu) {
        long now = SystemClock.elapsedRealtime();
        boolean newSituation = level != lastLevel || (kind != null && !kind.equals(lastKind));

        if (level == Level.ALARM && (newSituation || now - lastSirenAt >= SIREN_REPEAT_MS)) {
            lastSirenAt = now;
            playTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1000);
            vibrate(ALARM_VIBRATION);
        } else if (level == Level.CAUTION && newSituation) {
            playTone(ToneGenerator.TONE_PROP_BEEP2, 300);
            vibrate(new long[] { 0, 200 });
        }
        if (newSituation) {
            if (level == Level.NONE) {
                stopSpeech();
            } else if (speech != null) {
                speak(speech, speechRu);
            }
        }
        lastLevel = level;
        lastKind = kind;
    }

    private void playTone(int tone, int durationMs) {
        if (muted || tones == null) {
            return;
        }
        try {
            tones.startTone(tone, durationMs);
        } catch (RuntimeException e) {
            ZLog.w(TAG, "tone failed: " + e);
        }
    }

    private void vibrate(long[] pattern) {
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
    }

    private void speak(String text, @Nullable String russian) {
        if (muted || !ttsReady || tts == null) {
            return;
        }
        if (primaryVoice) {
            tts.setLanguage(speechLocale);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zholsafe-alert");
        } else if (russianVoice && russian != null) {
            tts.setLanguage(RUSSIAN);
            tts.speak(russian, TextToSpeech.QUEUE_FLUSH, null, "zholsafe-alert");
        }
    }

    private void stopSpeech() {
        if (tts != null) {
            tts.stop();
        }
    }

    public void silence() {
        stopSpeech();
        if (tones != null) {
            tones.stopTone();
        }
        if (vibrator != null) {
            vibrator.cancel();
        }
    }

    public void release() {
        silence();
        if (tones != null) {
            tones.release();
            tones = null;
        }
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
    }
}
