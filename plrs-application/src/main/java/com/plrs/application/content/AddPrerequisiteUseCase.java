package com.plrs.application.content;

import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.PrerequisiteEdge;
import com.plrs.domain.content.PrerequisiteRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds a directed edge to the prerequisite DAG with the concurrency
 * discipline required by §3.b.7.1: the inner transactional method runs
 * at SERIALIZABLE isolation so that a "no cycle" check followed by a
 * "save" cannot interleave with another writer creating the closing
 * edge. The orchestrator wrapping that method retries exactly once on
 * the canonical retryable failures (Postgres serialization conflict
 * surfaces as {@link ConcurrencyFailureException}; race-on-insert as
 * {@link DataIntegrityViolationException}); a small randomised back-off
 * smooths out hot-spot retries.
 *
 * <p>Idempotent semantics per §2.e.2.5 (CFD-4): if the edge already
 * exists, {@code handle} returns silently. Callers can replay the
 * command without observing duplicate work or a different exception
 * shape.
 *
 * <p>Domain invariants:
 *
 * <ul>
 *   <li>Both ids must reference existing content rows
 *       ({@link ContentNotFoundException} otherwise).
 *   <li>The edge must not be self-referential and must not close a cycle
 *       in the existing DAG (the aggregate's
 *       {@link Content#canAddPrerequisite} runs both checks via the
 *       narrow {@link com.plrs.domain.content.PrerequisiteCheckingRepository}
 *       port).
 * </ul>
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")} so
 * the bean does not register when {@code PlrsApplicationTests} runs its
 * no-DB smoke test.
 *
 * <p>Traces to: §2.e.2.5 (CFD-4 idempotent prerequisite add),
 * §3.b.7.1 (SERIALIZABLE + retry concurrency policy), FR-09
 * (prerequisite tracking).
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public final class AddPrerequisiteUseCase {

    private static final long BACKOFF_MIN_MS = 50L;
    private static final int BACKOFF_JITTER_MS = 50;

    private final ContentRepository contentRepository;
    private final PrerequisiteRepository prereqRepository;
    private final Clock clock;

    public AddPrerequisiteUseCase(
            ContentRepository contentRepository,
            PrerequisiteRepository prereqRepository,
            Clock clock) {
        this.contentRepository = contentRepository;
        this.prereqRepository = prereqRepository;
        this.clock = clock;
    }

    /**
     * Validates and persists the edge. Catches and retries exactly once
     * on {@link ConcurrencyFailureException} (which covers Spring's
     * {@link org.springframework.dao.CannotAcquireLockException} and
     * Postgres SERIALIZABLE conflict translation) or
     * {@link DataIntegrityViolationException} (race on the composite
     * PK insert). Other exceptions —
     * {@link ContentNotFoundException},
     * {@link com.plrs.domain.content.CycleDetectedException},
     * {@link com.plrs.domain.common.DomainValidationException} —
     * propagate unchanged.
     *
     * <p>Traces to: §2.e.2.5 (CFD-4), §3.b.7.1 (SERIALIZABLE + retry),
     * FR-09.
     */
    public void handle(AddPrerequisiteCommand cmd) {
        try {
            doHandle(cmd);
        } catch (ConcurrencyFailureException | DataIntegrityViolationException firstFailure) {
            backoff(firstFailure);
            doHandle(cmd);
        }
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    void doHandle(AddPrerequisiteCommand cmd) {
        Content content =
                contentRepository
                        .findById(cmd.contentId())
                        .orElseThrow(() -> new ContentNotFoundException(cmd.contentId()));
        contentRepository
                .findById(cmd.prereqId())
                .orElseThrow(() -> new ContentNotFoundException(cmd.prereqId()));

        if (prereqRepository.exists(cmd.contentId(), cmd.prereqId())) {
            return;
        }

        content.canAddPrerequisite(cmd.prereqId(), prereqRepository);

        prereqRepository.save(
                new PrerequisiteEdge(
                        cmd.contentId(),
                        cmd.prereqId(),
                        Instant.now(clock),
                        cmd.addedBy()));
    }

    private static void backoff(RuntimeException firstFailure) {
        long sleep = BACKOFF_MIN_MS + ThreadLocalRandom.current().nextInt(BACKOFF_JITTER_MS);
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw firstFailure;
        }
    }
}
