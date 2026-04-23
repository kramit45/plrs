package com.plrs.application.security;

import java.time.Instant;

/**
 * Bundle of credentials returned by {@link TokenService#issue}. Both
 * expiry instants are surfaced separately so callers that need them for
 * response bodies or for the refresh-token allow-list (step 33) do not
 * have to re-parse the JWTs.
 */
public record IssuedTokens(
        String accessToken,
        String refreshToken,
        String refreshJti,
        Instant accessExpiresAt,
        Instant refreshExpiresAt) {}
