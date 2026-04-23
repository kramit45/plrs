package com.plrs.infrastructure.security.jwt;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link JwtProperties} as a bean so the JWT infrastructure can
 * inject typed configuration, and supplies a default {@link Clock} bean
 * that token issuance and verification can depend on. The clock is
 * conditional so tests (and any later time-sensitive features) can
 * override it without duplicate-bean errors.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock jwtClock() {
        return Clock.systemUTC();
    }
}
