package kz.zholsafe.server.hazard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HazardEventEntityMappingTest {
    @Test
    void persistenceMappingUsesHazardTableAndPostgisGeography() throws Exception {
        assertNotNull(HazardEventEntity.class.getAnnotation(Entity.class));
        assertEquals("hazard_event", HazardEventEntity.class.getAnnotation(Table.class).name());
        Column location = HazardEventEntity.class.getDeclaredField("location")
                .getAnnotation(Column.class);
        assertEquals("geography(Point,4326)", location.columnDefinition());
        assertEquals("expires_at", HazardEventEntity.class.getDeclaredField("expiresAt")
                .getAnnotation(Column.class).name());
    }
}
