package com.plrs.web.auth;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Successful-login response body. Carries both tokens, the identity and
 * roles of the authenticated user, and explicit expiry instants so
 * clients don't have to parse the JWT to know when to refresh.
 *
 * <p>{@code tokenType} is always {@code "Bearer"}; it is included so the
 * client can hand the access token straight to an HTTP library as a
 * pre-formed {@code Authorization} header pair.
 *
 * <p>Notable omissions: no {@code passwordHash}, no {@code refreshJti}.
 * The former has no business leaving the server; the latter is for the
 * server-side allow-list and leaking it would let an attacker who sniffs
 * one request preemptively revoke the session.
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        UUID userId,
        String email,
        Set<String> roles,
        Instant accessExpiresAt,
        Instant refreshExpiresAt) {}
