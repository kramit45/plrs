package com.plrs.application.quiz;

import com.plrs.application.content.ContentNotFoundException;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.quiz.PersistedQuizAttempt;
import com.plrs.domain.quiz.QuizAttempt;
import com.plrs.domain.quiz.QuizAttemptRepository;
import com.plrs.domain.user.UserId;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Submits one quiz attempt. Acquires a per-({@code user, quiz})
 * advisory lock at the start of the transaction (§3.b.7.2),
 * delegates scoring to {@link Content#score} (server-authoritative
 * per FR-20), and persists the resulting {@link QuizAttempt}.
 *
 * <p><strong>Skeleton — DEFERRED to step 90:</strong>
 *
 * <ol>
 *   <li>EWMA mastery update per topic in {@code topicWeights}.
 *   <li>{@code users.user_skills_version} increment.
 *   <li>{@code outbox_event} row insert with {@code QUIZ_ATTEMPTED} payload.
 *   <li>Post-commit Redis cache invalidation.
 * </ol>
 *
 * <p>The current implementation persists the attempt and returns the
 * result only — functionally a "scoring + persistence" step; the
 * downstream invariants of TX-01 are completed in step 90.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}
 * so the bean is not created for the no-DB smoke test.
 *
 * <p>Traces to: §3.b.7.2 (advisory lock), §3.c.6.3, FR-20 (scoring).
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public final class SubmitQuizAttemptUseCase {

    private final ContentRepository contentRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final AdvisoryLockService lockService;
    private final Clock clock;

    public SubmitQuizAttemptUseCase(
            ContentRepository contentRepository,
            QuizAttemptRepository quizAttemptRepository,
            AdvisoryLockService lockService,
            Clock clock) {
        this.contentRepository = contentRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.lockService = lockService;
        this.clock = clock;
    }

    @Transactional
    public SubmitQuizAttemptResult handle(SubmitQuizAttemptCommand cmd) {
        // §3.b.7.2: per-(user, quiz) advisory lock prevents double-submit
        // races and serialises the future EWMA mastery update.
        lockService.acquireLock("quiz:" + cmd.userUuid() + ":" + cmd.quizContentId());

        UserId userId = UserId.of(cmd.userUuid());
        ContentId contentId = ContentId.of(cmd.quizContentId());

        Content quiz =
                contentRepository
                        .findById(contentId)
                        .orElseThrow(() -> new ContentNotFoundException(contentId));
        if (quiz.ctype() != ContentType.QUIZ) {
            throw new IllegalArgumentException(
                    "Content is not a quiz: " + contentId.value());
        }

        QuizAttempt attempt = quiz.score(userId, cmd.answers(), clock);
        PersistedQuizAttempt persisted = quizAttemptRepository.save(attempt);

        return new SubmitQuizAttemptResult(
                persisted.attemptId(),
                attempt.score(),
                attempt.correctCount(),
                attempt.totalCount(),
                attempt.perItemFeedback());
    }
}
