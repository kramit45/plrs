package com.plrs.infrastructure.recommendation;

import com.plrs.application.recommendation.CfScorer;
import com.plrs.application.recommendation.MlServiceClient;
import com.plrs.application.recommendation.SimNeighbour;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.interaction.InteractionEvent;
import com.plrs.domain.interaction.InteractionRepository;
import com.plrs.domain.user.UserId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * NFR-11 graceful-degradation wrapper for CF scoring.
 *
 * <p>Routes through the Python ML service when {@link
 * MlServiceClient#isReachable} returns true; falls back to the
 * existing in-process {@link RedisCfScorer} otherwise. Any exception
 * during the ML path also drops to the fallback so the recommender
 * never fails outright.
 *
 * <p>Marked {@link Primary} so callers wired to the {@code CfScorer}
 * port pick this up by default; {@link RedisCfScorer} stays
 * injectable as the concrete fallback type.
 */
@Component
@Primary
@ConditionalOnProperty(
        name = {"spring.datasource.url", "spring.data.redis.host", "plrs.ml.base-url"})
public class CompositeCfScorer implements CfScorer {

    private static final Logger log = LoggerFactory.getLogger(CompositeCfScorer.class);

    private final MlServiceClient ml;
    private final RedisCfScorer fallback;
    private final InteractionRepository interactionRepository;

    public CompositeCfScorer(
            MlServiceClient ml,
            RedisCfScorer fallback,
            InteractionRepository interactionRepository) {
        this.ml = ml;
        this.fallback = fallback;
        this.interactionRepository = interactionRepository;
    }

    @Override
    public Map<ContentId, Double> score(UserId userId, Set<ContentId> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        if (!ml.isReachable()) {
            return fallback.score(userId, candidates);
        }
        try {
            return scoreFromMl(userId, candidates);
        } catch (Exception e) {
            log.warn(
                    "CompositeCfScorer: ML call failed mid-flight; falling back",
                    e);
            return fallback.score(userId, candidates);
        }
    }

    private Map<ContentId, Double> scoreFromMl(
            UserId userId, Set<ContentId> candidates) {
        List<InteractionEvent> history =
                interactionRepository.findRecentPositives(
                        userId,
                        RedisCfScorer.HISTORY_DAYS,
                        RedisCfScorer.HISTORY_LIMIT);
        if (history.isEmpty()) {
            return zeroFor(candidates);
        }
        Set<ContentId> historyIds = new HashSet<>(history.size());
        for (InteractionEvent e : history) {
            historyIds.add(e.contentId());
        }

        Map<ContentId, Double> raw = new HashMap<>(candidates.size());
        for (ContentId candidate : candidates) {
            if (historyIds.contains(candidate)) {
                // Don't re-recommend an item the user has already engaged.
                raw.put(candidate, 0.0);
                continue;
            }
            List<SimNeighbour> slab = ml.cfSimilar(candidate, 50);
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

        return normalise(raw);
    }

    private static Map<ContentId, Double> normalise(Map<ContentId, Double> raw) {
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

    private static Map<ContentId, Double> zeroFor(Set<ContentId> candidates) {
        Map<ContentId, Double> out = new HashMap<>(candidates.size());
        for (ContentId c : candidates) {
            out.put(c, 0.0);
        }
        return out;
    }
}
