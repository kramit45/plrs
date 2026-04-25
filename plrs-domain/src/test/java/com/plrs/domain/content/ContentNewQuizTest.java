package com.plrs.domain.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.quiz.QuizItem;
import com.plrs.domain.quiz.QuizItemOption;
import com.plrs.domain.topic.TopicId;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Tests the QUIZ-content construction path. The earlier spec proposed a
 * {@code Content.newQuiz} factory; the design evolved to a
 * {@link QuizContentDraft} + repository {@code saveQuiz} pattern (mirrors
 * non-quiz ContentDraft + save). This test class records the decision and
 * exercises the resulting contract.
 */
class ContentNewQuizTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);
    private static final TopicId TOPIC = TopicId.of(1L);

    private static QuizItem item(int order) {
        return QuizItem.of(
                order,
                TOPIC,
                "stem-" + order,
                Optional.empty(),
                List.of(
                        new QuizItemOption(1, "a", true),
                        new QuizItemOption(2, "b", false)));
    }

    @Test
    void rehydrateOfQuizContentCarriesItemsAndQuizCtype() {
        Content quiz =
                Content.rehydrate(
                        ContentId.of(42L),
                        TOPIC,
                        "Quiz",
                        ContentType.QUIZ,
                        Difficulty.BEGINNER,
                        15,
                        "https://example.com/q",
                        Optional.empty(),
                        Set.of(),
                        Optional.empty(),
                        AuditFields.initial("system", CLOCK),
                        List.of(item(1), item(2)));

        assertThat(quiz.ctype()).isEqualTo(ContentType.QUIZ);
        assertThat(quiz.quizItems()).hasSize(2);
    }

    @Test
    void quizContentDraftConstructsWithItems() {
        QuizContentDraft draft =
                new QuizContentDraft(
                        TOPIC,
                        "Quiz",
                        Difficulty.BEGINNER,
                        15,
                        "https://example.com/q",
                        Optional.empty(),
                        Set.of(),
                        Optional.empty(),
                        AuditFields.initial("system", CLOCK),
                        List.of(item(1), item(2), item(3)));

        assertThat(draft.items()).hasSize(3);
    }

    @Test
    void contentClassExposesNoPublicNewQuizFactory() {
        // The earlier spec proposed Content.newQuiz; the design evolved to
        // QuizContentDraft + ContentRepository.saveQuiz. Pin the absence so
        // a future "convenience factory" PR triggers an explicit decision.
        boolean hasNewQuiz =
                Stream.of(Content.class.getDeclaredMethods())
                        .map(Method::getName)
                        .anyMatch(name -> name.equals("newQuiz"));

        assertThat(hasNewQuiz)
                .as("Content.newQuiz factory was deliberately removed; use QuizContentDraft")
                .isFalse();
    }
}
