package kz.zholsafe.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;

/** Foreground-only fused-location adapter. Permission denial produces no fix, never a crash. */
public final class AndroidLocationProvider implements LocationProvider, AutoCloseable {
    private final Context context;
    private final FusedLocationProviderClient client;
    private final AtomicReference<LocationFix> latest = new AtomicReference<>();
    private final LocationCallback callback = new LocationCallback() {
        @Override public void onLocationResult(LocationResult result) {
            Location location = result.getLastLocation();
            if (location == null) return;
            try { latest.set(toFix(location)); } catch (IllegalArgumentException ignoredInvalidPlatformFix) {
                latest.set(null);
            }
        }
    };

    public AndroidLocationProvider(Context context) {
        this.context = context.getApplicationContext();
        this.client = LocationServices.getFusedLocationProviderClient(this.context);
    }

    public boolean start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) { latest.set(null); return false; }
        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                .setMinUpdateIntervalMillis(500L).build();
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper());
            return true;
        } catch (SecurityException permissionRevokedDuringRequest) {
            latest.set(null);
            return false;
        }
    }

    @Override public Optional<LocationFix> latestFix() { return Optional.ofNullable(latest.get()); }

    @Override public void close() { client.removeLocationUpdates(callback); latest.set(null); }

    private static LocationFix toFix(Location value) {
        double accuracy = value.hasAccuracy() ? value.getAccuracy() : 0d;
        LocationQuality quality = accuracy <= 20d ? LocationQuality.PRECISE : LocationQuality.APPROXIMATE;
        return new LocationFix(value.getLatitude(), value.getLongitude(),
                Instant.ofEpochMilli(value.getTime()), value.getElapsedRealtimeNanos(), accuracy,
                value.hasBearing() ? OptionalDouble.of(value.getBearing()) : OptionalDouble.empty(),
                value.hasSpeed() ? OptionalDouble.of(value.getSpeed()) : OptionalDouble.empty(), quality);
    }
}
