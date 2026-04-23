package com.plrs.application.security;

import com.plrs.domain.user.UserId;
import java.time.Instant;

/**
 * Application-owned port for the refresh-token allow-list. PLRS treats
 * refresh tokens as long-lived credentials whose validity can be revoked
 * server-side — checking a JWT signature alone is not enough, because a
 * stolen refresh token would remain valid until its natural expiry. Every
 * refresh flow verifies the JWT and then consults this store; a missing
 * or mismatched entry means the token was revoked (logout, password
 * change, explicit admin action) and must be rejected.
 *
 * <p>The port is intentionally narrow:
 *
 * <ul>
 *   <li>{@link #store(String, UserId, Instant)} inserts an entry keyed by
 *       jti, with a time-to-live matching the token's expiry — so the
 *       backing store never accumulates stale entries,
 *   <li>{@link #isActive(String, UserId)} returns true only when the jti
 *       is still present <em>and</em> maps to the expected user — the
 *       second check frustrates an attacker who somehow observes another
 *       user's jti,
 *   <li>{@link #revoke(String)} removes an entry idempotently.
 * </ul>
 *
 * <p>Access tokens are deliberately not allow-listed: their short TTL
 * (2h) keeps the window of abuse narrow enough that the per-request
 * Redis lookup is not worth the latency.
 *
 * <p>Traces to: §7 (refresh-token allow-list).
 */
public interface RefreshTokenStore {

    /**
     * Records an active refresh-token jti for the given user, expiring at
     * {@code expiresAt}.
     *
     * @throws IllegalArgumentException when {@code expiresAt} is at or
     *     before the current instant — a zero/negative TTL cannot be
     *     stored and likely indicates a programming error
     */
    void store(String jti, UserId userId, Instant expiresAt);

    /**
     * Returns {@code true} iff {@code jti} is still present in the store
     * <em>and</em> was stored for {@code userId}.
     */
    boolean isActive(String jti, UserId userId);

    /**
     * Removes a jti from the allow-list. Idempotent — revoking an unknown
     * or already-revoked jti is a no-op.
     */
    void revoke(String jti);
}
