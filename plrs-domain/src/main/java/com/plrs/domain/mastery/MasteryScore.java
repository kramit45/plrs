package com.plrs.domain.mastery;

import com.plrs.domain.common.DomainValidationException;
import java.util.Locale;

/**
 * A scalar mastery score in the inclusive range {@code [0.0, 1.0]} — the
 * core signal PLRS uses to rank what a learner should practise next. A
 * value of {@code 0.0} means "no evidence of mastery", {@code 1.0} means
 * "fully mastered", and {@link #NEUTRAL} ({@code 0.5}) is the seed value
 * for a topic the learner has not yet touched.
 *
 * <p>Backed by a primitive {@code double}. Null, NaN, and the infinities
 * are rejected at construction so downstream arithmetic (notably
 * {@link #blendWith(MasteryScore, double)}) can assume well-defined inputs.
 *
 * <p>Immutable — {@link #blendWith(MasteryScore, double)} returns a fresh
 * instance rather than mutating. Persistence-side concerns (JPA
 * {@code AttributeConverter}) are deliberately kept out of this type;
 * infrastructure wires those separately.
 *
 * <p>Traces to: §3.b.2.1 (MasteryScore with blendWith), §2.e.2.4.3 (EWMA
 * formula), §3.c.5.7 (neutral 0.5 seed for unseen topics).
 */
public final class MasteryScore {

    private static final double MIN = 0.0;
    private static final double MAX = 1.0;

    /** Zero mastery — no evidence at all. */
    public static final MasteryScore ZERO = new MasteryScore(0.0);

    /**
     * Default starting mastery for an unseen topic per §3.c.5.7 — neither
     * evidence of mastery nor evidence of ignorance, just "we haven't asked
     * yet". Use this when seeding a fresh {@code user_topic_mastery} row.
     */
    public static final MasteryScore NEUTRAL = new MasteryScore(0.5);

    private final double value;

    private MasteryScore(double value) {
        this.value = value;
    }

    /**
     * Wraps a double as a MasteryScore.
     *
     * @throws DomainValidationException when {@code value} is NaN, infinite, or outside
     *     {@code [0.0, 1.0]}
     */
    public static MasteryScore of(double value) {
        if (Double.isNaN(value)) {
            throw new DomainValidationException("MasteryScore value must not be NaN");
        }
        if (Double.isInfinite(value)) {
            throw new DomainValidationException("MasteryScore value must be finite, got " + value);
        }
        if (value < MIN || value > MAX) {
            throw new DomainValidationException(
                    "MasteryScore value must be in [" + MIN + ", " + MAX + "], got " + value);
        }
        return new MasteryScore(value);
    }

    public double value() {
        return value;
    }

    /**
     * Exponentially-weighted moving average update per §2.e.2.4.3:
     * {@code new = alpha * other + (1 - alpha) * this}. A larger {@code alpha}
     * weights the new evidence more heavily; {@code alpha = 0.0} keeps this
     * score unchanged, {@code alpha = 1.0} replaces it entirely with
     * {@code other}.
     *
     * <p>The result is clamped to {@code [0.0, 1.0]} before construction so
     * that extreme floating-point rounding (e.g., {@code 1.0000000000000002})
     * does not cause a spurious {@link DomainValidationException}.
     *
     * @throws DomainValidationException when {@code other} is null or {@code alpha} is
     *     outside {@code [0.0, 1.0]}
     */
    public MasteryScore blendWith(MasteryScore other, double alpha) {
        if (other == null) {
            throw new DomainValidationException("MasteryScore blendWith other must not be null");
        }
        if (Double.isNaN(alpha) || alpha < 0.0 || alpha > 1.0) {
            throw new DomainValidationException(
                    "MasteryScore blendWith alpha must be in [0.0, 1.0], got " + alpha);
        }
        double blended = alpha * other.value + (1.0 - alpha) * this.value;
        double clamped = Math.max(MIN, Math.min(MAX, blended));
        return MasteryScore.of(clamped);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MasteryScore other)) {
            return false;
        }
        return Double.compare(value, other.value) == 0;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(value);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "MasteryScore(%.3f)", value);
    }
}
