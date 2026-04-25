package com.plrs.domain.interaction;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.user.UserId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Domain port for the implicit-feedback event stream. The adapter (step
 * 71, infrastructure module) writes to {@code plrs_ops.interactions}
 * (V8). Composite-PK violations from concurrent writers surface as
 * {@link org.springframework.dao.DataIntegrityViolationException}; the
 * use-case layer wraps them.
 *
 * <p>{@link #existsViewSince(UserId, ContentId, Instant)} backs the
 * FR-15 10-minute VIEW debounce: the use case asks "is there a VIEW for
 * this {@code (user, content)} pair newer than now − 10min?" before
 * issuing the INSERT. Returning a boolean (rather than fetching the
 * row) keeps the read minimal and lets the adapter use a {@code COUNT}
 * on the {@code idx_interactions_user_recent} index.
 *
 * <p>Traces to: §3.c.1.4 (interactions schema), FR-15 (VIEW debounce),
 * FR-16 (interaction events), FR-17 (rating).
 */
public interface InteractionRepository {

    /**
     * Persists the event. Composite-PK violations bubble as
     * {@link org.springframework.dao.DataIntegrityViolationException}.
     */
    void save(InteractionEvent event);

    /**
     * Returns {@code true} iff a VIEW event for {@code (userId, contentId)}
     * has {@code occurredAt > since}. Used by the FR-15 debounce check.
     */
    boolean existsViewSince(UserId userId, ContentId contentId, Instant since);

    /**
     * Returns the most recent COMPLETE events for {@code userId},
     * ordered by {@code occurredAt} DESC and capped at {@code limit}.
     * Backs the FR-35 dashboard "recent completions" card.
     */
    List<InteractionEvent> findRecentCompletes(UserId userId, int limit);

    /**
     * Returns interaction counts grouped by ISO year-week (e.g.
     * {@code "2026-17"}) for events whose {@code occurredAt} is at or
     * after {@code since}. Backs the FR-35 weekly activity sparkline;
     * the application service zero-fills missing weeks.
     */
    Map<String, Integer> countByIsoWeekSince(UserId userId, Instant since);

    /**
     * Counts {@code COMPLETE} + {@code LIKE} events per content, scoped
     * to the given candidate set and to events with
     * {@code occurredAt >= since}. Backs the FR-30 popularity fallback.
     * The returned map only contains entries for content that had at
     * least one matching event in the window — callers default missing
     * candidates to zero.
     */
    Map<ContentId, Long> countByContentSince(Collection<ContentId> candidates, Instant since);

    /**
     * Returns the user's most-recent positive interactions within the
     * last {@code days} days, capped at {@code limit} rows ordered by
     * {@code occurredAt} DESC. Positive = {@code COMPLETE}, {@code LIKE},
     * or {@code RATE} with {@code rating >= 4}. Backs the CfScorer
     * (step 113) — recent positives seed the sum-of-similarities
     * computation against the user's history.
     */
    List<InteractionEvent> findRecentPositives(UserId userId, int days, int limit);
}
