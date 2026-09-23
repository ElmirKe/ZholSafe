package kz.zholsafe.server.websocket;

import kz.zholsafe.server.config.ZholNetProperties;
import kz.zholsafe.server.hazard.HazardEventResponse;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class StompHazardNotificationPublisher implements HazardNotificationPublisher {
    private final SimpMessagingTemplate messaging;
    private final ZholNetProperties properties;

    public StompHazardNotificationPublisher(SimpMessagingTemplate messaging,
                                            ZholNetProperties properties) {
        this.messaging = messaging;
        this.properties = properties;
    }

    @Override
    public void publish(HazardEventResponse event) {
        messaging.convertAndSend(properties.websocketTopic(), event);
    }
}
