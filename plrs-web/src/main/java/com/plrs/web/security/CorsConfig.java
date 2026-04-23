package com.plrs.web.security;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS wiring for the {@code /api/**} surface. The web (Thymeleaf) chain
 * does not need CORS — those routes are same-origin by construction.
 *
 * <p>Allowed origins are environment-driven via
 * {@code plrs.cors.allowed-origins}:
 *
 * <ul>
 *   <li>Default (dev): the value from {@code application.yml} —
 *       {@code http://localhost:8080}.
 *   <li>Prod: sourced from the {@code PLRS_ALLOWED_ORIGINS} environment
 *       variable; {@code application-prod.yml} does not ship a fallback,
 *       so starting the prod profile without the variable fails fast at
 *       context load rather than silently opening the API to wildcards.
 * </ul>
 *
 * <p>The surface stays narrow on purpose: only the HTTP methods we
 * actually expose, only the request headers the client needs to send
 * ({@code Authorization} for JWT, {@code Content-Type} for JSON bodies,
 * {@code X-Request-Id} for correlation echo), and only the response
 * headers that a browser client genuinely needs to read. {@code
 * allowCredentials=true} because the JWT chain can receive requests
 * that carry cookies; {@code maxAge=1h} limits preflight chatter.
 *
 * <p>Traces to: §7 (security controls — CORS).
 */
@Configuration
public class CorsConfig {

    @Bean
    @Primary
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${plrs.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(allowedOrigins);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
        c.setExposedHeaders(List.of("X-Request-Id", "Location"));
        c.setAllowCredentials(true);
        c.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", c);
        return source;
    }
}
