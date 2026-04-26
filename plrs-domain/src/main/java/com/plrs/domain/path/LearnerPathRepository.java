package com.plrs.domain.path;

import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.util.List;
import java.util.Optional;

/**
 * Domain port for {@link LearnerPath} persistence. The adapter
 * (step 144) maps to {@code plrs_ops.learner_paths} +
 * {@code plrs_ops.learner_path_steps} (V18) and Jackson-serialises the
 * mastery snapshots into the JSONB columns.
 *
 * <p>Method shape:
 *
 * <ul>
 *   <li>{@link #save} persists a fresh draft and returns the hydrated
 *       aggregate stamped with the BIGSERIAL {@link PathId}. The
 *       database's partial unique index on (user_id, target_topic_id)
 *       enforces §3.b.4.3 — calls that race past the application-side
 *       supersede sequence will surface as a constraint violation.
 *   <li>{@link #update} writes the current state of a previously
 *       persisted aggregate; status transitions and step mutations
 *       round-trip through this method.
 *   <li>{@link #findById} loads an aggregate by id with all of its
 *       ordered steps.
 *   <li>{@link #findActiveByUserAndTarget} is the §3.b.4.3 lookup the
 *       Generate use case calls before persisting a new draft, so the
 *       previous active row can be SUPERSEDED first (TX-10 ordering).
 *   <li>{@link #findRecentByUser} powers the dashboard summary card.
 * </ul>
 *
 * <p>Traces to: §3.a (domain-owned port), §3.c.1.4, §3.b.4.3 (one
 * active path per (user, target)), FR-31..FR-34.
 */
public interface LearnerPathRepository {

    /**
     * Persists a fresh draft. The adapter assigns the BIGSERIAL
     * {@link PathId} and returns a {@link LearnerPath} with status
     * {@link LearnerPathStatus#NOT_STARTED}, the same step list, and
     * the same mastery start snapshot.
     */
    LearnerPath save(LearnerPathDraft draft);

    /** Persists the current state of an already-saved aggregate. */
    LearnerPath update(LearnerPath path);

    /** Loads an aggregate by id. */
    Optional<LearnerPath> findById(PathId id);

    /**
     * Returns the (at most one) active path for the given
     * {@code (user, target_topic)} pair. The §3.b.4.3 partial unique
     * index guarantees the at-most-one part — adapter-side this is a
     * single SELECT WHERE status IN (active subset) LIMIT 1.
     */
    Optional<LearnerPath> findActiveByUserAndTarget(UserId userId, TopicId targetTopicId);

    /**
     * Returns the most recent {@code limit} paths for a user across
     * all targets, ordered by {@code generated_at} DESC.
     */
    List<LearnerPath> findRecentByUser(UserId userId, int limit);
}
