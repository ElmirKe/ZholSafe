package kz.zholsafe.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ZholNet Server entry point.
 *
 * <p>ZholNet is SUPPLEMENTARY: vehicles never depend on this server for local hazard warnings.
 * Stage 0 ships only a health endpoint, the hazard-event DTO contract and an in-memory
 * placeholder store so the API contract can be exercised. Persistence (PostgreSQL + PostGIS),
 * WebSocket notifications and geospatial queries arrive in Stage 5/6.
 */
@SpringBootApplication
public class ZholNetServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZholNetServerApplication.class, args);
    }
}
