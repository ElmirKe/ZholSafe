package kz.zholsafe.server.api;

import kz.zholsafe.server.config.ServerBeans;
import kz.zholsafe.server.hazard.HazardEventResponse;
import kz.zholsafe.server.hazard.HazardEventService;
import kz.zholsafe.server.hazard.HazardSeverity;
import kz.zholsafe.server.hazard.HazardType;
import kz.zholsafe.server.hazard.InvalidHazardRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HazardController.class)
@Import(ServerBeans.class)
class HazardControllerTest {
    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    @Autowired MockMvc mvc;
    @MockBean HazardEventService service;

    @Test
    void validEventReturnsCreatedCompactResponse() throws Exception {
        when(service.accept(any())).thenReturn(new HazardEventResponse("e-1", 1, HazardType.HORSE,
                HazardSeverity.WARNING, 43.24, 76.91, NOW.minusSeconds(2), NOW,
                NOW.plusSeconds(1800), 0.9f, null, null, null, 1, false));

        mvc.perform(post(ApiPaths.HAZARDS).contentType(MediaType.APPLICATION_JSON).content("""
                {"eventId":"e-1","vehicleId":"anon-rotating-token","hazardType":"HORSE",
                 "confidence":0.9,"risk":0.8,"latitude":43.24,"longitude":76.91,
                 "timestamp":"2026-09-23T11:59:58Z","status":"ACTIVE"}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("e-1"))
                .andExpect(jsonPath("$.hazardType").value("HORSE"))
                .andExpect(jsonPath("$.receivedTimestamp").exists());
    }

    @Test
    void malformedJsonReturnsStructuredErrorWithoutImplementationDetails() throws Exception {
        mvc.perform(post(ApiPaths.HAZARDS).contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value(ApiPaths.HAZARDS))
                .andExpect(jsonPath("$.message").value("Malformed request"));
    }

    @Test
    void invalidNearbyRadiusReturnsStructuredError() throws Exception {
        when(service.nearby(anyDouble(), anyDouble(), anyDouble(), any(), any(),
                any(), any())).thenThrow(new InvalidHazardRequestException(List.of("radius too large")));

        mvc.perform(get(ApiPaths.HAZARDS_NEARBY)
                        .param("latitude", "43.24")
                        .param("longitude", "76.91")
                        .param("radiusMeters", "999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details[0]").value("radius too large"));
    }
}
