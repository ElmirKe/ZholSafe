package kz.zholsafe.server.websocket;

import kz.zholsafe.server.hazard.HazardEventResponse;

public interface HazardNotificationPublisher {
    void publish(HazardEventResponse event);
}
