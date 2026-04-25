package com.plrs.application.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plrs.application.content.ContentNotFoundException;
import com.plrs.application.outbox.OutboxEvent;
import com.plrs.application.outbox.OutboxRepository;
import com.plrs.domain.common.AuditFields;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.Difficulty;
import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.mastery.UserSkill;
import com.plrs.domain.mastery.UserSkillRepository;
import com.plrs.domain.quiz.AnswerSubmission;
import com.plrs.domain.quiz.PersistedQuizAttempt;
import com.plrs.domain.quiz.QuizAttempt;
import com.plrs.domain.quiz.QuizAttemptRepository;
import com.plrs.domain.quiz.QuizItem;
import com.plrs.domain.quiz.QuizItemOption;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import com.plrs.domain.user.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SubmitQuizAttemptUseCaseTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final UUID USER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UserId USER_ID = UserId.of(USER_UUID);
    private static final TopicId TOPIC_A = TopicId.of(1L);
    private static final TopicId TOPIC_B = TopicId.of(2L);
    private static final ContentId QUIZ_ID = ContentId.of(42L);

    @Mock private ContentRepository contentRepository;
    @Mock private QuizAttemptRepository quizAttemptRepository;
    @Mock private UserSkillRepository userSkillRepository;
    @Mock private UserRepository userRepository;
    @Mock private OutboxRepository outboxRepository;
    @Mock private AdvisoryLockService lockService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SubmitQuizAttemptUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new SubmitQuizAttemptUseCase(
                        contentRepository,
                        quizAttemptRepository,
                        userSkillRepository,
                        userRepository,
                        outboxRepository,
                        lockService,
                        objectMapper,
                        CLOCK);
    }

    private static QuizItem item(int order, TopicId topic, int correctOption) {
        return QuizItem.of(
                order,
                topic,
                "stem-" + order,
                Optional.empty(),
                List.of(
                        new QuizItemOption(1, "A", correctOption == 1),
                        new QuizItemOption(2, "B", correctOption == 2)));
    }

    private static Content singleTopicQuiz() {
        return Content.rehydrate(
                QUIZ_ID,
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
                List.of(item(1, TOPIC_A, 1), item(2, TOPIC_A, 1)));
    }

    private static Content twoTopicQuiz() {
        return Content.rehydrate(
                QUIZ_ID,
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
                List.of(item(1, TOPIC_A, 1), item(2, TOPIC_B, 1)));
    }

    private static Content videoContent() {
        return Content.rehydrate(
                QUIZ_ID,
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
    }

    private static PersistedQuizAttempt persistedReturning(QuizAttempt a) {
        return new PersistedQuizAttempt(99L, a);
    }

    private void stubHappyPath(Content quiz) {
        when(contentRepository.findById(QUIZ_ID)).thenReturn(Optional.of(quiz));
        when(quizAttemptRepository.save(any(QuizAttempt.class)))
                .thenAnswer(inv -> persistedReturning(inv.getArgument(0)));
        when(userSkillRepository.find(any(UserId.class), any(TopicId.class)))
                .thenReturn(Optional.empty());
        when(userSkillRepository.upsert(any(UserSkill.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepository.save(any(OutboxEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private SubmitQuizAttemptCommand bothCorrect() {
        return new SubmitQuizAttemptCommand(
                USER_UUID,
                QUIZ_ID.value(),
                List.of(new AnswerSubmission(1, 1), new AnswerSubmission(2, 1)));
    }

    private SubmitQuizAttemptCommand allWrong() {
        return new SubmitQuizAttemptCommand(
                USER_UUID,
                QUIZ_ID.value(),
                List.of(new AnswerSubmission(1, 2), new AnswerSubmission(2, 2)));
    }

    @Test
    void happyPathPersistsAttemptAndReturnsScore() {
        stubHappyPath(singleTopicQuiz());

        SubmitQuizAttemptResult result = useCase.handle(bothCorrect());

        assertThat(result.attemptId()).isEqualTo(99L);
        assertThat(result.score()).isEqualByComparingTo("100.00");
        assertThat(result.correctCount()).isEqualTo(2);
        assertThat(result.totalCount()).isEqualTo(2);
        assertThat(result.masteryDeltas()).hasSize(1);
    }

    @Test
    void lockAcquiredBeforeAnyRepositoryCall() {
        stubHappyPath(singleTopicQuiz());

        useCase.handle(bothCorrect());

        InOrder inOrder =
                Mockito.inOrder(
                        lockService,
                        contentRepository,
                        quizAttemptRepository,
                        userSkillRepository,
                        userRepository,
                        outboxRepository);
        inOrder.verify(lockService).acquireLock(eq("quiz:" + USER_UUID + ":" + QUIZ_ID.value()));
        inOrder.verify(contentRepository).findById(QUIZ_ID);
        inOrder.verify(quizAttemptRepository).save(any());
        inOrder.verify(userSkillRepository).upsert(any());
        inOrder.verify(userRepository).bumpSkillsVersion(USER_ID);
        inOrder.verify(outboxRepository).save(any());
    }

    @Test
    void quizNotFoundThrowsAndSkipsAllWrites() {
        when(contentRepository.findById(QUIZ_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                useCase.handle(
                                        new SubmitQuizAttemptCommand(
                                                USER_UUID, QUIZ_ID.value(), List.of())))
                .isInstanceOf(ContentNotFoundException.class);

        verify(quizAttemptRepository, never()).save(any());
        verify(userSkillRepository, never()).upsert(any());
        verify(userRepository, never()).bumpSkillsVersion(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void nonQuizContentThrowsAndSkipsAllWrites() {
        when(contentRepository.findById(QUIZ_ID)).thenReturn(Optional.of(videoContent()));

        assertThatThrownBy(
                        () ->
                                useCase.handle(
                                        new SubmitQuizAttemptCommand(
                                                USER_UUID, QUIZ_ID.value(), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a quiz");

        verify(quizAttemptRepository, never()).save(any());
        verify(userSkillRepository, never()).upsert(any());
        verify(userRepository, never()).bumpSkillsVersion(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void rejectsAnswerForUnknownItemAndSkipsWrites() {
        when(contentRepository.findById(QUIZ_ID)).thenReturn(Optional.of(singleTopicQuiz()));

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
        verify(userSkillRepository, never()).upsert(any());
        verify(userRepository, never()).bumpSkillsVersion(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void ewmaSeedsNeutralWhenNoPriorSkillAndAlphaIs040() {
        // Single topic, weight=1.0 → α_effective = 0.40 × 1.0 = 0.40.
        // Score 100% → other=1.0; new = 0.40 * 1.0 + 0.60 * 0.5 = 0.70.
        stubHappyPath(singleTopicQuiz());

        useCase.handle(bothCorrect());

        ArgumentCaptor<UserSkill> upserted = ArgumentCaptor.forClass(UserSkill.class);
        verify(userSkillRepository).upsert(upserted.capture());

        UserSkill saved = upserted.getValue();
        assertThat(saved.userId()).isEqualTo(USER_ID);
        assertThat(saved.topicId()).isEqualTo(TOPIC_A);
        assertThat(saved.mastery().value()).isCloseTo(0.70, within(1e-9));
    }

    @Test
    void ewmaPullsTowardZeroOnFullyWrongAttempt() {
        // Score 0% → other=0.0; new = 0.40 * 0.0 + 0.60 * 0.5 = 0.30.
        stubHappyPath(singleTopicQuiz());

        useCase.handle(allWrong());

        ArgumentCaptor<UserSkill> upserted = ArgumentCaptor.forClass(UserSkill.class);
        verify(userSkillRepository).upsert(upserted.capture());
        assertThat(upserted.getValue().mastery().value()).isCloseTo(0.30, within(1e-9));
    }

    @Test
    void ewmaScalesAlphaByPerTopicWeight() {
        // Two topics evenly weighted (weight=0.5 each) → α_effective per
        // topic = 0.40 × 0.5 = 0.20. Score 100% → new = 0.20 * 1.0 + 0.80
        // * 0.5 = 0.60 for each topic.
        stubHappyPath(twoTopicQuiz());

        useCase.handle(bothCorrect());

        ArgumentCaptor<UserSkill> upserted = ArgumentCaptor.forClass(UserSkill.class);
        verify(userSkillRepository, times(2)).upsert(upserted.capture());
        assertThat(upserted.getAllValues())
                .extracting(s -> s.topicId())
                .containsExactlyInAnyOrder(TOPIC_A, TOPIC_B);
        for (UserSkill s : upserted.getAllValues()) {
            assertThat(s.mastery().value()).isCloseTo(0.60, within(1e-9));
        }
    }

    @Test
    void ewmaUsesExistingMasteryWhenSkillRowAlreadyExists() {
        // Existing mastery 0.80, score 100%, weight 1.0:
        // new = 0.40 * 1.0 + 0.60 * 0.80 = 0.88.
        when(contentRepository.findById(QUIZ_ID)).thenReturn(Optional.of(singleTopicQuiz()));
        when(quizAttemptRepository.save(any()))
                .thenAnswer(inv -> persistedReturning(inv.getArgument(0)));
        when(userSkillRepository.find(USER_ID, TOPIC_A))
                .thenReturn(
                        Optional.of(
                                UserSkill.rehydrate(
                                        USER_ID,
                                        TOPIC_A,
                                        MasteryScore.of(0.80),
                                        new BigDecimal("0.500"),
                                        T0)));
        when(userSkillRepository.upsert(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        useCase.handle(bothCorrect());

        ArgumentCaptor<UserSkill> upserted = ArgumentCaptor.forClass(UserSkill.class);
        verify(userSkillRepository).upsert(upserted.capture());
        assertThat(upserted.getValue().mastery().value()).isCloseTo(0.88, within(1e-9));
    }

    @Test
    void bumpsSkillsVersionExactlyOnce() {
        stubHappyPath(twoTopicQuiz());

        useCase.handle(bothCorrect());

        verify(userRepository, times(1)).bumpSkillsVersion(USER_ID);
    }

    @Test
    void writesQuizAttemptedOutboxEvent() {
        stubHappyPath(singleTopicQuiz());

        useCase.handle(bothCorrect());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.aggregateType()).isEqualTo("QUIZ_ATTEMPTED");
        assertThat(event.aggregateId()).isEqualTo("99");
        assertThat(event.createdAt()).isEqualTo(T0);
        assertThat(event.outboxId()).isEmpty();
        assertThat(event.deliveredAt()).isEmpty();
        assertThat(event.attempts()).isEqualTo((short) 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void outboxPayloadContainsAttemptUserAndDeltaFields() throws Exception {
        stubHappyPath(singleTopicQuiz());

        useCase.handle(bothCorrect());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        Map<String, Object> payload =
                objectMapper.readValue(captor.getValue().payloadJson(), Map.class);
        assertThat(payload)
                .containsKeys("attemptId", "userId", "quizContentId", "score", "deltas");
        assertThat(payload.get("attemptId")).isEqualTo(99);
        assertThat(payload.get("userId")).isEqualTo(USER_UUID.toString());
        assertThat(payload.get("quizContentId")).isEqualTo(QUIZ_ID.value().intValue());

        List<Map<String, Object>> deltas =
                (List<Map<String, Object>>) payload.get("deltas");
        assertThat(deltas).hasSize(1);
        Map<String, Object> delta = deltas.get(0);
        assertThat(delta).containsKeys("topicId", "oldMastery", "newMastery");
        assertThat(((Number) delta.get("oldMastery")).doubleValue()).isCloseTo(0.5, within(1e-9));
        assertThat(((Number) delta.get("newMastery")).doubleValue()).isCloseTo(0.7, within(1e-9));
    }

    @Test
    void resultMasteryDeltasMatchUpsertedSkills() {
        stubHappyPath(twoTopicQuiz());

        SubmitQuizAttemptResult result = useCase.handle(bothCorrect());

        assertThat(result.masteryDeltas()).hasSize(2);
        assertThat(result.masteryDeltas())
                .extracting(MasteryDelta::topicId)
                .containsExactlyInAnyOrder(TOPIC_A, TOPIC_B);
        for (MasteryDelta d : result.masteryDeltas()) {
            assertThat(d.oldMastery().value()).isCloseTo(0.5, within(1e-9));
            assertThat(d.newMastery().value()).isCloseTo(0.6, within(1e-9));
        }
    }

    @Test
    void clockUsedForAttemptedAtAndOutboxCreatedAt() {
        stubHappyPath(singleTopicQuiz());

        useCase.handle(bothCorrect());

        verify(quizAttemptRepository).save(argThat(a -> a.attemptedAt().equals(T0)));
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().createdAt()).isEqualTo(T0);
    }

    @Test
    void lockKeyIncludesUserAndQuizIds() {
        stubHappyPath(singleTopicQuiz());

        useCase.handle(bothCorrect());

        verify(lockService).acquireLock(eq("quiz:" + USER_UUID + ":" + QUIZ_ID.value()));
    }
}
