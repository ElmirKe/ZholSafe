package kz.zholsafe.server.api;

/** Stable REST paths. Vehicle client and web map must use these constants' values. */
public final class ApiPaths {
    public static final String API_V1 = "/api/v1";
    public static final String HEALTH = API_V1 + "/health";
    public static final String HAZARDS = API_V1 + "/hazards";
    public static final String HAZARDS_NEARBY = HAZARDS + "/nearby";
    public static final String WS_HAZARDS = "/ws/hazards";

    private ApiPaths() { }
}
