package com.plrs.domain.path;

import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Aggregate root for a learner's prerequisite-aware journey to a target
 * topic (FR-31). Holds an ordered, immutable list of {@link LearnerPathStep}s,
 * a {@link LearnerPathStatus} that walks the lifecycle
 * {@code NOT_STARTED → IN_PROGRESS → (PAUSED / REVIEW_PENDING) →
 * COMPLETED / ABANDONED / SUPERSEDED}, and pre/post mastery snapshots
 * so the offline harness can attribute mastery delta to a path.
 *
 * <p>Identity is captured by {@link #id} which is empty until the
 * persistence adapter has a BIGSERIAL key — {@link #newDraft} returns a
 * pre-save instance with an empty id, while
 * {@link #rehydrate} is the read-back factory the JPA mapper calls.
 * Equality is identity-based on {@code id} when present (two persisted
 * paths are equal iff their PathIds are equal); two pre-save instances
 * are never equal — they cannot have collided yet.
 *
 * <p>State transitions return a fresh instance rather than mutating the
 * receiver. Each transition validates the source status and throws
 * {@link DomainInvariantException} on illegal transitions; null arguments
 * raise the parent {@link DomainValidationException}.
 *
 * <p>Traces to: §3.c.1.4 (learner_paths schema), §3.b.4.3 (one active
 * path per user+target), FR-31..FR-34.
 */
public final class LearnerPath {

    private final Optional<PathId> id;
    private final UserId userId;
    private final TopicId targetTopicId;
    private final LearnerPathStatus status;
    private final Instant generatedAt;
    private final Optional<Instant> startedAt;
    private final Optional<Instant> pausedAt;
    private final Optional<Instant> completedAt;
    private final Optional<Instant> abandonedAt;
    private final Optional<Instant> supersededAt;
    private final Optional<PathId> supersededBy;
    private final List<LearnerPathStep> steps;
    private final Map<TopicId, MasteryScore> masteryStartSnapshot;
    private final Optional<Map<TopicId, MasteryScore>> masteryEndSnapshot;

    private LearnerPath(
            Optional<PathId> id,
            UserId userId,
            TopicId targetTopicId,
            LearnerPathStatus status,
            Instant generatedAt,
            Optional<Instant> startedAt,
            Optional<Instant> pausedAt,
            Optional<Instant> completedAt,
            Optional<Instant> abandonedAt,
            Optional<Instant> supersededAt,
            Optional<PathId> supersededBy,
            List<LearnerPathStep> steps,
            Map<TopicId, MasteryScore> masteryStartSnapshot,
            Optional<Map<TopicId, MasteryScore>> masteryEndSnapshot) {
        // Null guards on every field — Optional must be present (possibly empty), not null.
        if (id == null) {
            throw new DomainValidationException("LearnerPath id must not be null");
        }
        if (userId == null) {
            throw new DomainValidationException("LearnerPath userId must not be null");
        }
        if (targetTopicId == null) {
            throw new DomainValidationException("LearnerPath targetTopicId must not be null");
        }
        if (status == null) {
            throw new DomainValidationException("LearnerPath status must not be null");
        }
        if (generatedAt == null) {
            throw new DomainValidationException("LearnerPath generatedAt must not be null");
        }
        if (startedAt == null
                || pausedAt == null
                || completedAt == null
                || abandonedAt == null
                || supersededAt == null
                || supersededBy == null) {
            throw new DomainValidationException("LearnerPath Optional fields must not be null");
        }
        if (steps == null) {
            throw new DomainValidationException("LearnerPath steps must not be null");
        }
        if (masteryStartSnapshot == null) {
            throw new DomainValidationException(
                    "LearnerPath masteryStartSnapshot must not be null");
        }
        if (masteryEndSnapshot == null) {
            throw new DomainValidationException(
                    "LearnerPath masteryEndSnapshot must not be null");
        }
        Set<Integer> seenOrders = new HashSet<>();
        for (LearnerPathStep s : steps) {
            if (!seenOrders.add(s.stepOrder())) {
                throw new DomainInvariantException(
                        "LearnerPath steps must have unique stepOrder, duplicate "
                                + s.stepOrder());
            }
        }

        this.id = id;
        this.userId = userId;
        this.targetTopicId = targetTopicId;
        this.status = status;
        this.generatedAt = generatedAt;
        this.startedAt = startedAt;
        this.pausedAt = pausedAt;
        this.completedAt = completedAt;
        this.abandonedAt = abandonedAt;
        this.supersededAt = supersededAt;
        this.supersededBy = supersededBy;
        this.steps = List.copyOf(steps);
        this.masteryStartSnapshot = Map.copyOf(masteryStartSnapshot);
        this.masteryEndSnapshot = masteryEndSnapshot.map(Map::copyOf);
    }

    /**
     * Pre-save factory: status {@link LearnerPathStatus#NOT_STARTED},
     * generatedAt = {@code Instant.now(clock)}, no id, no timestamps,
     * no end snapshot.
     */
    public static LearnerPath newDraft(
            UserId userId,
            TopicId targetTopicId,
            List<LearnerPathStep> steps,
            Map<TopicId, MasteryScore> masteryStartSnapshot,
            Clock clock) {
        if (clock == null) {
            throw new DomainValidationException("LearnerPath clock must not be null");
        }
        return new LearnerPath(
                Optional.empty(),
                userId,
                targetTopicId,
                LearnerPathStatus.NOT_STARTED,
                Instant.now(clock),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                steps,
                masteryStartSnapshot,
                Optional.empty());
    }

    /** Read-back factory used by the persistence adapter; trusts the supplied state. */
    public static LearnerPath rehydrate(
            PathId id,
            UserId userId,
            TopicId targetTopicId,
            LearnerPathStatus status,
            Instant generatedAt,
            Optional<Instant> startedAt,
            Optional<Instant> pausedAt,
            Optional<Instant> completedAt,
            Optional<Instant> abandonedAt,
            Optional<Instant> supersededAt,
            Optional<PathId> supersededBy,
            List<LearnerPathStep> steps,
            Map<TopicId, MasteryScore> masteryStartSnapshot,
            Optional<Map<TopicId, MasteryScore>> masteryEndSnapshot) {
        if (id == null) {
            throw new DomainValidationException(
                    "LearnerPath rehydrate id must not be null; use newDraft for transient form");
        }
        return new LearnerPath(
                Optional.of(id),
                userId,
                targetTopicId,
                status,
                generatedAt,
                startedAt,
                pausedAt,
                completedAt,
                abandonedAt,
                supersededAt,
                supersededBy,
                steps,
                masteryStartSnapshot,
                masteryEndSnapshot);
    }

    // ---- transitions ----

    public LearnerPath start(Clock clock) {
        requireSource(LearnerPathStatus.NOT_STARTED, "start");
        Instant at = Instant.now(requireClock(clock));
        return copy(
                LearnerPathStatus.IN_PROGRESS,
                Optional.of(at),
                pausedAt,
                completedAt,
                abandonedAt,
                supersededAt,
                supersededBy,
                steps,
                masteryEndSnapshot);
    }

    public LearnerPath pause(Clock clock) {
        requireSource(LearnerPathStatus.IN_PROGRESS, "pause");
        Instant at = Instant.now(requireClock(clock));
        return copy(
                LearnerPathStatus.PAUSED,
                startedAt,
                Optional.of(at),
                completedAt,
                abandonedAt,
                supersededAt,
                supersededBy,
                steps,
                masteryEndSnapshot);
    }

    public LearnerPath resume(Clock clock) {
        requireSource(LearnerPathStatus.PAUSED, "resume");
        requireClock(clock);
        // resume keeps pausedAt for history; status flips back, no new timestamp column.
        return copy(
                LearnerPathStatus.IN_PROGRESS,
                startedAt,
                pausedAt,
                completedAt,
                abandonedAt,
                supersededAt,
                supersededBy,
                steps,
                masteryEndSnapshot);
    }

    /**
     * Marks the step at {@code stepOrder} {@link StepStatus#DONE} and
     * stamps its completedAt. If the path was {@link LearnerPathStatus#NOT_STARTED}
     * this also auto-advances it to {@link LearnerPathStatus#IN_PROGRESS}
     * — the learner has clearly started.
     */
    public LearnerPath markStepDone(int stepOrder, Clock clock) {
        if (!status.isActive() || status == LearnerPathStatus.PAUSED) {
            throw new DomainInvariantException(
                    "cannot markStepDone on path in status " + status);
        }
        Instant at = Instant.now(requireClock(clock));
        List<LearnerPathStep> next = new ArrayList<>(steps.size());
        boolean found = false;
        for (LearnerPathStep s : steps) {
            if (s.stepOrder() == stepOrder) {
                next.add(s.markDone(at));
                found = true;
            } else {
                next.add(s);
            }
        }
        if (!found) {
            throw new DomainInvariantException(
                    "no step with stepOrder " + stepOrder + " on this path");
        }
        LearnerPathStatus nextStatus =
                status == LearnerPathStatus.NOT_STARTED
                        ? LearnerPathStatus.IN_PROGRESS
                        : status;
        Optional<Instant> nextStarted =
                status == LearnerPathStatus.NOT_STARTED ? Optional.of(at) : startedAt;
        return copy(
                nextStatus,
                nextStarted,
                pausedAt,
                completedAt,
                abandonedAt,
                supersededAt,
                supersededBy,
                next,
                masteryEndSnapshot);
    }

    /**
     * Terminal: requires an end mastery snapshot so the offline harness
     * can attribute the path's mastery delta. Allowed only from active
     * non-paused states.
     */
    public LearnerPath complete(Map<TopicId, MasteryScore> endSnapshot, Clock clock) {
        if (!(status == LearnerPathStatus.IN_PROGRESS
                || status == LearnerPathStatus.REVIEW_PENDING)) {
            throw new DomainInvariantException(
                    "cannot complete path in status " + status + "; expected IN_PROGRESS or REVIEW_PENDING");
        }
        if (endSnapshot == null) {
            throw new DomainValidationException("complete endSnapshot must not be null");
        }
        Instant at = Instant.now(requireClock(clock));
        return copy(
                LearnerPathStatus.COMPLETED,
                startedAt,
                pausedAt,
                Optional.of(at),
                abandonedAt,
                supersededAt,
                supersededBy,
                steps,
                Optional.of(endSnapshot));
    }

    /** Terminal: allowed from any active status. */
    public LearnerPath abandon(Clock clock) {
        if (!status.isActive()) {
            throw new DomainInvariantException(
                    "cannot abandon path in terminal status " + status);
        }
        Instant at = Instant.now(requireClock(clock));
        return copy(
                LearnerPathStatus.ABANDONED,
                startedAt,
                pausedAt,
                completedAt,
                Optional.of(at),
                supersededAt,
                supersededBy,
                steps,
                masteryEndSnapshot);
    }

    /**
     * Terminal: marks this path SUPERSEDED, stamps the new path's id
     * onto {@link #supersededBy} so the lineage survives. Allowed only
     * from active statuses — superseding a terminal path is a bug.
     */
    public LearnerPath supersededBy(PathId successorId, Clock clock) {
        if (successorId == null) {
            throw new DomainValidationException("supersededBy successorId must not be null");
        }
        if (!status.isActive()) {
            throw new DomainInvariantException(
                    "cannot supersede path in terminal status " + status);
        }
        Instant at = Instant.now(requireClock(clock));
        return copy(
                LearnerPathStatus.SUPERSEDED,
                startedAt,
                pausedAt,
                completedAt,
                abandonedAt,
                Optional.of(at),
                Optional.of(successorId),
                steps,
                masteryEndSnapshot);
    }

    // ---- accessors ----

    public Optional<PathId> id() {
        return id;
    }

    public UserId userId() {
        return userId;
    }

    public TopicId targetTopicId() {
        return targetTopicId;
    }

    public LearnerPathStatus status() {
        return status;
    }

    public Instant generatedAt() {
        return generatedAt;
    }

    public Optional<Instant> startedAt() {
        return startedAt;
    }

    public Optional<Instant> pausedAt() {
        return pausedAt;
    }

    public Optional<Instant> completedAt() {
        return completedAt;
    }

    public Optional<Instant> abandonedAt() {
        return abandonedAt;
    }

    public Optional<Instant> supersededAt() {
        return supersededAt;
    }

    public Optional<PathId> supersededBy() {
        return supersededBy;
    }

    public List<LearnerPathStep> steps() {
        return steps;
    }

    public Map<TopicId, MasteryScore> masteryStartSnapshot() {
        return masteryStartSnapshot;
    }

    public Optional<Map<TopicId, MasteryScore>> masteryEndSnapshot() {
        return masteryEndSnapshot;
    }

    // ---- helpers ----

    private LearnerPath copy(
            LearnerPathStatus newStatus,
            Optional<Instant> newStartedAt,
            Optional<Instant> newPausedAt,
            Optional<Instant> newCompletedAt,
            Optional<Instant> newAbandonedAt,
            Optional<Instant> newSupersededAt,
            Optional<PathId> newSupersededBy,
            List<LearnerPathStep> newSteps,
            Optional<Map<TopicId, MasteryScore>> newEndSnapshot) {
        return new LearnerPath(
                id,
                userId,
                targetTopicId,
                newStatus,
                generatedAt,
                newStartedAt,
                newPausedAt,
                newCompletedAt,
                newAbandonedAt,
                newSupersededAt,
                newSupersededBy,
                newSteps,
                masteryStartSnapshot,
                newEndSnapshot);
    }

    private void requireSource(LearnerPathStatus required, String op) {
        if (status != required) {
            throw new DomainInvariantException(
                    "cannot " + op + " path in status " + status + "; expected " + required);
        }
    }

    private static Clock requireClock(Clock clock) {
        if (clock == null) {
            throw new DomainValidationException("clock must not be null");
        }
        return clock;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LearnerPath other)) {
            return false;
        }
        // Identity-based equality on id when both have one. Pre-save
        // drafts are never equal (no stable identity yet).
        return id.isPresent() && other.id.isPresent() && id.get().equals(other.id.get());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "LearnerPath(id="
                + id.map(PathId::value).map(String::valueOf).orElse("draft")
                + ", user="
                + userId.value()
                + ", target="
                + targetTopicId.value()
                + ", status="
                + status
                + ", steps="
                + steps.size()
                + ")";
    }
}
