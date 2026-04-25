package com.plrs.domain.recommendation;

import com.plrs.domain.common.DomainValidationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;

/**
 * The relevance score the recommender assigns to one (user, content)
 * pair. Bounded to {@code [0.0, 1.0]} and persisted as
 * {@code NUMERIC(6,4)} so the on-the-wire and on-disk representations
 * agree to four decimal places (§3.c.1.4 — recommendations.score).
 *
 * <p>Backed by a primitive {@code double} for arithmetic and a
 * {@link BigDecimal} accessor for persistence; {@link #of(double)}
 * rounds HALF_UP to four decimals so float drift doesn't seep into the
 * stored value. Null, NaN, and the infinities are rejected at
 * construction.
 *
 * <p>{@link #blendWith(RecommendationScore, double)} produces the
 * convex combination used by the MMR / re-rank stages of the
 * recommender pipeline (Iter 3 step 120+):
 * {@code new = (1 - lambda) * this + lambda * other}.
 *
 * <p>Traces to: §3.c.1.4 (recommendations schema), FR-26 / FR-27
 * (recommender ranking).
 */
public final class RecommendationScore {

    /** Number of decimal places retained, mirroring the DB column scale. */
    public static final int SCALE = 4;

    private static final double MIN = 0.0;
    private static final double MAX = 1.0;

    /** Zero relevance. */
    public static final RecommendationScore ZERO = new RecommendationScore(0.0);

    /** Maximum relevance. */
    public static final RecommendationScore ONE = new RecommendationScore(1.0);

    private final double value;

    private RecommendationScore(double value) {
        this.value = value;
    }

    /**
     * Wraps a double as a RecommendationScore, rounding HALF_UP to four
     * decimal places so the in-memory value matches what the database
     * stores.
     *
     * @throws DomainValidationException when {@code value} is NaN,
     *     infinite, or outside {@code [0.0, 1.0]}
     */
    public static RecommendationScore of(double value) {
        if (Double.isNaN(value)) {
            throw new DomainValidationException(
                    "RecommendationScore value must not be NaN");
        }
        if (Double.isInfinite(value)) {
            throw new DomainValidationException(
                    "RecommendationScore value must be finite, got " + value);
        }
        if (value < MIN || value > MAX) {
            throw new DomainValidationException(
                    "RecommendationScore value must be in [" + MIN + ", " + MAX
                            + "], got " + value);
        }
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP);
        return new RecommendationScore(rounded.doubleValue());
    }

    /** The raw double value at scale {@link #SCALE}. */
    public double value() {
        return value;
    }

    /** Persistence-friendly representation matching {@code NUMERIC(6,4)}. */
    public BigDecimal toBigDecimal() {
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Convex combination with another score:
     * {@code (1 - lambda) * this + lambda * other}. Clamped to
     * {@code [0.0, 1.0]} before construction so floating-point drift
     * (e.g. {@code 1.0000000000000002}) doesn't trip the bounds check.
     *
     * @throws DomainValidationException when {@code other} is null or
     *     {@code lambda} is NaN / outside {@code [0.0, 1.0]}
     */
    public RecommendationScore blendWith(RecommendationScore other, double lambda) {
        if (other == null) {
            throw new DomainValidationException("blendWith other must not be null");
        }
        if (Double.isNaN(lambda) || lambda < 0.0 || lambda > 1.0) {
            throw new DomainValidationException(
                    "blendWith lambda must be in [0.0, 1.0], got " + lambda);
        }
        double blended = (1.0 - lambda) * this.value + lambda * other.value;
        double clamped = Math.max(MIN, Math.min(MAX, blended));
        return RecommendationScore.of(clamped);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RecommendationScore other)) {
            return false;
        }
        return Double.compare(value, other.value) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "RecommendationScore(%.4f)", value);
    }
}
