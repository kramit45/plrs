package com.plrs.application.recommendation;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.interaction.InteractionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Counts {@code COMPLETE} + {@code LIKE} events per candidate content
 * over the last 30 days and normalises to {@code [0, 1]}, dividing by
 * the highest count in the same window. Backs the FR-30 popularity
 * fallback recommender — the simplest baseline a recommender should
 * beat (the offline harness in step 122 will track exactly that).
 *
 * <p>Empty windows are normalised by treating {@code max == 0} as
 * {@code max == 1}, so every candidate scores 0 rather than producing
 * a divide-by-zero.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}
 * so the bean is not created for the no-DB smoke test.
 *
 * <p>Traces to: FR-30 (popularity fallback).
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class PopularityScorer {

    /** Look-back window for COMPLETE + LIKE counts. */
    public static final Duration WINDOW = Duration.ofDays(30);

    private final InteractionRepository interactionRepository;
    private final Clock clock;

    public PopularityScorer(InteractionRepository interactionRepository, Clock clock) {
        this.interactionRepository = interactionRepository;
        this.clock = clock;
    }

    /**
     * Returns a map of {@code candidateId → score in [0, 1]}. Every
     * input id is present in the output; candidates with zero events
     * map to {@code 0.0}.
     */
    public Map<ContentId, Double> score(Collection<ContentId> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        Instant since = Instant.now(clock).minus(WINDOW);
        Map<ContentId, Long> counts =
                interactionRepository.countByContentSince(candidates, since);
        long max = 0L;
        for (long v : counts.values()) {
            if (v > max) {
                max = v;
            }
        }
        if (max == 0L) {
            max = 1L; // Empty window — every candidate ends up at 0.0.
        }
        Map<ContentId, Double> out = new HashMap<>(candidates.size());
        for (ContentId c : candidates) {
            long count = counts.getOrDefault(c, 0L);
            out.put(c, (double) count / (double) max);
        }
        return out;
    }
}
