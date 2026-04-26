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
    /**
     * FR-40: optional dependency so tests that wire HybridRanker
     * directly don't have to provide a ConfigParamService. When
     * absent or value missing, falls back to {@link #LAMBDA_BLEND}.
     */
    private final org.springframework.beans.factory.ObjectProvider<
                    com.plrs.application.admin.ConfigParamService>
            configProvider;

    public HybridRanker(
            CfScorer cfScorer,
            CbScorer cbScorer,
            PopularityScorer popularityScorer,
            org.springframework.beans.factory.ObjectProvider<
                            com.plrs.application.admin.ConfigParamService>
                    configProvider) {
        this.cfScorer = cfScorer;
        this.cbScorer = cbScorer;
        this.popularityScorer = popularityScorer;
        this.configProvider = configProvider;
    }

    private double lambdaBlend() {
        com.plrs.application.admin.ConfigParamService svc =
                configProvider == null ? null : configProvider.getIfAvailable();
        if (svc == null) {
            return LAMBDA_BLEND;
        }
        java.util.OptionalDouble cfg = svc.getDouble("rec.lambda_blend");
        return cfg.isPresent() ? cfg.getAsDouble() : LAMBDA_BLEND;
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

        double lambda = lambdaBlend();
        Map<ContentId, Blended> out = new HashMap<>(candidates.size());
        for (ContentId c : candidates) {
            double cfS = cf.getOrDefault(c, 0.0);
            double cbS = cb.getOrDefault(c, 0.0);
            double popS = pop.getOrDefault(c, 0.0);
            double score = coldStart
                    ? popS
                    : lambda * cfS + (1.0 - lambda) * cbS;
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
