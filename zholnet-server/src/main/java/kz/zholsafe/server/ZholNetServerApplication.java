package kz.zholsafe.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ZholNet Server entry point.
 *
 * <p>ZholNet is SUPPLEMENTARY: vehicles never depend on this server for local hazard warnings.
 * Stage 5 persists compact anonymous hazard metadata in PostgreSQL/PostGIS and exposes REST plus
 * broadcast WebSocket notifications. It never receives camera or driver-biometric data.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ZholNetServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZholNetServerApplication.class, args);
    }
}
