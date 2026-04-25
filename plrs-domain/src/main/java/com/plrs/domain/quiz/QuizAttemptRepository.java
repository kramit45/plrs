package com.plrs.domain.quiz;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.user.UserId;
import java.util.List;
import java.util.Optional;

/**
 * Domain port for {@link QuizAttempt} persistence. The adapter writes
 * to {@code plrs_ops.quiz_attempts}.
 *
 * <p>Save and the read-back queries return {@link PersistedQuizAttempt}
 * so callers can reach the surrogate {@code attempt_id} without making
 * the id part of the {@link QuizAttempt} value identity.
 *
 * <p>Traces to: §3.c.1.4 (quiz_attempts schema), FR-20 (scoring),
 * FR-21 (mastery update sources).
 */
public interface QuizAttemptRepository {

    /** Persists a fresh quiz attempt and returns it with the DB-assigned id. */
    PersistedQuizAttempt save(QuizAttempt attempt);

    /** Loads an attempt by surrogate id. */
    Optional<PersistedQuizAttempt> findById(Long attemptId);

    /** Recent attempts for a user, ordered by {@code attempted_at} DESC. */
    List<PersistedQuizAttempt> findRecentByUser(UserId userId, int limit);

    /** All attempts a user has made on a given quiz, FIFO. */
    List<PersistedQuizAttempt> findByUserAndContent(UserId userId, ContentId contentId);

    /** Cheap probe used by the submit use case to detect repeat takers. */
    boolean existsByUserAndContent(UserId userId, ContentId contentId);
}
