package kz.zholsafe.server.websocket;

import kz.zholsafe.server.config.ZholNetProperties;
import kz.zholsafe.server.hazard.HazardEventResponse;
import kz.zholsafe.server.hazard.HazardSeverity;
import kz.zholsafe.server.hazard.HazardType;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StompHazardNotificationPublisherTest {
    @Test
    void acceptedHazardPublishesCompactMetadataToConfiguredTopic() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        ZholNetProperties properties = new ZholNetProperties(Duration.ofMinutes(30),
                Duration.ofMinutes(5), Duration.ofSeconds(8), 12d, 10_000d, 100,
                "/ws/hazards", "/topic/hazards");
        HazardEventResponse event = new HazardEventResponse("e-1", 1, HazardType.HORSE,
                HazardSeverity.WARNING, 43.24, 76.91, Instant.EPOCH, Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1800), 0.9f, null, null, null, 1, false);

        new StompHazardNotificationPublisher(messaging, properties).publish(event);

        verify(messaging).convertAndSend("/topic/hazards", event);
    }
}
