package com.plrs.domain.path;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle states of a {@link LearnerPath}. The active subset
 * ({@link #NOT_STARTED}, {@link #IN_PROGRESS}, {@link #PAUSED},
 * {@link #REVIEW_PENDING}) is the one the database's partial unique
 * index on {@code (user_id, target_topic_id)} guards (V18,
 * §3.b.4.3) — at most one path per learner+target may sit in any of
 * these states.
 *
 * <p>{@link #COMPLETED}, {@link #ABANDONED}, and {@link #SUPERSEDED}
 * are terminal — they fall outside the partial unique window so a
 * learner can re-plan a target topic without dropping history.
 *
 * <p>Traces to: §3.c.1.4 (learner_paths.status enum), §3.b.4.3 (one
 * active path invariant), FR-31..FR-34.
 */
public enum LearnerPathStatus {
    NOT_STARTED,
    IN_PROGRESS,
    PAUSED,
    REVIEW_PENDING,
    COMPLETED,
    ABANDONED,
    SUPERSEDED;

    private static final Set<LearnerPathStatus> ACTIVE =
            EnumSet.of(NOT_STARTED, IN_PROGRESS, PAUSED, REVIEW_PENDING);

    /**
     * @return true if this status occupies the "active" window — the
     *     partial unique window in the database.
     */
    public boolean isActive() {
        return ACTIVE.contains(this);
    }
}
