package com.plrs.domain.content;

import com.plrs.domain.common.DomainValidationException;
import java.util.Arrays;

/**
 * Learner-facing difficulty label attached to each piece of content. The
 * declared order ({@code BEGINNER}, {@code INTERMEDIATE}, {@code ADVANCED})
 * is load-bearing: the Postgres {@code content_difficulty_enum} CHECK
 * constraint and the recommender's feasibility filter (FR-27) rely on
 * this ordering. Do not reorder without auditing every caller.
 *
 * <p>{@link #rank()} exposes a strictly increasing numeric projection of
 * the declared order — {@code BEGINNER=1}, {@code INTERMEDIATE=2},
 * {@code ADVANCED=3}. The recommender uses this to gate content against a
 * learner's current mastery in Iteration 3, so the rank values are part
 * of the public contract, not an implementation detail.
 *
 * <p>Traces to: §3.c.1.3 (content_difficulty_enum), FR-08 (content
 * catalogue), FR-27 (feasibility filter in recommender).
 */
public enum Difficulty {
    BEGINNER(1),
    INTERMEDIATE(2),
    ADVANCED(3);

    private final int rank;

    Difficulty(int rank) {
        this.rank = rank;
    }

    /** Strictly increasing numeric projection of the declared order. */
    public int rank() {
        return rank;
    }

    /**
     * Parses a difficulty name using a case-sensitive match. Same
     * rationale as {@link com.plrs.domain.user.Role#fromName(String)} and
     * {@link ContentType#fromName(String)}.
     *
     * @throws DomainValidationException when {@code name} is null or does
     *     not match any difficulty; the message lists the valid values
     */
    public static Difficulty fromName(String name) {
        if (name == null) {
            throw new DomainValidationException(
                    "Difficulty name must not be null; expected one of "
                            + Arrays.toString(values()));
        }
        for (Difficulty d : values()) {
            if (d.name().equals(name)) {
                return d;
            }
        }
        throw new DomainValidationException(
                "Unknown difficulty: '" + name + "'; expected one of "
                        + Arrays.toString(values()));
    }
}
