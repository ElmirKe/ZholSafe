package kz.zholsafe.remote;

import kz.zholsafe.network.NetworkHazardType;

import java.util.Locale;

/** Concise Russian demo text; never presents remote TTC or a collision prediction. */
public final class RemoteHazardDisplayText {
    private RemoteHazardDisplayText() {}
    public static String render(RemoteHazardSnapshot snapshot) {
        if (snapshot.status() == RemoteHazardSnapshot.Status.NETWORK_UNAVAILABLE) {
            return "ZHOLNET\nСеть недоступна\nЛокальная безопасность работает";
        }
        if (snapshot.status() == RemoteHazardSnapshot.Status.LOCATION_UNAVAILABLE
                || snapshot.status() == RemoteHazardSnapshot.Status.LOCATION_STALE) {
            return "ZHOLNET\nGPS недоступен\nЛокальная безопасность работает";
        }
        if (snapshot.status() == RemoteHazardSnapshot.Status.STOPPED) return "ZHOLNET\nОжидание";
        if (snapshot.selected().isEmpty()) return "ZHOLNET\nНет удалённых предупреждений";
        RemoteHazardWarning warning = snapshot.selected().get();
        String position = warning.bearingRelation() == BearingRelation.AHEAD ? "Впереди: " : "Рядом: ";
        return "ZHOLNET\n" + position + type(warning.hazardType()) + "\n≈ "
                + distance(warning.distanceMeters()) + "\nПредупреждение от ZholNet";
    }
    public static String distance(double meters) {
        if (meters < 1_000) return Math.max(10, Math.round(meters / 10d) * 10) + " м";
        return String.format(Locale.ROOT, "%.1f км", meters / 1_000d);
    }
    private static String type(NetworkHazardType type) {
        return switch (type) {
            case HORSE -> "ЛОШАДЬ"; case DOG -> "СОБАКА"; case PERSON -> "ЧЕЛОВЕК";
            case COW -> "КОРОВА"; case SHEEP -> "ОВЦА"; case GOAT -> "КОЗА";
            case CAMEL -> "ВЕРБЛЮД"; case STOPPED_VEHICLE -> "ОСТАНОВИВШИЙСЯ АВТОМОБИЛЬ";
            case OBSTACLE -> "ПРЕПЯТСТВИЕ"; case OTHER, UNKNOWN -> "ОПАСНОСТЬ";
        };
    }
}
