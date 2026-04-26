package com.plrs.domain.path;

import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pre-save form of a {@link LearnerPath}: everything the
 * {@code PathPlanner} produces, minus the BIGSERIAL id and the
 * generated-at timestamp that the persistence adapter stamps. The
 * repository's {@code save(LearnerPathDraft)} returns a hydrated
 * {@link LearnerPath}.
 *
 * <p>Compact-constructor invariants mirror what the persisted aggregate
 * also enforces, so tests that construct drafts directly never produce
 * something the database would reject.
 *
 * <p>Traces to: §3.c.1.4, FR-31.
 */
public record LearnerPathDraft(
        UserId userId,
        TopicId targetTopicId,
        List<LearnerPathStep> steps,
        Map<TopicId, MasteryScore> masteryStartSnapshot) {

    public LearnerPathDraft {
        if (userId == null) {
            throw new DomainValidationException("LearnerPathDraft userId must not be null");
        }
        if (targetTopicId == null) {
            throw new DomainValidationException(
                    "LearnerPathDraft targetTopicId must not be null");
        }
        if (steps == null) {
            throw new DomainValidationException("LearnerPathDraft steps must not be null");
        }
        if (masteryStartSnapshot == null) {
            throw new DomainValidationException(
                    "LearnerPathDraft masteryStartSnapshot must not be null");
        }
        // Defensive copies + duplicate-stepOrder check.
        Set<Integer> seen = new HashSet<>();
        for (LearnerPathStep s : steps) {
            if (!seen.add(s.stepOrder())) {
                throw new DomainValidationException(
                        "LearnerPathDraft steps must have unique stepOrder, duplicate "
                                + s.stepOrder());
            }
        }
        steps = List.copyOf(steps);
        masteryStartSnapshot = Map.copyOf(masteryStartSnapshot);
    }
}
