package com.plrs.domain.recommendation;

import com.plrs.domain.common.DomainValidationException;

/**
 * Human-readable explanation for a single recommendation, surfaced to
 * the learner alongside the recommendation in the UI (FR-29 — "show why").
 *
 * <p>Trimmed and length-bounded ({@code 1..200} chars after trim) to
 * match the {@code reason_text VARCHAR(200) NOT NULL} column in
 * {@code plrs_ops.recommendations} (§3.c.1.4). Producers should keep
 * the text short and PII-light: it's stored verbatim and rendered
 * without further sanitisation.
 *
 * <p>Traces to: §3.c.1.4, FR-29.
 */
public record RecommendationReason(String text) {

    public static final int MAX_LENGTH = 200;

    public RecommendationReason {
        if (text == null) {
            throw new DomainValidationException("RecommendationReason text must not be null");
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new DomainValidationException("RecommendationReason text must not be blank");
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new DomainValidationException(
                    "RecommendationReason text must be at most " + MAX_LENGTH
                            + " characters, got " + trimmed.length());
        }
        text = trimmed;
    }
}
