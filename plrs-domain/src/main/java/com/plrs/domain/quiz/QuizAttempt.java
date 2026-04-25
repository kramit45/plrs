package com.plrs.domain.quiz;

import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One scored quiz attempt — what the student answered, what was
 * correct, the overall score (NUMERIC(5,2) per §3.c.1.4), the per-item
 * feedback, and the per-topic weights the EWMA mastery update will
 * consume (§3.c.5.7).
 *
 * <p>Aggregate root of the quiz-result subdomain. Pure value type — no
 * mutators; all derived values are computed by {@code Content.score}
 * (step 83) and persisted by the SubmitQuizAttempt use case (step 85).
 *
 * <p>Invariants enforced by the canonical constructor:
 *
 * <ul>
 *   <li>{@code score} in {@code [0, 100]}, scale 2 (NUMERIC(5,2)).
 *   <li>{@code correctCount} and {@code totalCount} are non-negative;
 *       {@code correctCount <= totalCount}.
 *   <li>{@code perItemFeedback.size() == totalCount}.
 *   <li>{@code topicWeights} non-empty; values in {@code [0, 1]};
 *       weights sum to {@code 1.000} (3dp tolerance — matches the
 *       output of {@code Content.score}'s rounding-correction pass).
 * </ul>
 *
 * <p>Equality is based on the natural key
 * {@code (userId, quizContentId, attemptedAt)} since the surrogate
 * {@code attempt_id} is assigned by the database and exposed via
 * {@code PersistedQuizAttempt} at the application layer.
 *
 * <p>Traces to: §3.c.1.4 (quiz_attempts schema), §3.c.5.7 (per-topic
 * weights for EWMA), FR-20 (scoring), FR-21 (mastery update).
 */
public final class QuizAttempt {

    /** Scale of the persisted {@code score} column (NUMERIC(5,2)). */
    public static final int SCORE_SCALE = 2;

    /** Scale used for {@code topicWeights} arithmetic. */
    public static final int WEIGHT_SCALE = 3;

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCORE_SCALE);
    private static final BigDecimal HUNDRED =
            BigDecimal.valueOf(100).setScale(SCORE_SCALE);
    private static final BigDecimal ONE_AT_WEIGHT_SCALE =
            BigDecimal.ONE.setScale(WEIGHT_SCALE);

    private final UserId userId;
    private final ContentId quizContentId;
    private final BigDecimal score;
    private final int correctCount;
    private final int totalCount;
    private final List<PerItemFeedback> perItemFeedback;
    private final Map<TopicId, BigDecimal> topicWeights;
    private final Instant attemptedAt;

    public QuizAttempt(
            UserId userId,
            ContentId quizContentId,
            BigDecimal score,
            int correctCount,
            int totalCount,
            List<PerItemFeedback> perItemFeedback,
            Map<TopicId, BigDecimal> topicWeights,
            Instant attemptedAt) {
        if (userId == null) {
            throw new DomainValidationException("QuizAttempt userId must not be null");
        }
        if (quizContentId == null) {
            throw new DomainValidationException(
                    "QuizAttempt quizContentId must not be null");
        }
        if (score == null) {
            throw new DomainValidationException("QuizAttempt score must not be null");
        }
        if (perItemFeedback == null) {
            throw new DomainValidationException(
                    "QuizAttempt perItemFeedback must not be null");
        }
        if (topicWeights == null) {
            throw new DomainValidationException(
                    "QuizAttempt topicWeights must not be null");
        }
        if (attemptedAt == null) {
            throw new DomainValidationException("QuizAttempt attemptedAt must not be null");
        }

        BigDecimal scoreScaled = score.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
        if (scoreScaled.compareTo(ZERO) < 0 || scoreScaled.compareTo(HUNDRED) > 0) {
            throw new DomainInvariantException(
                    "QuizAttempt score must be in [0.00, 100.00], got " + score);
        }
        if (correctCount < 0 || totalCount < 0 || correctCount > totalCount) {
            throw new DomainInvariantException(
                    "QuizAttempt counts must satisfy 0 <= correctCount <= totalCount, got "
                            + correctCount + "/" + totalCount);
        }
        if (perItemFeedback.size() != totalCount) {
            throw new DomainInvariantException(
                    "QuizAttempt perItemFeedback size ("
                            + perItemFeedback.size()
                            + ") must match totalCount ("
                            + totalCount
                            + ")");
        }
        if (topicWeights.isEmpty()) {
            throw new DomainInvariantException("QuizAttempt topicWeights must not be empty");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<TopicId, BigDecimal> e : topicWeights.entrySet()) {
            if (e.getKey() == null) {
                throw new DomainValidationException(
                        "QuizAttempt topicWeights key (TopicId) must not be null");
            }
            if (e.getValue() == null) {
                throw new DomainValidationException(
                        "QuizAttempt topicWeights value must not be null");
            }
            if (e.getValue().compareTo(BigDecimal.ZERO) < 0
                    || e.getValue().compareTo(BigDecimal.ONE) > 0) {
                throw new DomainInvariantException(
                        "QuizAttempt topicWeight value must be in [0, 1], got "
                                + e.getValue());
            }
            sum = sum.add(e.getValue());
        }
        BigDecimal sumScaled = sum.setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
        if (sumScaled.compareTo(ONE_AT_WEIGHT_SCALE) != 0) {
            throw new DomainInvariantException(
                    "QuizAttempt topicWeights must sum to 1.000 (3dp), got " + sumScaled);
        }

        this.userId = userId;
        this.quizContentId = quizContentId;
        this.score = scoreScaled;
        this.correctCount = correctCount;
        this.totalCount = totalCount;
        this.perItemFeedback = List.copyOf(perItemFeedback);
        this.topicWeights = Map.copyOf(topicWeights);
        this.attemptedAt = attemptedAt;
    }

    public UserId userId() {
        return userId;
    }

    public ContentId quizContentId() {
        return quizContentId;
    }

    public BigDecimal score() {
        return score;
    }

    public int correctCount() {
        return correctCount;
    }

    public int totalCount() {
        return totalCount;
    }

    public List<PerItemFeedback> perItemFeedback() {
        return perItemFeedback;
    }

    public Map<TopicId, BigDecimal> topicWeights() {
        return topicWeights;
    }

    public Instant attemptedAt() {
        return attemptedAt;
    }

    /** Returns {@code score / 100} at scale 4 (HALF_UP). Useful for EWMA input. */
    public BigDecimal scoreFraction() {
        return score.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof QuizAttempt other)) {
            return false;
        }
        return userId.equals(other.userId)
                && quizContentId.equals(other.quizContentId)
                && attemptedAt.equals(other.attemptedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, quizContentId, attemptedAt);
    }
}
