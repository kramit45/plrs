package com.plrs.infrastructure.security.jwt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration binding for {@code plrs.jwt.*}. Records declare
 * binding via the canonical constructor in Spring Boot 3, which also lets
 * us normalise missing values and enforce the PEM-required-in-prod
 * invariant in one place.
 *
 * <p>Defaults applied by the compact constructor mirror §7: 2h access
 * token, 30d refresh token, issuer {@code plrs}, audience {@code plrs-web}.
 * When {@link #generateIfMissing} is {@code false} (production
 * expectation), both PEMs must be supplied; otherwise construction fails
 * at startup rather than silently falling back to an ephemeral keypair.
 *
 * <p>Traces to: §7 (JWT RS256, 2h access, 30d refresh).
 */
@ConfigurationProperties(prefix = "plrs.jwt")
public record JwtProperties(
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        String issuer,
        String audience,
        String privateKeyPem,
        String publicKeyPem,
        boolean generateIfMissing) {

    public JwtProperties {
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofHours(2);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(30);
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "plrs";
        }
        if (audience == null || audience.isBlank()) {
            audience = "plrs-web";
        }
        if (!generateIfMissing && (isBlank(privateKeyPem) || isBlank(publicKeyPem))) {
            throw new IllegalStateException(
                    "plrs.jwt: both private-key-pem and public-key-pem are required when"
                            + " generate-if-missing=false");
        }
    }

    public boolean hasKeyMaterial() {
        return !isBlank(privateKeyPem) && !isBlank(publicKeyPem);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
