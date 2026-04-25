package com.plrs.domain.recommendation;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.user.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Domain port for {@link Recommendation} persistence. The adapter
 * (step 105) writes to {@code plrs_ops.recommendations} (V14).
 *
 * <p>Method shape:
 *
 * <ul>
 *   <li>{@link #saveAll} batches a freshly-served recommendation set
 *       (typically the top-N for one user, one timestamp) so the entire
 *       slate commits atomically — partial saves would corrupt rank
 *       interpretation.
 *   <li>{@link #find} loads one row by the natural composite key.
 *   <li>{@link #recordClick} flips the {@code clicked_at} timestamp on
 *       a single served recommendation. Adapter implements this as a
 *       targeted UPDATE rather than load+save so concurrent clicks on
 *       different rows don't collide on optimistic locking.
 *   <li>{@link #findRecent} returns the most recent {@code limit}
 *       recommendations served to a user, ordered by {@code created_at}
 *       DESC. Backs the dashboard's served-history view (Iter 3).
 * </ul>
 *
 * <p>No delete / cleanup method — retention of recommendations is an
 * operational concern handled by a separate scheduled prune (Iter 4).
 *
 * <p>Traces to: §3.a (domain-owned port), §3.c.1.4 (recommendations
 * schema), FR-26/27 (ranking + serve), FR-29 (audit served set).
 */
public interface RecommendationRepository {

    /** Batch-saves a set of recommendations as one transactional write. */
    void saveAll(List<Recommendation> recs);

    /** Loads one recommendation by its natural composite key. */
    Optional<Recommendation> find(UserId userId, ContentId contentId, Instant createdAt);

    /**
     * Stamps {@code clicked_at} on the row keyed by
     * {@code (userId, contentId, createdAt)}. No-op if the row is
     * already clicked (the adapter takes the earliest click, matching
     * the aggregate's idempotency).
     */
    void recordClick(UserId userId, ContentId contentId, Instant createdAt, Instant clickedAt);

    /**
     * Returns the most recent {@code limit} recommendations served to
     * {@code userId}, ordered by {@code created_at} DESC.
     */
    List<Recommendation> findRecent(UserId userId, int limit);
}
