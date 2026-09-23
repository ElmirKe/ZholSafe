package kz.zholsafe.network;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/** Random app-scoped token persisted in private SharedPreferences; contains no hardware ID. */
public final class SharedPreferencesAnonymousSourceIdProvider implements AnonymousSourceIdProvider {
    private static final String FILE = "zholnet_privacy";
    private static final String KEY = "anonymous_source_id";
    private final SharedPreferences preferences;

    public SharedPreferencesAnonymousSourceIdProvider(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
    @Override public synchronized String current() {
        String value = preferences.getString(KEY, null);
        if (value == null) value = rotate();
        return value;
    }
    @Override public synchronized String rotate() {
        String value = "anon-" + UUID.randomUUID();
        preferences.edit().putString(KEY, value).apply();
        return value;
    }
}
