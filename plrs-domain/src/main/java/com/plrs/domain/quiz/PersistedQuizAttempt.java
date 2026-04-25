package com.plrs.domain.quiz;

/**
 * Pair of a {@link QuizAttempt} value and the surrogate {@code attempt_id}
 * the database assigned to it. Used by
 * {@link QuizAttemptRepository#save(QuizAttempt)} so callers can refer
 * to the persisted row without making the {@code attempt_id} part of
 * the value-object identity.
 *
 * <p>Lives in {@code plrs-domain} (not {@code plrs-application}) because
 * the port that returns it is domain-owned; a record in the application
 * layer would force a {@code domain → application} dependency that the
 * hexagonal layout forbids.
 */
public record PersistedQuizAttempt(Long attemptId, QuizAttempt attempt) {}
