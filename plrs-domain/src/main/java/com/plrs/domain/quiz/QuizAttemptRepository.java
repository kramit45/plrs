package com.plrs.domain.quiz;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.user.UserId;
import java.util.List;
import java.util.Optional;

/**
 * Domain port for {@link QuizAttempt} persistence. The adapter
 * (step 84, infrastructure module) writes to {@code plrs_ops.quiz_attempts}.
 *
 * <p>Step 85 reshapes this port to return {@code PersistedQuizAttempt}
 * (the surrogate {@code attempt_id} + the domain {@link QuizAttempt});
 * for now {@code save} returns the domain attempt unchanged so the
 * surrogate id is fetched via {@link #findById}.
 *
 * <p>Traces to: §3.c.1.4 (quiz_attempts schema), FR-20 (scoring),
 * FR-21 (mastery update sources).
 */
public interface QuizAttemptRepository {

    /** Persists a fresh quiz attempt and returns the value-equal aggregate. */
    QuizAttempt save(QuizAttempt attempt);

    /** Loads an attempt by surrogate id. */
    Optional<QuizAttempt> findById(Long attemptId);

    /** Recent attempts for a user, ordered by {@code attempted_at} DESC. */
    List<QuizAttempt> findRecentByUser(UserId userId, int limit);

    /** All attempts a user has made on a given quiz, FIFO. */
    List<QuizAttempt> findByUserAndContent(UserId userId, ContentId contentId);

    /** Cheap probe used by the submit use case to detect repeat takers. */
    boolean existsByUserAndContent(UserId userId, ContentId contentId);
}
