package kz.zholsafe.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HazardEventContractTest {
    @Test void clientGeneratedJsonIsAcceptedByStage5Schema() throws Exception {
        HazardJsonCodec codec = new HazardJsonCodec();
        String generated = codec.encode(Stage6TestFixtures.event(Instant.parse("2026-09-23T12:00:00Z")));
        Path schemaPath = Path.of("..", "..", "tests", "contracts", "hazard-event.v1.schema.json")
                .toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(schemaPath), "shared Stage 5 schema must be present");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode schemaDocument = mapper.readTree(Files.readString(schemaPath));
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schemaDocument);
        Set<?> violations = schema.validate(mapper.readTree(generated));
        assertTrue(violations.isEmpty(), violations::toString);
    }

    @Test void wireJsonHasNoMediaIdentityOrDriverBiometricFields() {
        String json = new HazardJsonCodec().encode(Stage6TestFixtures.event(Instant.now()));
        for (String forbidden : new String[]{"video", "frame", "image", "audio", "driver",
                "face", "eye", "perclos", "phone", "licensePlate", "vin", "biometric"}) {
            assertFalse(json.toLowerCase().contains(forbidden.toLowerCase()), forbidden);
        }
    }
}
