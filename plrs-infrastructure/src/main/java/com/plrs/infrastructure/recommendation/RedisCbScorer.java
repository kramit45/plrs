package com.plrs.infrastructure.recommendation;

import com.plrs.application.recommendation.CbScorer;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * TF-IDF centroid {@link CbScorer}. Algorithm:
 *
 * <ol>
 *   <li>Load the user's last 30 days of {@code COMPLETE} interactions
 *       via {@link InteractionRepository#findRecentByEventType}.
 *   <li>Build the L2-normalised centroid of those items' TF-IDF rows
 *       through {@link TfIdfReader#centroid}.
 *   <li>Cosine the centroid with each candidate's TF-IDF row via
 *       {@link TfIdfReader#cosineWith}.
 * </ol>
 *
 * <p>Cold start: empty centroid (no completed items, or none indexed
 * by the latest TF-IDF build) → all zeros.
 *
 * <p>Gated by {@code @ConditionalOnProperty(name =
 * {spring.datasource.url, spring.data.redis.host})}.
 *
 * <p>Traces to: §3.c.7, §3.c.5.2.
 */
@Component
@ConditionalOnProperty(name = {"spring.datasource.url", "spring.data.redis.host"})
public class RedisCbScorer implements CbScorer {

    /** Look-back window for the user's completed history. */
    public static final int HISTORY_DAYS = 30;

    private final InteractionRepository interactionRepository;
    private final TfIdfReader tfIdfReader;

    public RedisCbScorer(
            InteractionRepository interactionRepository, TfIdfReader tfIdfReader) {
        this.interactionRepository = interactionRepository;
        this.tfIdfReader = tfIdfReader;
    }

    @Override
    public Map<ContentId, Double> score(UserId userId, Set<ContentId> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        List<InteractionEvent> completed =
                interactionRepository.findRecentByEventType(
                        userId, EventType.COMPLETE, HISTORY_DAYS);
        if (completed.isEmpty()) {
            return zeroFor(candidates);
        }
        Set<ContentId> historyIds = new HashSet<>(completed.size());
        for (InteractionEvent e : completed) {
            historyIds.add(e.contentId());
        }

        double[] centroid = tfIdfReader.centroid(historyIds);
        // Detect all-zero centroid (e.g. completed items absent from
        // the TF-IDF index): everyone scores 0.
        if (isAllZero(centroid)) {
            return zeroFor(candidates);
        }

        Map<ContentId, Double> out = new HashMap<>(candidates.size());
        for (ContentId candidate : candidates) {
            // Already-completed items: don't re-recommend; mirrors the
            // CfScorer's history guard.
            if (historyIds.contains(candidate)) {
                out.put(candidate, 0.0);
                continue;
            }
            out.put(candidate, tfIdfReader.cosineWith(candidate, centroid));
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

    private static boolean isAllZero(double[] v) {
        for (double d : v) {
            if (d != 0.0) {
                return false;
            }
        }
        return true;
    }
}
