package com.plrs.application.user;

import com.plrs.domain.user.Email;
import com.plrs.domain.user.Role;
import com.plrs.domain.user.UserId;
import java.time.Instant;
import java.util.Set;

/**
 * Result of a successful login. Carries the identity and roles of the
 * authenticated user alongside both JWTs and their expiry instants so the
 * web layer can populate response bodies, {@code Set-Cookie} headers, or
 * whatever wire format the caller prefers without re-deriving anything.
 */
public record LoginResult(
        UserId userId,
        Email email,
        Set<Role> roles,
        String accessToken,
        String refreshToken,
        Instant accessExpiresAt,
        Instant refreshExpiresAt) {}
