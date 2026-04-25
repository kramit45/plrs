package com.plrs.application.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.plrs.application.content.ContentNotFoundException;
import com.plrs.domain.common.AuditFields;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.Difficulty;
import com.plrs.domain.quiz.AnswerSubmission;
import com.plrs.domain.quiz.PersistedQuizAttempt;
import com.plrs.domain.quiz.QuizAttempt;
import com.plrs.domain.quiz.QuizAttemptRepository;
import com.plrs.domain.quiz.QuizItem;
import com.plrs.domain.quiz.QuizItemOption;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SubmitQuizAttemptUseCaseTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final UUID USER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final TopicId TOPIC = TopicId.of(1L);
    private static final ContentId QUIZ_ID = ContentId.of(42L);

    @Mock private ContentRepository contentRepository;
    @Mock private QuizAttemptRepository quizAttemptRepository;
    @Mock private AdvisoryLockService lockService;

    private SubmitQuizAttemptUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SubmitQuizAttemptUseCase(
                contentRepository, quizAttemptRepository, lockService, CLOCK);
    }

    private static QuizItem item(int order, int correctOption) {
        return QuizItem.of(
                order,
                TOPIC,
                "stem-" + order,
                Optional.empty(),
                List.of(
                        new QuizItemOption(1, "A", correctOption == 1),
                        new QuizItemOption(2, "B", correctOption == 2)));
    }

    private static Content twoItemQuiz() {
        return Content.rehydrate(
                QUIZ_ID,
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
                List.of(item(1, 1), item(2, 1)));
    }

    private static Content videoContent() {
        return Content.rehydrate(
                QUIZ_ID,
                TOPIC,
                "Video",
                ContentType.VIDEO,
                Difficulty.BEGINNER,
                10,
                "https://x.y",
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    private static PersistedQuizAttempt persistedReturning(QuizAttempt a) {
        return new PersistedQuizAttempt(99L, a);
    }

    @Test
    void happyPathAcquiresLockThenPersistsAttempt() {
        when(contentRepository.findById(QUIZ_ID)).thenReturn(Optional.of(twoItemQuiz()));
        when(quizAttemptRepository.save(any(QuizAttempt.class)))
                .thenAnswer(inv -> new PersistedQuizAttempt(99L, inv.getArgument(0)));

        SubmitQuizAttemptResult result =
                useCase.handle(
                        new SubmitQuizAttemptCommand(
                                USER_UUID,
                                QUIZ_ID.value(),
                                List.of(new AnswerSubmission(1, 1), new AnswerSubmission(2, 1))));

        assertThat(result.attemptId()).isEqualTo(99L);
        assertThat(result.score()).isEqualByComparingTo("100.00");
        assertThat(result.correctCount()).isEqualTo(2);
        verify(quizAttemptRepository).save(any(QuizAttempt.class));
    }

    @Test
    void lockAcquiredBeforeFindById() {
        when(contentRepository.findById(QUIZ_ID)).thenReturn(Optional.of(twoItemQuiz()));
        when(quizAttemptRepository.save(any()))
                .thenAnswer(inv -> persistedReturning(inv.getArgument(0)));

        useCase.handle(
                new SubmitQuizAttemptCommand(
                        USER_UUID,
                        QUIZ_ID.value(),
                        List.of(new AnswerSubmission(1, 1), new AnswerSubmission(2, 1))));

        InOrder inOrder = Mockito.inOrder(lockService, contentRepository, quizAttemptRepository);
        inOrder.verify(lockService).acquireLock(argThat(k -> k.startsWith("quiz:")));
        inOrder.verify(contentRepository).findById(QUIZ_ID);
        inOrder.verify(quizAttemptRepository).save(any());
    }

    @Test
    void quizNotFoundThrowsContentNotFoundException() {
        when(contentRepository.findById(QUIZ_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                useCase.handle(
                                        new SubmitQuizAttemptCommand(
                                                USER_UUID, QUIZ_ID.value(), List.of())))
                .isInstanceOf(ContentNotFoundException.class);

        verify(quizAttemptRepository, never()).save(any());
    }

    @Test
    void nonQuizContentThrowsIllegalArgumentException() {
        when(contentRepository.findById(QUIZ_ID)).thenReturn(Optional.of(videoContent()));

        assertThatThrownBy(
                        () ->
                                useCase.handle(
                                        new SubmitQuizAttemptCommand(
                                                USER_UUID, QUIZ_ID.value(), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a quiz");

        verify(quizAttemptRepository, never()).save(any());
    }

    @Test
    void delegatesScoringToContentScore() {
        when(contentRepository.findById(QUIZ_ID)).thenReturn(Optional.of(twoItemQuiz()));
        when(quizAttemptRepository.save(any()))
                .thenAnswer(inv -> persistedReturning(inv.getArgument(0)));

        // 1 of 2 correct → 50.00
        SubmitQuizAttemptResult result =
                useCase.handle(
                        new SubmitQuizAttemptCommand(
                                USER_UUID,
                                QUIZ_ID.value(),
                                List.of(new AnswerSubmission(1, 1), new AnswerSubmission(2, 2))));

        assertThat(result.score()).isEqualByComparingTo("50.00");
        assertThat(result.correctCount()).isEqualTo(1);
        assertThat(result.totalCount()).isEqualTo(2);
    }

    @Test
    void clockUsedForAttemptedAt() {
        when(contentRepository.findById(QUIZ_ID)).thenReturn(Optional.of(twoItemQuiz()));
        when(quizAttemptRepository.save(any()))
                .thenAnswer(inv -> persistedReturning(inv.getArgument(0)));

        useCase.handle(
                new SubmitQuizAttemptCommand(USER_UUID, QUIZ_ID.value(), List.of()));

        verify(quizAttemptRepository)
                .save(argThat(a -> a.attemptedAt().equals(T0)));
    }

    @Test
    void rejectsAnswerForUnknownItem() {
        when(contentRepository.findById(QUIZ_ID)).thenReturn(Optional.of(twoItemQuiz()));

        assertThatThrownBy(
                        () ->
                                useCase.handle(
                                        new SubmitQuizAttemptCommand(
                                                USER_UUID,
                                                QUIZ_ID.value(),
                                                List.of(new AnswerSubmission(99, 1)))))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("99");

        verify(quizAttemptRepository, never()).save(any());
    }

    @Test
    void doesNotYetUpdateMasteryNorOutbox() {
        // Skeleton-step contract: the use case has only 4 deps; no
        // UserSkillRepository, no OutboxRepository wiring yet (step 90).
        Constructor<?>[] ctors = SubmitQuizAttemptUseCase.class.getDeclaredConstructors();
        assertThat(ctors).hasSize(1);
        assertThat(ctors[0].getParameterCount()).isEqualTo(4);
        assertThat(ctors[0].getParameterTypes())
                .containsExactly(
                        ContentRepository.class,
                        QuizAttemptRepository.class,
                        AdvisoryLockService.class,
                        Clock.class);
    }

    @Test
    void lockKeyIncludesUserAndQuizIds() {
        when(contentRepository.findById(QUIZ_ID)).thenReturn(Optional.of(twoItemQuiz()));
        when(quizAttemptRepository.save(any()))
                .thenAnswer(inv -> persistedReturning(inv.getArgument(0)));

        useCase.handle(new SubmitQuizAttemptCommand(USER_UUID, QUIZ_ID.value(), List.of()));

        verify(lockService).acquireLock(eq("quiz:" + USER_UUID + ":" + QUIZ_ID.value()));
    }
}
