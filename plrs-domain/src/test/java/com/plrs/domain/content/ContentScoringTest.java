package com.plrs.domain.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.quiz.AnswerSubmission;
import com.plrs.domain.quiz.QuizAttempt;
import com.plrs.domain.quiz.QuizItem;
import com.plrs.domain.quiz.QuizItemOption;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContentScoringTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);
    private static final UserId USER = UserId.newId();
    private static final TopicId TOPIC_A = TopicId.of(1L);
    private static final TopicId TOPIC_B = TopicId.of(2L);

    private static QuizItem item(int order, TopicId topic, int correctOption) {
        return QuizItem.of(
                order,
                topic,
                "stem-" + order,
                Optional.empty(),
                List.of(
                        new QuizItemOption(1, "A", correctOption == 1),
                        new QuizItemOption(2, "B", correctOption == 2),
                        new QuizItemOption(3, "C", correctOption == 3),
                        new QuizItemOption(4, "D", correctOption == 4)));
    }

    /** 4-item quiz: items 1,2 in topic A; items 3,4 in topic B. Correct option = 1 for all. */
    private static Content fourItemQuiz() {
        return Content.rehydrate(
                ContentId.of(42L),
                TOPIC_A,
                "Quiz",
                ContentType.QUIZ,
                Difficulty.BEGINNER,
                15,
                "https://example.com/q",
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                AuditFields.initial("system", CLOCK),
                List.of(
                        item(1, TOPIC_A, 1),
                        item(2, TOPIC_A, 1),
                        item(3, TOPIC_B, 1),
                        item(4, TOPIC_B, 1)));
    }

    @Test
    void scoreAllCorrectReturns100() {
        QuizAttempt a =
                fourItemQuiz()
                        .score(
                                USER,
                                List.of(
                                        new AnswerSubmission(1, 1),
                                        new AnswerSubmission(2, 1),
                                        new AnswerSubmission(3, 1),
                                        new AnswerSubmission(4, 1)),
                                CLOCK);

        assertThat(a.score()).isEqualByComparingTo("100.00");
        assertThat(a.correctCount()).isEqualTo(4);
        assertThat(a.totalCount()).isEqualTo(4);
    }

    @Test
    void scoreHalfCorrectReturns50() {
        QuizAttempt a =
                fourItemQuiz()
                        .score(
                                USER,
                                List.of(
                                        new AnswerSubmission(1, 1),
                                        new AnswerSubmission(2, 1),
                                        new AnswerSubmission(3, 2),
                                        new AnswerSubmission(4, 3)),
                                CLOCK);

        assertThat(a.score()).isEqualByComparingTo("50.00");
        assertThat(a.correctCount()).isEqualTo(2);
    }

    @Test
    void scoreNoAnswersReturnsZero() {
        QuizAttempt a = fourItemQuiz().score(USER, List.of(), CLOCK);

        assertThat(a.score()).isEqualByComparingTo("0.00");
        assertThat(a.correctCount()).isZero();
        assertThat(a.perItemFeedback())
                .allSatisfy(
                        f -> {
                            assertThat(f.isCorrect()).isFalse();
                            assertThat(f.selectedOptionOrder()).isZero(); // sentinel for blank
                        });
    }

    @Test
    void scorePartialAnswersTreatsMissingAsIncorrect() {
        QuizAttempt a =
                fourItemQuiz()
                        .score(
                                USER,
                                List.of(new AnswerSubmission(1, 1), new AnswerSubmission(2, 1)),
                                CLOCK);

        assertThat(a.score()).isEqualByComparingTo("50.00");
        assertThat(a.perItemFeedback().get(2).selectedOptionOrder()).isZero();
        assertThat(a.perItemFeedback().get(3).selectedOptionOrder()).isZero();
    }

    @Test
    void scoreWithAnswerForUnknownItemThrows() {
        assertThatThrownBy(
                        () ->
                                fourItemQuiz()
                                        .score(
                                                USER,
                                                List.of(
                                                        new AnswerSubmission(1, 1),
                                                        new AnswerSubmission(99, 1)),
                                                CLOCK))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("99");
    }

    @Test
    void scoreTopicWeightsPartitionToOne() {
        QuizAttempt a =
                fourItemQuiz()
                        .score(
                                USER,
                                List.of(
                                        new AnswerSubmission(1, 1),
                                        new AnswerSubmission(2, 1),
                                        new AnswerSubmission(3, 1),
                                        new AnswerSubmission(4, 1)),
                                CLOCK);

        BigDecimal sum =
                a.topicWeights().values().stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(new BigDecimal("1.000"));
        assertThat(a.topicWeights().get(TOPIC_A)).isEqualByComparingTo("0.500");
        assertThat(a.topicWeights().get(TOPIC_B)).isEqualByComparingTo("0.500");
    }

    @Test
    void scorePerItemFeedbackSizeEqualsQuizSize() {
        QuizAttempt a =
                fourItemQuiz()
                        .score(USER, List.of(new AnswerSubmission(1, 1)), CLOCK);

        assertThat(a.perItemFeedback()).hasSize(4);
    }

    @Test
    void scoreCorrectCountMatchesFeedback() {
        QuizAttempt a =
                fourItemQuiz()
                        .score(
                                USER,
                                List.of(
                                        new AnswerSubmission(1, 1),
                                        new AnswerSubmission(2, 1),
                                        new AnswerSubmission(3, 2)),
                                CLOCK);

        long correctInFeedback =
                a.perItemFeedback().stream().filter(f -> f.isCorrect()).count();
        assertThat(correctInFeedback).isEqualTo(a.correctCount());
        assertThat(a.correctCount()).isEqualTo(2);
    }

    /** 6-item quiz, all in TOPIC_A. */
    private static Content sixItemQuiz() {
        return Content.rehydrate(
                ContentId.of(43L),
                TOPIC_A,
                "Six-item Quiz",
                ContentType.QUIZ,
                Difficulty.BEGINNER,
                15,
                "https://example.com/six",
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                AuditFields.initial("system", CLOCK),
                List.of(
                        item(1, TOPIC_A, 1),
                        item(2, TOPIC_A, 1),
                        item(3, TOPIC_A, 1),
                        item(4, TOPIC_A, 1),
                        item(5, TOPIC_A, 1),
                        item(6, TOPIC_A, 1)));
    }

    @Test
    void scoreFiveOfSixRoundsTo8333() {
        // 5/6 = 83.333... → HALF_UP → 83.33
        QuizAttempt a =
                sixItemQuiz()
                        .score(
                                USER,
                                List.of(
                                        new AnswerSubmission(1, 1),
                                        new AnswerSubmission(2, 1),
                                        new AnswerSubmission(3, 1),
                                        new AnswerSubmission(4, 1),
                                        new AnswerSubmission(5, 1)),
                                CLOCK);

        assertThat(a.score()).isEqualByComparingTo("83.33");
    }

    @Test
    void scoreOneOfSixRoundsTo1667() {
        // 1/6 = 16.666... → HALF_UP → 16.67
        QuizAttempt a =
                sixItemQuiz()
                        .score(USER, List.of(new AnswerSubmission(1, 1)), CLOCK);

        assertThat(a.score()).isEqualByComparingTo("16.67");
    }

    @Test
    void scoreThreeTopicsWeightsSumExactlyToOne() {
        // 3 items, one per topic → each weight = 1/3 = 0.333; the rounding
        // residue (0.001) is absorbed onto the largest weight to make the
        // total exactly 1.000.
        TopicId topicC = TopicId.of(3L);
        Content quiz =
                Content.rehydrate(
                        ContentId.of(99L),
                        TOPIC_A,
                        "3-topic",
                        ContentType.QUIZ,
                        Difficulty.BEGINNER,
                        10,
                        "https://x.y",
                        Optional.empty(),
                        Set.of(),
                        Optional.empty(),
                        AuditFields.initial("system", CLOCK),
                        List.of(item(1, TOPIC_A, 1), item(2, TOPIC_B, 1), item(3, topicC, 1)));

        QuizAttempt a = quiz.score(USER, List.of(), CLOCK);

        BigDecimal sum =
                a.topicWeights().values().stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(new BigDecimal("1.000"));
        assertThat(a.topicWeights()).containsOnlyKeys(TOPIC_A, TOPIC_B, topicC);
    }

    @Test
    void scoreThrowsOnNonQuizCtype() {
        Content video =
                Content.rehydrate(
                        ContentId.of(1L),
                        TOPIC_A,
                        "Video",
                        ContentType.VIDEO,
                        Difficulty.BEGINNER,
                        10,
                        "https://x.y",
                        Optional.empty(),
                        Set.of(),
                        Optional.empty(),
                        AuditFields.initial("system", CLOCK));

        assertThatThrownBy(() -> video.score(USER, List.of(), CLOCK))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("ctype=QUIZ");
    }

    @Test
    void scoreUsesClockForAttemptedAt() {
        Instant fixed = Instant.parse("2026-12-31T23:59:59Z");
        Clock atFixed = Clock.fixed(fixed, ZoneOffset.UTC);

        QuizAttempt a = fourItemQuiz().score(USER, List.of(), atFixed);

        assertThat(a.attemptedAt()).isEqualTo(fixed);
    }
}
