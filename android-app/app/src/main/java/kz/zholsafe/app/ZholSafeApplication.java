package kz.zholsafe.app;

import android.app.Application;
import android.util.Log;

import kz.zholsafe.config.ZholSafeConfig;
import kz.zholsafe.logging.ZLog;

/**
 * Application entry point. Installs the Logcat sink for the core logging façade and builds the
 * root configuration. No pipeline is started here (Stage 1 adds the foreground service).
 */
public class ZholSafeApplication extends Application {

    private ZholSafeConfig config;

    @Override
    public void onCreate() {
        super.onCreate();
        ZLog.install(new LogcatSink());
        // Default to DEMO until a real model + camera path is verified in Stage 1/2.
        config = ZholSafeConfig.defaults(ZholSafeConfig.OperatingMode.DEMO);
        ZLog.i("App", "ZholSafe started in mode " + config.mode());
    }

    public ZholSafeConfig config() {
        return config;
    }

    /** Android Logcat implementation of {@link ZLog.Sink}. Never logs frame data. */
    static final class LogcatSink implements ZLog.Sink {
        @Override
        public void log(ZLog.Level level, String tag, String message, Throwable error) {
            String t = "ZholSafe/" + tag;
            switch (level) {
                case DEBUG: Log.d(t, message, error); break;
                case INFO: Log.i(t, message, error); break;
                case WARN: Log.w(t, message, error); break;
                case ERROR:
                default: Log.e(t, message, error); break;
            }
        }
    }
}
