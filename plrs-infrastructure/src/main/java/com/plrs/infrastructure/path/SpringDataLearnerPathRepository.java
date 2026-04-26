package com.plrs.infrastructure.path;

import com.plrs.domain.path.LearnerPath;
import com.plrs.domain.path.LearnerPathDraft;
import com.plrs.domain.path.LearnerPathRepository;
import com.plrs.domain.path.LearnerPathStatus;
import com.plrs.domain.path.LearnerPathStep;
import com.plrs.domain.path.PathId;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter implementing {@link LearnerPathRepository} on top of Spring
 * Data JPA. Children rows are managed explicitly via a sibling step
 * repository — see {@link LearnerPathJpaEntity}'s docstring for why.
 *
 * <p>{@link #update} replaces the step set wholesale (delete-by-path
 * + re-insert). Step sets are small (≤ ~30 in practice) and the
 * delete-then-insert form sidesteps the diff-and-update logic JPA
 * would otherwise need for orphan removal on a composite-PK child.
 *
 * <p>Both {@link #save} and {@link #update} run inside the caller's
 * transaction (use cases are {@code @Transactional}); the
 * partial-unique conflict surfaces as a Spring
 * {@code DataIntegrityViolationException} that callers can catch and
 * map per TX-10.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}.
 *
 * <p>Traces to: §3.a, §3.c.1.4, §3.b.4.3, FR-31..FR-34.
 */
@Repository
@ConditionalOnProperty(name = "spring.datasource.url")
public class SpringDataLearnerPathRepository implements LearnerPathRepository {

    private static final List<String> ACTIVE_NAMES =
            List.of(
                    LearnerPathStatus.NOT_STARTED.name(),
                    LearnerPathStatus.IN_PROGRESS.name(),
                    LearnerPathStatus.PAUSED.name(),
                    LearnerPathStatus.REVIEW_PENDING.name());

    private final LearnerPathJpaRepository pathJpa;
    private final LearnerPathStepJpaRepository stepJpa;
    private final LearnerPathMapper mapper;
    private final Clock clock;

    public SpringDataLearnerPathRepository(
            LearnerPathJpaRepository pathJpa,
            LearnerPathStepJpaRepository stepJpa,
            LearnerPathMapper mapper,
            Clock clock) {
        this.pathJpa = pathJpa;
        this.stepJpa = stepJpa;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public LearnerPath save(LearnerPathDraft draft) {
        // Materialise as a NOT_STARTED LearnerPath so we share the
        // mapper with update(). Use a transient generatedAt = now;
        // the SQL DEFAULT NOW() would also fire, but we want the
        // hydrated aggregate to carry the same instant the database
        // stamps without a re-read.
        Instant now = Instant.now(clock);
        LearnerPath transient_ =
                LearnerPath.newDraft(
                        draft.userId(),
                        draft.targetTopicId(),
                        draft.steps(),
                        draft.masteryStartSnapshot(),
                        Clock.fixed(now, java.time.ZoneOffset.UTC));

        LearnerPathJpaEntity entity = mapper.toEntity(transient_);
        LearnerPathJpaEntity saved = pathJpa.save(entity);

        for (LearnerPathStep step : draft.steps()) {
            stepJpa.save(mapper.toStepEntity(saved.getPathId(), step));
        }

        return loadById(saved.getPathId())
                .orElseThrow(() -> new IllegalStateException("save lost path " + saved.getPathId()));
    }

    @Override
    @Transactional
    public LearnerPath update(LearnerPath path) {
        Long pathId =
                path.id()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "update requires a persisted path with an id"))
                        .value();
        LearnerPathJpaEntity entity = mapper.toEntity(path);
        pathJpa.save(entity);
        // Wipe + reinsert the step set so JPA doesn't need to diff
        // composite-PK children.
        stepJpa.deleteByPathId(pathId);
        stepJpa.flush();
        for (LearnerPathStep step : path.steps()) {
            stepJpa.save(mapper.toStepEntity(pathId, step));
        }
        return loadById(pathId)
                .orElseThrow(
                        () -> new IllegalStateException("update lost path " + pathId));
    }

    @Override
    public Optional<LearnerPath> findById(PathId id) {
        return loadById(id.value());
    }

    @Override
    public Optional<LearnerPath> findActiveByUserAndTarget(
            UserId userId, TopicId targetTopicId) {
        return pathJpa
                .findFirstByUserIdAndTargetTopicIdAndStatusIn(
                        userId.value(), targetTopicId.value(), ACTIVE_NAMES)
                .map(this::hydrate);
    }

    @Override
    public List<LearnerPath> findRecentByUser(UserId userId, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0, got " + limit);
        }
        if (limit == 0) {
            return List.of();
        }
        return pathJpa
                .findByUserIdOrderByGeneratedAtDesc(userId.value(), PageRequest.of(0, limit))
                .stream()
                .map(this::hydrate)
                .toList();
    }

    private Optional<LearnerPath> loadById(Long pathId) {
        return pathJpa.findById(pathId).map(this::hydrate);
    }

    private LearnerPath hydrate(LearnerPathJpaEntity entity) {
        List<LearnerPathStepJpaEntity> steps =
                stepJpa.findByPathIdOrderByStepOrderAsc(entity.getPathId());
        return mapper.toDomain(entity, steps);
    }
}
