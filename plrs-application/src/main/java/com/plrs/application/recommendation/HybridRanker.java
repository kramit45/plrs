package com.plrs.application.recommendation;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.user.UserId;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Replaces the step-114 50/50 popularity+CF interim with the proper
 * λ-weighted hybrid (FR-25). Default {@code λ_blend = 0.65} weights
 * CF over CB; popularity becomes the cold-start fallback when both
 * CF and CB are effectively absent.
 *
 * <p>Cold-start detection: averages over the candidate set. If
 * {@code avg(cf) < 0.05} AND {@code avg(cb) < 0.05}, the user has no
 * usable signal — emit popularity instead of the formula output.
 *
 * <p>Score formula (warm path): {@code λ * cf + (1 - λ) * cb}.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}.
 *
 * <p>Traces to: FR-25 (λ_blend = 0.65), FR-30 (cold-start fallback).
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class HybridRanker {

    /** Default blend weight per FR-25. Iter 3 hardcodes this. */
    public static final double LAMBDA_BLEND = 0.65;

    /**
     * Cold-start threshold per signal — if BOTH CF and CB averages
     * are below this, the popularity fallback takes over.
     */
    public static final double COLD_START_THRESHOLD = 0.05;

    private final CfScorer cfScorer;
    private final CbScorer cbScorer;
    private final PopularityScorer popularityScorer;

    public HybridRanker(
            CfScorer cfScorer,
            CbScorer cbScorer,
            PopularityScorer popularityScorer) {
        this.cfScorer = cfScorer;
        this.cbScorer = cbScorer;
        this.popularityScorer = popularityScorer;
    }

    public Map<ContentId, Blended> blend(UserId userId, Set<ContentId> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        Map<ContentId, Double> cf = cfScorer.score(userId, candidates);
        Map<ContentId, Double> cb = cbScorer.score(userId, candidates);
        Map<ContentId, Double> pop = popularityScorer.score(candidates);

        double cfAvg = average(cf, candidates);
        double cbAvg = average(cb, candidates);
        boolean coldStart =
                cfAvg < COLD_START_THRESHOLD && cbAvg < COLD_START_THRESHOLD;

        Map<ContentId, Blended> out = new HashMap<>(candidates.size());
        for (ContentId c : candidates) {
            double cfS = cf.getOrDefault(c, 0.0);
            double cbS = cb.getOrDefault(c, 0.0);
            double popS = pop.getOrDefault(c, 0.0);
            double score = coldStart
                    ? popS
                    : LAMBDA_BLEND * cfS + (1.0 - LAMBDA_BLEND) * cbS;
            out.put(c, new Blended(score, cfS, cbS, popS, coldStart));
        }
        return out;
    }

    private static double average(Map<ContentId, Double> scores, Set<ContentId> candidates) {
        if (candidates.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (ContentId c : candidates) {
            sum += scores.getOrDefault(c, 0.0);
        }
        return sum / candidates.size();
    }
}
