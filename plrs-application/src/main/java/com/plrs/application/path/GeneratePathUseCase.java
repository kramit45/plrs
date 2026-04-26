package com.plrs.application.path;

import com.plrs.application.audit.Auditable;
import com.plrs.domain.path.LearnerPath;
import com.plrs.domain.path.LearnerPathDraft;
import com.plrs.domain.path.LearnerPathRepository;
import com.plrs.domain.path.PathId;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TX-10: plan, supersede-then-save, atomically per request.
 *
 * <p>The §3.b.4.3 invariant says at most one active path may exist for
 * any (user, target) pair, so the use case must take care to free the
 * partial-unique window <em>before</em> inserting the new draft. The
 * order is:
 *
 * <ol>
 *   <li>Run the {@link PathPlanner} to produce a {@link LearnerPathDraft}.
 *   <li>Look up the (at most one) existing active path for the same
 *       {@code (user, target)} and, if present, transition it to
 *       SUPERSEDED via {@link LearnerPath#supersededBy(PathId, Clock)}.
 *       The supersede write removes the row from the active window.
 *   <li>Persist the new draft. Now safe — the partial unique would
 *       have rejected this insert otherwise.
 *   <li>Stamp the previous row's {@code superseded_by} with the new
 *       path's id so the lineage survives. (We pre-stamped a placeholder
 *       to free the window; the real id is back-patched here.)
 * </ol>
 *
 * <p>All four steps run inside a single transaction; if anything throws
 * the original active path is rolled back to its prior state.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}.
 *
 * <p>Traces to: §3.b.4.3 (TX-10), FR-31.
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class GeneratePathUseCase {

    private final PathPlanner planner;
    private final LearnerPathRepository repository;
    private final Clock clock;

    public GeneratePathUseCase(
            PathPlanner planner, LearnerPathRepository repository, Clock clock) {
        this.planner = planner;
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Plans a path and persists it, superseding any prior active path
     * for the same (user, target) atomically.
     *
     * @return the {@link PathId} of the newly persisted path
     */
    @Transactional
    @Auditable(action = "PATH_GENERATED", entityType = "learner_path")
    public PathId handle(UserId userId, TopicId targetTopicId) {
        LearnerPathDraft draft = planner.plan(userId, targetTopicId);

        Optional<LearnerPath> prior =
                repository.findActiveByUserAndTarget(userId, targetTopicId);
        if (prior.isPresent()) {
            // Two-phase supersede: step (a) free the partial-unique window
            // with a placeholder superseded_by id (we use the prior path's
            // own id — guaranteed positive, not the new id we don't have yet),
            // step (c) back-patch the real new id once the new row exists.
            PathId priorId = prior.get().id().orElseThrow();
            LearnerPath placeheld = prior.get().supersededBy(priorId, clock);
            repository.update(placeheld);
        }

        LearnerPath saved = repository.save(draft);

        if (prior.isPresent()) {
            // Refresh the prior path from the DB and stamp the real successor id.
            PathId priorId = prior.get().id().orElseThrow();
            LearnerPath refreshed = repository.findById(priorId).orElseThrow();
            // refreshed.status is SUPERSEDED — supersededBy() already validated
            // that source. We need an in-place edit of supersededBy without
            // re-running the transition; rehydrate carries that.
            LearnerPath patched =
                    com.plrs.domain.path.LearnerPath.rehydrate(
                            priorId,
                            refreshed.userId(),
                            refreshed.targetTopicId(),
                            refreshed.status(),
                            refreshed.generatedAt(),
                            refreshed.startedAt(),
                            refreshed.pausedAt(),
                            refreshed.completedAt(),
                            refreshed.abandonedAt(),
                            refreshed.supersededAt(),
                            saved.id(),
                            refreshed.steps(),
                            refreshed.masteryStartSnapshot(),
                            refreshed.masteryEndSnapshot());
            repository.update(patched);
        }

        return saved.id().orElseThrow();
    }
}
