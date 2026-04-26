package com.plrs.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link LoginRateLimitFilter} as a manual bean. Lives in its
 * own @Configuration (not inside {@link SecurityConfig}) so slice tests
 * that {@code @Import(SecurityConfig.class)} alone don't pull in the
 * filter and have to mock {@link IpRateLimiter}. The full
 * application context picks both up via component scan.
 *
 * <p>{@code @ConditionalOnBean(IpRateLimiter.class)} skips registering
 * the filter when the limiter isn't present (no-DB smoke tests where
 * IpRateLimiter is gated off).
 */
@Configuration
@ConditionalOnBean(IpRateLimiter.class)
public class LoginRateLimitConfig {

    @Bean
    public LoginRateLimitFilter loginRateLimitFilter(
            IpRateLimiter limiter, ObjectMapper objectMapper) {
        return new LoginRateLimitFilter(limiter, objectMapper);
    }
}
