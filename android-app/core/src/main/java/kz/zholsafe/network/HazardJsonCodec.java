package kz.zholsafe.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Explicit JSON boundary matching the Stage 5 schema and response DTO. */
public final class HazardJsonCodec {
    public String encode(NetworkHazardEvent event) {
        JsonObject json = new JsonObject();
        json.addProperty("eventId", event.eventId());
        json.addProperty("schemaVersion", event.schemaVersion());
        json.addProperty("vehicleId", event.vehicleId());
        json.addProperty("hazardType", event.hazardType().name());
        json.addProperty("severity", event.severity().name());
        json.addProperty("confidence", event.confidence());
        json.addProperty("risk", event.risk());
        json.addProperty("latitude", event.latitude());
        json.addProperty("longitude", event.longitude());
        json.addProperty("timestamp", event.timestamp().toString());
        json.addProperty("status", event.status());
        json.add("evidenceReference", com.google.gson.JsonNull.INSTANCE);
        optional(json, "headingDegrees", event.headingDegrees());
        optional(json, "approximateDistanceMeters", event.approximateDistanceMeters());
        optional(json, "ttcSeconds", event.ttcSeconds());
        return json.toString();
    }

    public NearbyHazard decodeOne(String body) { return decode(JsonParser.parseString(body).getAsJsonObject()); }

    public List<NearbyHazard> decodeNearby(String body) {
        JsonArray array = JsonParser.parseString(body).getAsJsonArray();
        List<NearbyHazard> result = new ArrayList<>(array.size());
        for (JsonElement item : array) result.add(decode(item.getAsJsonObject()));
        return List.copyOf(result);
    }

    private static NearbyHazard decode(JsonObject j) {
        return new NearbyHazard(text(j, "eventId"), integer(j, "schemaVersion"),
                NetworkHazardType.valueOf(text(j, "hazardType")),
                NetworkSeverity.valueOf(text(j, "severity")), number(j, "latitude"),
                number(j, "longitude"), Instant.parse(text(j, "sourceTimestamp")),
                Instant.parse(text(j, "receivedTimestamp")), Instant.parse(text(j, "expiresAt")),
                (float) number(j, "confidence"), optionalFloat(j, "headingDegrees"),
                optionalFloat(j, "approximateDistanceMeters"), optionalFloat(j, "ttcSeconds"),
                integer(j, "reportCount"), j.get("deduplicated").getAsBoolean());
    }
    private static void optional(JsonObject j, String name, Float value) { if (value != null) j.addProperty(name, value); }
    private static String text(JsonObject j, String name) { return j.get(name).getAsString(); }
    private static int integer(JsonObject j, String name) { return j.get(name).getAsInt(); }
    private static double number(JsonObject j, String name) { return j.get(name).getAsDouble(); }
    private static Float optionalFloat(JsonObject j, String name) {
        return !j.has(name) || j.get(name).isJsonNull() ? null : j.get(name).getAsFloat();
    }
}
