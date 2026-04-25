package com.plrs.domain.quiz;

import com.plrs.domain.common.DomainValidationException;

/**
 * One student answer in a quiz attempt — the {@code itemOrder} of the
 * question and the {@code selectedOptionOrder} of the option the
 * student picked. Server-authoritative scoring (FR-20) compares this
 * against the {@code is_correct} flag held by the {@link QuizItem}'s
 * options at the time of attempt; the student never sees those flags.
 *
 * <p>Traces to: §3.c.6.3 (QuizAttemptService.submit input shape), FR-20.
 */
public record AnswerSubmission(int itemOrder, int selectedOptionOrder) {

    public AnswerSubmission {
        if (itemOrder < 1) {
            throw new DomainValidationException(
                    "AnswerSubmission itemOrder must be >= 1, got " + itemOrder);
        }
        if (selectedOptionOrder < 1) {
            throw new DomainValidationException(
                    "AnswerSubmission selectedOptionOrder must be >= 1, got "
                            + selectedOptionOrder);
        }
    }
}
