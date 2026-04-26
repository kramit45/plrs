package com.plrs.application.recommendation;

import com.plrs.domain.content.ContentId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Greedy Maximal-Marginal-Relevance reranker (FR-28). Takes a list of
 * candidates ordered by relevance and re-orders them so each next
 * pick balances relevance against dissimilarity to what's already
 * been chosen.
 *
 * <p>Uses the canonical Carbonell-Goldstein formula with
 * {@code λ_mmr = 0.30} as the relevance weight:
 *
 * <pre>{@code
 * mmr(c) = LAMBDA_MMR * relevance(c)
 *        - (1 - LAMBDA_MMR) * max_{s ∈ selected} sim(c, s)
 * }</pre>
 *
 * <p>The first slot is the highest-relevance item (MMR is undefined
 * with no selected set). Subsequent slots scan the remaining pool and
 * pick whichever candidate maximises the expression above. Pool is
 * truncated at {@link #DEFAULT_POOL_SIZE} to bound the
 * O(k · pool · selected) cosine call count.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}
 * so the bean only loads alongside the rest of the recommender stack.
 *
 * <p>Traces to: §3.c.8 (MmrReranker), FR-28.
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class MmrReranker {

    /** Relevance weight; spec FR-28 fixes this at 0.30 (diversity-heavy). */
    public static final double LAMBDA_MMR = 0.30;

    /** Cap on how many of the top-relevance items the reranker considers. */
    public static final int DEFAULT_POOL_SIZE = 50;

    private final ContentSimilarity similarity;
    /** Optional FR-40 tunable; falls back to {@link #LAMBDA_MMR} when absent. */
    private final org.springframework.beans.factory.ObjectProvider<
                    com.plrs.application.admin.ConfigParamService>
            configProvider;

    public MmrReranker(
            ContentSimilarity similarity,
            org.springframework.beans.factory.ObjectProvider<
                            com.plrs.application.admin.ConfigParamService>
                    configProvider) {
        this.similarity = similarity;
        this.configProvider = configProvider;
    }

    private double lambdaMmr() {
        com.plrs.application.admin.ConfigParamService svc =
                configProvider == null ? null : configProvider.getIfAvailable();
        if (svc == null) {
            return LAMBDA_MMR;
        }
        java.util.OptionalDouble cfg = svc.getDouble("rec.lambda_mmr");
        return cfg.isPresent() ? cfg.getAsDouble() : LAMBDA_MMR;
    }

    /**
     * Reranks the input list to favour diversity. Returns at most
     * {@code k} ids in MMR order. An empty input or a non-positive
     * {@code k} returns an empty list.
     */
    public List<ContentId> rerank(
            List<ContentId> orderedByRelevance,
            Map<ContentId, Double> relevance,
            int k) {
        if (orderedByRelevance == null || orderedByRelevance.isEmpty() || k <= 0) {
            return List.of();
        }
        int target = Math.min(k, orderedByRelevance.size());
        int poolEnd =
                Math.min(
                        orderedByRelevance.size(),
                        Math.max(target, DEFAULT_POOL_SIZE));
        List<ContentId> pool = orderedByRelevance.subList(0, poolEnd);

        List<ContentId> selected = new ArrayList<>(target);
        LinkedHashSet<ContentId> remaining = new LinkedHashSet<>(pool);

        // Slot 1: top relevance.
        ContentId first = pool.get(0);
        selected.add(first);
        remaining.remove(first);

        while (selected.size() < target && !remaining.isEmpty()) {
            ContentId best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (ContentId c : remaining) {
                double rel = relevance == null ? 0.0 : relevance.getOrDefault(c, 0.0);
                double maxSim = 0.0;
                for (ContentId s : selected) {
                    double sim = similarity.cosine(c, s);
                    if (sim > maxSim) {
                        maxSim = sim;
                    }
                }
                double lm = lambdaMmr();
                double mmr = lm * rel - (1.0 - lm) * maxSim;
                if (mmr > bestScore) {
                    bestScore = mmr;
                    best = c;
                }
            }
            selected.add(best);
            remaining.remove(best);
        }
        return selected;
    }
}
