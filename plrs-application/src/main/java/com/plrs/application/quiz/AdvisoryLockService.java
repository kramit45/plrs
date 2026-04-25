package com.plrs.application.quiz;

/**
 * Application-layer port for transaction-scoped advisory locks (§3.b.7.2).
 * The Postgres adapter calls {@code pg_advisory_xact_lock(hashtext(:k))}
 * inside the current transaction; the lock releases automatically on
 * commit or rollback.
 *
 * <p>Used by SubmitQuizAttempt to serialise concurrent submissions for
 * the same {@code (user, quiz)} pair so the EWMA mastery update (step 90)
 * cannot interleave.
 *
 * <p>Traces to: §3.b.7.2 (per-(user, quiz) advisory lock).
 */
public interface AdvisoryLockService {

    /**
     * Acquires a transaction-scoped advisory lock keyed on {@code key}.
     * Blocks until the lock is granted; releases automatically on
     * transaction completion.
     */
    void acquireLock(String key);
}
