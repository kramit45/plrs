package com.plrs.domain.mastery;

import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Aggregate root for one row of {@code plrs_ops.user_skills}: a learner's
 * mastery + confidence on a specific topic. Updated by EWMA when a quiz
 * attempt is scored (FR-21, §3.c.5.7).
 *
 * <p>Invariants enforced by the canonical constructor:
 *
 * <ul>
 *   <li>{@code userId}, {@code topicId}, {@code mastery}, {@code confidence},
 *       {@code updatedAt} all non-null.
 *   <li>{@code confidence} in {@code [0, 1]} at scale 3 (NUMERIC(4,3)).
 * </ul>
 *
 * <p>Equality is based on the natural key {@code (userId, topicId)}.
 *
 * <p>Traces to: §3.c.1.4 (user_skills schema), §3.c.5.7 (EWMA algorithm),
 * FR-21 (mastery update).
 */
public final class UserSkill {

    /** Confidence increment per quiz attempt, capped at 1.0. */
    static final BigDecimal CONFIDENCE_STEP = new BigDecimal("0.100");

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(3);
    private static final BigDecimal ONE = BigDecimal.ONE.setScale(3);

    private final UserId userId;
    private final TopicId topicId;
    private final MasteryScore mastery;
    private final BigDecimal confidence;
    private final Instant updatedAt;

    private UserSkill(
            UserId userId,
            TopicId topicId,
            MasteryScore mastery,
            BigDecimal confidence,
            Instant updatedAt) {
        if (userId == null) {
            throw new DomainValidationException("UserSkill userId must not be null");
        }
        if (topicId == null) {
            throw new DomainValidationException("UserSkill topicId must not be null");
        }
        if (mastery == null) {
            throw new DomainValidationException("UserSkill mastery must not be null");
        }
        if (confidence == null) {
            throw new DomainValidationException("UserSkill confidence must not be null");
        }
        if (updatedAt == null) {
            throw new DomainValidationException("UserSkill updatedAt must not be null");
        }
        BigDecimal scaled = confidence.setScale(3, RoundingMode.HALF_UP);
        if (scaled.compareTo(ZERO) < 0 || scaled.compareTo(ONE) > 0) {
            throw new DomainInvariantException(
                    "UserSkill confidence must be in [0, 1], got " + confidence);
        }
        this.userId = userId;
        this.topicId = topicId;
        this.mastery = mastery;
        this.confidence = scaled;
        this.updatedAt = updatedAt;
    }

    /**
     * Builds a fresh skill with neutral mastery (0.5) and starting
     * confidence 0.100 — the seed for any topic the learner hasn't
     * touched yet (§3.c.5.7).
     */
    public static UserSkill initial(UserId userId, TopicId topicId, Clock clock) {
        if (clock == null) {
            throw new DomainValidationException("UserSkill.initial clock must not be null");
        }
        return new UserSkill(
                userId, topicId, MasteryScore.NEUTRAL, new BigDecimal("0.100"), clock.instant());
    }

    /** Reconstructs a UserSkill from persisted state. */
    public static UserSkill rehydrate(
            UserId userId,
            TopicId topicId,
            MasteryScore mastery,
            BigDecimal confidence,
            Instant updatedAt) {
        return new UserSkill(userId, topicId, mastery, confidence, updatedAt);
    }

    /**
     * Returns a new {@code UserSkill} with mastery blended via
     * {@link MasteryScore#blendWith} and confidence incremented by
     * {@link #CONFIDENCE_STEP} (capped at 1.000). The receiver is
     * unchanged.
     *
     * @throws DomainValidationException when {@code scoreFraction} is
     *     outside {@code [0, 1]} or {@code alphaEffective} is outside
     *     {@code [0, 1]}
     */
    public UserSkill applyEwma(
            BigDecimal scoreFraction, double alphaEffective, Clock clock) {
        if (scoreFraction == null) {
            throw new DomainValidationException("scoreFraction must not be null");
        }
        if (clock == null) {
            throw new DomainValidationException("clock must not be null");
        }
        if (scoreFraction.compareTo(BigDecimal.ZERO) < 0
                || scoreFraction.compareTo(BigDecimal.ONE) > 0) {
            throw new DomainValidationException(
                    "scoreFraction must be in [0, 1], got " + scoreFraction);
        }
        if (Double.isNaN(alphaEffective) || alphaEffective < 0.0 || alphaEffective > 1.0) {
            throw new DomainValidationException(
                    "alphaEffective must be in [0.0, 1.0], got " + alphaEffective);
        }
        MasteryScore newMastery =
                mastery.blendWith(MasteryScore.of(scoreFraction.doubleValue()), alphaEffective);
        BigDecimal newConfidence =
                confidence.add(CONFIDENCE_STEP).min(BigDecimal.ONE).setScale(3, RoundingMode.HALF_UP);
        return new UserSkill(userId, topicId, newMastery, newConfidence, clock.instant());
    }

    public UserId userId() {
        return userId;
    }

    public TopicId topicId() {
        return topicId;
    }

    public MasteryScore mastery() {
        return mastery;
    }

    public BigDecimal confidence() {
        return confidence;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserSkill other)) {
            return false;
        }
        return userId.equals(other.userId) && topicId.equals(other.topicId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, topicId);
    }

    @Override
    public String toString() {
        return "UserSkill{userId=" + userId
                + ", topicId=" + topicId
                + ", mastery=" + mastery
                + ", confidence=" + confidence
                + "}";
    }
}
