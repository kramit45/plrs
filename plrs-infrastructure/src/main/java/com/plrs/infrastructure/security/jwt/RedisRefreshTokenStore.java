package com.plrs.infrastructure.security.jwt;

import com.plrs.application.security.RefreshTokenStore;
import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed implementation of {@link RefreshTokenStore}. Each active
 * refresh token lands at {@code jwt:refresh:{jti}} with the user id as
 * the value and a TTL equal to the remaining lifetime of the token, so
 * Redis itself evicts entries that outlive their JWT expiry without any
 * reaper process.
 *
 * <p>The value stored is the UUID string of the user id — which makes
 * {@link #isActive(String, UserId)} robust against an attacker who learns
 * a jti but not the subject it was issued for: the lookup requires both
 * to match. A single-value-per-key model is enough because jti is
 * already globally unique.
 *
 * <p>Traces to: §7 (refresh-token allow-list).
 */
@Component
public class RedisRefreshTokenStore implements RefreshTokenStore {

    static final String KEY_PREFIX = "jwt:refresh:";

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisRefreshTokenStore(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public void store(String jti, UserId userId, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(clock), expiresAt);
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(
                    "refresh token expiresAt (" + expiresAt + ") must be in the future");
        }
        redis.opsForValue().set(key(jti), userId.value().toString(), ttl);
    }

    @Override
    public boolean isActive(String jti, UserId userId) {
        String stored = redis.opsForValue().get(key(jti));
        return stored != null && stored.equals(userId.value().toString());
    }

    @Override
    public void revoke(String jti) {
        redis.delete(key(jti));
    }

    private static String key(String jti) {
        return KEY_PREFIX + jti;
    }
}
