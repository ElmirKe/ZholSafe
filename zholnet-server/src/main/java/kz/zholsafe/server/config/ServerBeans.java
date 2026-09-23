package kz.zholsafe.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ServerBeans {
    @Bean
    Clock serverClock() {
        return Clock.systemUTC();
    }
}
