package com.plrs.application.security;

import java.time.Instant;

/**
 * Bundle of credentials returned by {@link TokenService#issue}. The refresh
 * token's {@code jti} and expiry are surfaced separately because callers
 * that persist the refresh token into an allow-list (step 33) need both
 * without re-parsing the token.
 */
public record IssuedTokens(
        String accessToken, String refreshToken, String refreshJti, Instant refreshExpiresAt) {}
