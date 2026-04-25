package com.plrs.infrastructure.recommendation;

import com.plrs.application.recommendation.CbScorer;
import com.plrs.application.recommendation.MlServiceClient;
import com.plrs.application.recommendation.SimNeighbour;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.interaction.EventType;
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
 * NFR-11 graceful-degradation wrapper for CB scoring.
 *
 * <p>When the ML service is reachable, queries
 * {@link MlServiceClient#cbSimilar} per completed item in the user's
 * recent history and aggregates similarities to each candidate.
 * On any failure (or when {@link MlServiceClient#isReachable}
 * returns false) drops to the in-process {@link RedisCbScorer} which
 * does the equivalent computation against the cached TF-IDF matrix.
 *
 * <p>Marked {@link Primary} so callers wired to the {@code CbScorer}
 * port pick this up by default.
 */
@Component
@Primary
@ConditionalOnProperty(
        name = {"spring.datasource.url", "spring.data.redis.host", "plrs.ml.base-url"})
public class CompositeCbScorer implements CbScorer {

    private static final Logger log = LoggerFactory.getLogger(CompositeCbScorer.class);

    /** Top-K cap requested per ml.cbSimilar call. */
    static final int NEIGHBOURS_PER_QUERY = 50;

    private final MlServiceClient ml;
    private final RedisCbScorer fallback;
    private final InteractionRepository interactionRepository;

    public CompositeCbScorer(
            MlServiceClient ml,
            RedisCbScorer fallback,
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
                    "CompositeCbScorer: ML call failed mid-flight; falling back",
                    e);
            return fallback.score(userId, candidates);
        }
    }

    private Map<ContentId, Double> scoreFromMl(
            UserId userId, Set<ContentId> candidates) {
        List<InteractionEvent> completed =
                interactionRepository.findRecentByEventType(
                        userId, EventType.COMPLETE, RedisCbScorer.HISTORY_DAYS);
        if (completed.isEmpty()) {
            return zeroFor(candidates);
        }
        Set<ContentId> historyIds = new HashSet<>(completed.size());
        for (InteractionEvent e : completed) {
            historyIds.add(e.contentId());
        }

        // For each completed item, ask the ML service for its top-K
        // content-similar neighbours. Aggregate the similarity each
        // candidate receives across the user's whole history; divide
        // by history size to get the centroid-cosine equivalent.
        Map<ContentId, Double> sum = new HashMap<>(candidates.size());
        for (ContentId h : historyIds) {
            List<SimNeighbour> neighbours = ml.cbSimilar(h, NEIGHBOURS_PER_QUERY);
            if (neighbours == null || neighbours.isEmpty()) {
                continue;
            }
            for (SimNeighbour n : neighbours) {
                ContentId nid = ContentId.of(n.contentId());
                if (!candidates.contains(nid) || historyIds.contains(nid)) {
                    continue;
                }
                sum.merge(nid, n.similarity(), Double::sum);
            }
        }

        Map<ContentId, Double> out = new HashMap<>(candidates.size());
        int historyCount = historyIds.size();
        for (ContentId c : candidates) {
            if (historyIds.contains(c)) {
                out.put(c, 0.0);
                continue;
            }
            double aggregated = sum.getOrDefault(c, 0.0);
            // Average across the user's history so longer histories
            // don't artificially inflate scores.
            out.put(c, aggregated / historyCount);
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
