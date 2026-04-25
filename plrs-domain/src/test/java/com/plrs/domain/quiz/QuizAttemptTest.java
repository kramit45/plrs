package com.plrs.domain.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuizAttemptTest {

    private static final UserId USER = UserId.newId();
    private static final ContentId QUIZ = ContentId.of(42L);
    private static final TopicId TOPIC_A = TopicId.of(1L);
    private static final TopicId TOPIC_B = TopicId.of(2L);
    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");

    private static PerItemFeedback fb(int itemOrder, boolean correct, TopicId topic) {
        return new PerItemFeedback(itemOrder, correct ? 1 : 2, 1, correct, topic);
    }

    @Test
    void validConstruction() {
        QuizAttempt a =
                new QuizAttempt(
                        USER,
                        QUIZ,
                        new BigDecimal("75.00"),
                        3,
                        4,
                        List.of(
                                fb(1, true, TOPIC_A),
                                fb(2, true, TOPIC_A),
                                fb(3, true, TOPIC_B),
                                fb(4, false, TOPIC_B)),
                        Map.of(TOPIC_A, new BigDecimal("0.500"), TOPIC_B, new BigDecimal("0.500")),
                        T0);

        assertThat(a.score()).isEqualByComparingTo("75.00");
        assertThat(a.correctCount()).isEqualTo(3);
        assertThat(a.totalCount()).isEqualTo(4);
    }

    @Test
    void rejectsScoreNegative() {
        assertThatThrownBy(
                        () ->
                                new QuizAttempt(
                                        USER,
                                        QUIZ,
                                        new BigDecimal("-0.01"),
                                        0,
                                        1,
                                        List.of(fb(1, false, TOPIC_A)),
                                        Map.of(TOPIC_A, BigDecimal.ONE),
                                        T0))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("score");
    }

    @Test
    void rejectsScoreOver100() {
        assertThatThrownBy(
                        () ->
                                new QuizAttempt(
                                        USER,
                                        QUIZ,
                                        new BigDecimal("100.01"),
                                        1,
                                        1,
                                        List.of(fb(1, true, TOPIC_A)),
                                        Map.of(TOPIC_A, BigDecimal.ONE),
                                        T0))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("score");
    }

    @Test
    void rejectsPerItemFeedbackSizeMismatchWithTotalCount() {
        assertThatThrownBy(
                        () ->
                                new QuizAttempt(
                                        USER,
                                        QUIZ,
                                        new BigDecimal("100.00"),
                                        1,
                                        2,
                                        List.of(fb(1, true, TOPIC_A)),
                                        Map.of(TOPIC_A, BigDecimal.ONE),
                                        T0))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("perItemFeedback");
    }

    @Test
    void rejectsCorrectCountGreaterThanTotal() {
        assertThatThrownBy(
                        () ->
                                new QuizAttempt(
                                        USER,
                                        QUIZ,
                                        new BigDecimal("100.00"),
                                        5,
                                        2,
                                        List.of(fb(1, true, TOPIC_A), fb(2, true, TOPIC_A)),
                                        Map.of(TOPIC_A, BigDecimal.ONE),
                                        T0))
                .isInstanceOf(DomainInvariantException.class);
    }

    @Test
    void rejectsTopicWeightsSummingToOtherThanOne() {
        assertThatThrownBy(
                        () ->
                                new QuizAttempt(
                                        USER,
                                        QUIZ,
                                        new BigDecimal("50.00"),
                                        1,
                                        2,
                                        List.of(fb(1, true, TOPIC_A), fb(2, false, TOPIC_B)),
                                        Map.of(
                                                TOPIC_A, new BigDecimal("0.500"),
                                                TOPIC_B, new BigDecimal("0.400")),
                                        T0))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("sum to 1.000");
    }

    @Test
    void acceptsTopicWeightsSummingToOneWithin3DpTolerance() {
        // 0.333 + 0.333 + 0.334 = 1.000 exactly at 3dp
        QuizAttempt a =
                new QuizAttempt(
                        USER,
                        QUIZ,
                        new BigDecimal("100.00"),
                        3,
                        3,
                        List.of(
                                fb(1, true, TOPIC_A),
                                fb(2, true, TOPIC_B),
                                fb(3, true, TopicId.of(3L))),
                        Map.of(
                                TOPIC_A, new BigDecimal("0.333"),
                                TOPIC_B, new BigDecimal("0.333"),
                                TopicId.of(3L), new BigDecimal("0.334")),
                        T0);

        assertThat(a.topicWeights()).hasSize(3);
    }

    @Test
    void scoreFractionReturnsScoreDividedByHundred() {
        QuizAttempt a =
                new QuizAttempt(
                        USER,
                        QUIZ,
                        new BigDecimal("83.33"),
                        5,
                        6,
                        List.of(
                                fb(1, true, TOPIC_A),
                                fb(2, true, TOPIC_A),
                                fb(3, true, TOPIC_A),
                                fb(4, true, TOPIC_A),
                                fb(5, true, TOPIC_A),
                                fb(6, false, TOPIC_A)),
                        Map.of(TOPIC_A, BigDecimal.ONE),
                        T0);

        assertThat(a.scoreFraction()).isEqualByComparingTo("0.8333");
    }

    @Test
    void immutabilityOfCollections() {
        Map<TopicId, BigDecimal> mutableWeights = new HashMap<>();
        mutableWeights.put(TOPIC_A, BigDecimal.ONE);

        QuizAttempt a =
                new QuizAttempt(
                        USER,
                        QUIZ,
                        new BigDecimal("100.00"),
                        1,
                        1,
                        List.of(fb(1, true, TOPIC_A)),
                        mutableWeights,
                        T0);

        mutableWeights.put(TOPIC_B, BigDecimal.ONE);

        assertThat(a.topicWeights()).hasSize(1).containsKey(TOPIC_A);
        assertThatThrownBy(() -> a.topicWeights().put(TOPIC_B, BigDecimal.ZERO))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> a.perItemFeedback().add(fb(99, false, TOPIC_A)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void scoreScaleIsTwo() {
        // Even if caller passes scale 5, the constructor coerces to scale 2.
        QuizAttempt a =
                new QuizAttempt(
                        USER,
                        QUIZ,
                        new BigDecimal("75.12345"),
                        3,
                        4,
                        List.of(
                                fb(1, true, TOPIC_A),
                                fb(2, true, TOPIC_A),
                                fb(3, true, TOPIC_B),
                                fb(4, false, TOPIC_B)),
                        Map.of(TOPIC_A, new BigDecimal("0.500"), TOPIC_B, new BigDecimal("0.500")),
                        T0);

        assertThat(a.score().scale()).isEqualTo(2);
        assertThat(a.score()).isEqualByComparingTo("75.12");
    }

    @Test
    void equalsAndHashCodeUseNaturalKey() {
        QuizAttempt a =
                new QuizAttempt(
                        USER,
                        QUIZ,
                        new BigDecimal("100.00"),
                        1,
                        1,
                        List.of(fb(1, true, TOPIC_A)),
                        Map.of(TOPIC_A, BigDecimal.ONE),
                        T0);
        QuizAttempt b =
                new QuizAttempt(
                        USER,
                        QUIZ,
                        new BigDecimal("0.00"),
                        0,
                        1,
                        List.of(fb(1, false, TOPIC_A)),
                        Map.of(TOPIC_A, BigDecimal.ONE),
                        T0);
        QuizAttempt different =
                new QuizAttempt(
                        USER,
                        QUIZ,
                        new BigDecimal("100.00"),
                        1,
                        1,
                        List.of(fb(1, true, TOPIC_A)),
                        Map.of(TOPIC_A, BigDecimal.ONE),
                        T0.plusSeconds(1));

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(different);
    }
}
