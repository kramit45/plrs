package com.plrs.infrastructure.cache;

import com.plrs.application.cache.TopNCache;
import com.plrs.domain.user.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed adapter for {@link TopNCache}. The per-user top-N
 * recommendation list lives at {@code rec:topN:{userUuid}} (Iter 3
 * recommender writes it; Iter 2 only invalidates).
 *
 * <p>{@link #invalidate} is best-effort: any Redis exception is logged
 * and swallowed so it cannot fail the surrounding {@code @Transactional}
 * commit. The {@code user_skills_version} bump performed in the same
 * transaction (TX-01, step 90) is the authoritative invariant — a
 * stale cache combined with a fresh version still yields a correct
 * read because consumers detect the version mismatch and recompute
 * (§2.e.2.3.3).
 *
 * <p>Traces to: §2.e.2.3.3 (cache version invariant), TX-04
 * (post-commit cache invalidation).
 */
@Component
public class RedisTopNCache implements TopNCache {

    static final String KEY_PREFIX = "rec:topN:";

    private static final Logger log = LoggerFactory.getLogger(RedisTopNCache.class);

    private final StringRedisTemplate redis;

    public RedisTopNCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void invalidate(UserId userId) {
        try {
            redis.delete(KEY_PREFIX + userId.value());
        } catch (Exception e) {
            log.warn(
                    "Redis invalidate failed for user {}; relying on version-bust",
                    userId.value(),
                    e);
        }
    }
}
