package com.plrs.infrastructure.recommendation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plrs.application.recommendation.ArtifactPayload;
import com.plrs.application.recommendation.ArtifactRepository;
import com.plrs.application.recommendation.CfScorer;
import com.plrs.application.recommendation.SimNeighbour;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.interaction.InteractionEvent;
import com.plrs.domain.interaction.InteractionRepository;
import com.plrs.domain.user.UserId;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed {@link CfScorer}. Algorithm:
 *
 * <ol>
 *   <li>Load the user's last 50 positive interactions in the last 90
 *       days via {@link InteractionRepository#findRecentPositives}.
 *   <li>For each candidate not already in the user's history:
 *       <ul>
 *         <li>Read the per-item similarity slab from Redis
 *             ({@code sim:item:{contentId}}).
 *         <li>If absent, fall back to {@code model_artifacts} (V15
 *             {@code SIM_SLAB} key); on hit, warm Redis with a 24h
 *             TTL so subsequent reads are fast.
 *         <li>Sum similarities of slab entries that overlap with the
 *             user's history; divide by the number of matches to get
 *             an average.
 *       </ul>
 *   <li>Normalise the resulting raw scores to {@code [0, 1]} by
 *       dividing by the highest score in the batch (so the most
 *       similar candidate gets 1.0).
 * </ol>
 *
 * <p>Best-effort throughout: any Redis or artifact failure logs WARN
 * and falls through to a 0.0 score for that candidate — the popularity
 * fallback in step 119's HybridRanker covers cold paths.
 *
 * <p>Gated by {@code @ConditionalOnProperty(spring.data.redis.host)}
 * so the bean only loads when Redis is configured.
 *
 * <p>Traces to: §3.c.7, §3.c.5.3.
 */
@Component
@ConditionalOnProperty(name = {"spring.datasource.url", "spring.data.redis.host"})
public class RedisCfScorer implements CfScorer {

    /** Look-back window for the user's positive history. */
    public static final int HISTORY_DAYS = 90;

    /** Cap on history rows; trims the average's denominator. */
    public static final int HISTORY_LIMIT = 50;

    static final String SIM_KEY_PREFIX = "sim:item:";
    static final Duration WARM_TTL = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(RedisCfScorer.class);
    private static final TypeReference<List<SimNeighbour>> SLAB_TYPE =
            new TypeReference<>() {};

    private final StringRedisTemplate redis;
    private final InteractionRepository interactionRepository;
    private final ArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;

    public RedisCfScorer(
            StringRedisTemplate redis,
            InteractionRepository interactionRepository,
            ArtifactRepository artifactRepository,
            ObjectMapper objectMapper) {
        this.redis = redis;
        this.interactionRepository = interactionRepository;
        this.artifactRepository = artifactRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<ContentId, Double> score(UserId userId, Set<ContentId> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        List<InteractionEvent> history =
                interactionRepository.findRecentPositives(userId, HISTORY_DAYS, HISTORY_LIMIT);
        if (history.isEmpty()) {
            // Cold-start user — no signal. Return zeros for every
            // candidate so the caller can still iterate.
            Map<ContentId, Double> zeros = new HashMap<>(candidates.size());
            for (ContentId c : candidates) {
                zeros.put(c, 0.0);
            }
            return zeros;
        }

        Set<ContentId> historyIds = new HashSet<>(history.size());
        for (InteractionEvent e : history) {
            historyIds.add(e.contentId());
        }

        Map<ContentId, Double> raw = new HashMap<>(candidates.size());
        for (ContentId candidate : candidates) {
            if (historyIds.contains(candidate)) {
                // The user already engaged with this item; the
                // recommender shouldn't re-recommend it.
                raw.put(candidate, 0.0);
                continue;
            }
            List<SimNeighbour> slab = loadSlab(candidate);
            if (slab == null || slab.isEmpty()) {
                raw.put(candidate, 0.0);
                continue;
            }
            double sum = 0.0;
            int matches = 0;
            for (SimNeighbour n : slab) {
                if (historyIds.contains(ContentId.of(n.contentId()))) {
                    sum += n.similarity();
                    matches++;
                }
            }
            raw.put(candidate, matches > 0 ? sum / matches : 0.0);
        }

        // Normalise raw scores so the top candidate maps to 1.0.
        double max = 0.0;
        for (double v : raw.values()) {
            if (v > max) {
                max = v;
            }
        }
        if (max <= 0.0) {
            return raw;
        }
        Map<ContentId, Double> out = new HashMap<>(raw.size());
        for (var e : raw.entrySet()) {
            out.put(e.getKey(), e.getValue() / max);
        }
        return out;
    }

    private List<SimNeighbour> loadSlab(ContentId candidate) {
        String key = SIM_KEY_PREFIX + candidate.value();
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                Optional<ArtifactPayload> artifact =
                        artifactRepository.find("SIM_SLAB", candidate.value().toString());
                if (artifact.isEmpty()) {
                    return null;
                }
                json = artifact.get().asString();
                // Warm Redis so the next read is hot.
                try {
                    redis.opsForValue().set(key, json, WARM_TTL);
                } catch (Exception warmEx) {
                    log.warn(
                            "RedisCfScorer: failed to warm Redis for item {}",
                            candidate.value(),
                            warmEx);
                }
            }
            return objectMapper.readValue(json, SLAB_TYPE);
        } catch (Exception e) {
            log.warn(
                    "RedisCfScorer: slab load failed for item {}", candidate.value(), e);
            return null;
        }
    }
}
