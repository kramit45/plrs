package com.plrs.domain.quiz;

import com.plrs.domain.common.DomainValidationException;

/**
 * One answer option attached to a {@link QuizItem}. Carries the display
 * order, the option text, and whether selecting it counts as correct.
 *
 * <p>Validation at this level is intentionally narrow:
 *
 * <ul>
 *   <li>{@code optionOrder} is a positive integer ({@code >= 1}).
 *   <li>{@code optionText} is non-null and non-blank after trim. There is
 *       no upper-bound length cap — the DB column is {@code TEXT} per
 *       §3.c.1.3, so the domain matches.
 * </ul>
 *
 * <p>The "exactly one {@code is_correct = true} per quiz item" rule is
 * a {@link QuizItem}-level invariant, not an option-level one — it
 * cannot be checked from a single option in isolation.
 *
 * <p>Traces to: §3.c.1.3 (quiz_item_options DDL), FR-19 (quiz authoring).
 */
public record QuizItemOption(int optionOrder, String optionText, boolean isCorrect) {

    public QuizItemOption {
        if (optionOrder < 1) {
            throw new DomainValidationException(
                    "QuizItemOption optionOrder must be >= 1, got " + optionOrder);
        }
        if (optionText == null) {
            throw new DomainValidationException("QuizItemOption optionText must not be null");
        }
        if (optionText.trim().isEmpty()) {
            throw new DomainValidationException("QuizItemOption optionText must not be blank");
        }
    }
}
