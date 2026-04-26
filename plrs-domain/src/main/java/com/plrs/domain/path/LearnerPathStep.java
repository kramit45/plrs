package com.plrs.domain.path;

import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.content.ContentId;
import java.time.Instant;
import java.util.Optional;

/**
 * One ordered slot inside a {@link LearnerPath}: a piece of content the
 * learner is being asked to work through, plus its lifecycle state and
 * the planner's per-slot reason.
 *
 * <p>{@code addedAsReview} flags steps that the planner inserted because
 * a previously-mastered topic appears to have decayed (review pass) —
 * distinct from steps on the natural prerequisite chain so analytics
 * can split "new learning" from "revisiting".
 *
 * <p>{@code reasonInPath} is the human-readable explanation the UI
 * shows on hover ("Prerequisite for target topic", "Target-topic item",
 * etc.). Capped at 200 chars to match the column width in V18.
 *
 * <p>Traces to: §3.c.1.4 (learner_path_steps), FR-31.
 */
public record LearnerPathStep(
        int stepOrder,
        ContentId contentId,
        StepStatus status,
        boolean addedAsReview,
        String reasonInPath,
        Optional<Instant> startedAt,
        Optional<Instant> completedAt) {

    /** Maximum length of the reason string (matches schema column width). */
    public static final int MAX_REASON_LENGTH = 200;

    public LearnerPathStep {
        if (stepOrder < 1) {
            throw new DomainValidationException(
                    "LearnerPathStep stepOrder must be >= 1, got " + stepOrder);
        }
        if (contentId == null) {
            throw new DomainValidationException("LearnerPathStep contentId must not be null");
        }
        if (status == null) {
            throw new DomainValidationException("LearnerPathStep status must not be null");
        }
        if (reasonInPath == null) {
            throw new DomainValidationException("LearnerPathStep reasonInPath must not be null");
        }
        if (reasonInPath.isEmpty() || reasonInPath.length() > MAX_REASON_LENGTH) {
            throw new DomainValidationException(
                    "LearnerPathStep reasonInPath length must be in [1, "
                            + MAX_REASON_LENGTH
                            + "], got "
                            + reasonInPath.length());
        }
        if (startedAt == null) {
            throw new DomainValidationException("LearnerPathStep startedAt must not be null");
        }
        if (completedAt == null) {
            throw new DomainValidationException("LearnerPathStep completedAt must not be null");
        }
    }

    /** Convenience: starts a fresh PENDING step with no timestamps. */
    public static LearnerPathStep pending(
            int stepOrder, ContentId contentId, boolean addedAsReview, String reasonInPath) {
        return new LearnerPathStep(
                stepOrder,
                contentId,
                StepStatus.PENDING,
                addedAsReview,
                reasonInPath,
                Optional.empty(),
                Optional.empty());
    }

    /** Returns a copy marked DONE at {@code at}, with startedAt populated if absent. */
    public LearnerPathStep markDone(Instant at) {
        if (at == null) {
            throw new DomainValidationException("markDone instant must not be null");
        }
        Optional<Instant> started = startedAt.isPresent() ? startedAt : Optional.of(at);
        return new LearnerPathStep(
                stepOrder,
                contentId,
                StepStatus.DONE,
                addedAsReview,
                reasonInPath,
                started,
                Optional.of(at));
    }
}
