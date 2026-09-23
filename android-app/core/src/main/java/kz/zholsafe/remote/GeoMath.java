package kz.zholsafe.remote;

/** WGS84-sphere calculations suitable for bounded advisory distances. */
public final class GeoMath {
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;
    private GeoMath() {}

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1), phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1), dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        a = Math.max(0d, Math.min(1d, a));
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static double initialBearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1), phi2 = Math.toRadians(lat2);
        double lambda = Math.toRadians(lon2 - lon1);
        double y = Math.sin(lambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(lambda);
        return normalizeDegrees(Math.toDegrees(Math.atan2(y, x)));
    }

    public static double absoluteAngularDifference(double first, double second) {
        double difference = Math.abs(normalizeDegrees(first) - normalizeDegrees(second));
        return Math.min(difference, 360d - difference);
    }

    private static double normalizeDegrees(double value) {
        double normalized = value % 360d;
        return normalized < 0 ? normalized + 360d : normalized;
    }
}
