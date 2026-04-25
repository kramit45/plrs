package com.plrs.infrastructure.recommendation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plrs.application.recommendation.CachedTopN;
import com.plrs.application.recommendation.TopNCacheStore;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.recommendation.Recommendation;
import com.plrs.domain.recommendation.RecommendationReason;
import com.plrs.domain.recommendation.RecommendationScore;
import com.plrs.domain.user.UserId;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed adapter for {@link TopNCacheStore}. The cache key
 * matches the Iter 2 invalidation port
 * ({@code com.plrs.infrastructure.cache.RedisTopNCache}), so the
 * step-91 post-commit hook on the quiz-attempt path correctly nukes
 * entries this store writes.
 *
 * <p>Serialisation: Jackson, with a deliberately flat shape — no
 * {@code @JsonValue} / Jackson modules on the framework-free domain
 * types, so {@code Recommendation} is projected into a flat DTO with
 * primitive fields, then re-hydrated through
 * {@link Recommendation#rehydrate} on read.
 *
 * <p>TTL: {@link #TTL} caps how long a stale entry can linger if the
 * version-bust signal fails to fire; the per-entry version stamp
 * remains the authoritative correctness lever.
 *
 * <p>Best-effort: any Redis exception is logged WARN and swallowed
 * (read returns {@link Optional#empty()}, write drops). The
 * {@code user_skills_version} bump in the quiz-submit transaction is
 * the strong guarantee.
 *
 * <p>Traces to: §2.e.2.3.2, §2.e.2.3.3.
 */
@Component
@ConditionalOnProperty(name = "spring.data.redis.host")
public class RedisTopNCacheStore implements TopNCacheStore {

    static final String KEY_PREFIX = "rec:topN:";

    /** Maximum lifetime of a cached entry. */
    public static final Duration TTL = Duration.ofMinutes(30);

    private static final Logger log = LoggerFactory.getLogger(RedisTopNCacheStore.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisTopNCacheStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<CachedTopN> get(UserId userId) {
        try {
            String raw = redis.opsForValue().get(key(userId));
            if (raw == null) {
                return Optional.empty();
            }
            return Optional.of(deserialise(raw));
        } catch (Exception e) {
            log.warn("Redis get failed for top-N cache, user {}", userId.value(), e);
            return Optional.empty();
        }
    }

    @Override
    public void put(UserId userId, CachedTopN entry) {
        try {
            redis.opsForValue().set(key(userId), serialise(entry), TTL);
        } catch (Exception e) {
            log.warn("Redis put failed for top-N cache, user {}", userId.value(), e);
        }
    }

    private static String key(UserId userId) {
        return KEY_PREFIX + userId.value();
    }

    private String serialise(CachedTopN entry) throws JsonProcessingException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", entry.version());
        root.put("computedAt", entry.computedAt().toString());
        List<Map<String, Object>> items = new ArrayList<>(entry.items().size());
        for (Recommendation r : entry.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", r.userId().value().toString());
            m.put("contentId", r.contentId().value());
            m.put("createdAt", r.createdAt().toString());
            m.put("score", r.score().toBigDecimal().toPlainString());
            m.put("rankPosition", r.rankPosition());
            m.put("reason", r.reason().text());
            m.put("modelVariant", r.modelVariant());
            r.clickedAt().ifPresent(ca -> m.put("clickedAt", ca.toString()));
            r.completedAt().ifPresent(ca -> m.put("completedAt", ca.toString()));
            items.add(m);
        }
        root.put("items", items);
        return objectMapper.writeValueAsString(root);
    }

    @SuppressWarnings("unchecked")
    private CachedTopN deserialise(String raw) throws JsonProcessingException {
        Map<String, Object> root =
                objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        long version = ((Number) root.get("version")).longValue();
        Instant computedAt = Instant.parse((String) root.get("computedAt"));
        List<Map<String, Object>> raws =
                (List<Map<String, Object>>) root.getOrDefault("items", List.of());
        List<Recommendation> items = new ArrayList<>(raws.size());
        for (Map<String, Object> m : raws) {
            items.add(
                    Recommendation.rehydrate(
                            UserId.of(UUID.fromString((String) m.get("userId"))),
                            ContentId.of(((Number) m.get("contentId")).longValue()),
                            Instant.parse((String) m.get("createdAt")),
                            RecommendationScore.of(
                                    new BigDecimal((String) m.get("score")).doubleValue()),
                            ((Number) m.get("rankPosition")).intValue(),
                            new RecommendationReason((String) m.get("reason")),
                            (String) m.get("modelVariant"),
                            Optional.ofNullable((String) m.get("clickedAt")).map(Instant::parse),
                            Optional.ofNullable((String) m.get("completedAt")).map(Instant::parse)));
        }
        return new CachedTopN(version, items, computedAt);
    }
}
