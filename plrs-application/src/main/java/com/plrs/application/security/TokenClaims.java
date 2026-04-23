package com.plrs.application.security;

import com.plrs.domain.user.Role;
import com.plrs.domain.user.UserId;
import java.time.Instant;
import java.util.Set;

/**
 * Narrow projection of the claims PLRS cares about after verifying a JWT.
 * Domain-typed so downstream services never handle raw strings for
 * identity or roles.
 */
public record TokenClaims(UserId subject, Set<Role> roles, String jti, Instant expiresAt) {}
