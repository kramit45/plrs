package com.plrs.domain.interaction;

import com.plrs.domain.common.DomainValidationException;

/**
 * Integer rating on the inclusive range {@code [1, 5]} — the scale PLRS
 * uses for the RATE interaction type. The range matches the
 * {@code CHECK (rating BETWEEN 1 AND 5)} constraint on the
 * {@code interactions.rating} column, so rejecting out-of-range inputs at
 * the domain boundary keeps the DB from being the last line of defence.
 *
 * <p>Backed by a primitive {@code int}: a Rating always carries a value
 * when it exists. Absence of a rating (an interaction whose type is not
 * RATE, or a user who hasn't rated yet) is expressed as
 * {@code Optional<Rating>} at the usage site, never as a nullable field on
 * this type.
 *
 * <p>Fractional ratings (e.g., 4.5) are intentionally unsupported — the
 * design is integer-only so the database CHECK and the value object stay
 * trivially aligned.
 *
 * <p>This is a {@code final class} with a {@code private} constructor and a
 * single {@link #of(int)} factory so validation is impossible to bypass.
 *
 * <p>Traces to: §3.c.1.4 (interactions.rating CHECK), §3.b.2.1 (RATE
 * interaction payload).
 */
public final class Rating {

    public static final int MIN = 1;
    public static final int MAX = 5;

    private final int value;

    private Rating(int value) {
        this.value = value;
    }

    /**
     * Wraps an int as a Rating.
     *
     * @throws DomainValidationException when {@code value} is outside {@code [1, 5]}
     */
    public static Rating of(int value) {
        if (value < MIN || value > MAX) {
            throw new DomainValidationException(
                    "Rating value must be in [" + MIN + ", " + MAX + "], got " + value);
        }
        return new Rating(value);
    }

    public int value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Rating other)) {
            return false;
        }
        return value == other.value;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(value);
    }

    @Override
    public String toString() {
        return "Rating(" + value + ")";
    }
}
