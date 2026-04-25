package com.plrs.application.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.Difficulty;
import com.plrs.domain.interaction.InteractionEvent;
import com.plrs.domain.interaction.InteractionRepository;
import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.mastery.UserSkill;
import com.plrs.domain.mastery.UserSkillRepository;
import com.plrs.domain.quiz.PerItemFeedback;
import com.plrs.domain.quiz.PersistedQuizAttempt;
import com.plrs.domain.quiz.QuizAttempt;
import com.plrs.domain.quiz.QuizAttemptRepository;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import com.plrs.domain.user.UserId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StudentDashboardServiceTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final UserId USER_ID =
            UserId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));

    @Mock private UserSkillRepository userSkillRepository;
    @Mock private InteractionRepository interactionRepository;
    @Mock private QuizAttemptRepository quizAttemptRepository;
    @Mock private ContentRepository contentRepository;
    @Mock private TopicRepository topicRepository;

    private StudentDashboardService service;

    @BeforeEach
    void setUp() {
        service =
                new StudentDashboardService(
                        userSkillRepository,
                        interactionRepository,
                        quizAttemptRepository,
                        contentRepository,
                        topicRepository);
    }

    private UserSkill skillFor(long topicId, double mastery) {
        return UserSkill.rehydrate(
                USER_ID,
                TopicId.of(topicId),
                MasteryScore.of(mastery),
                new BigDecimal("0.500"),
                T0);
    }

    private Topic topic(long id, String name) {
        return Topic.rehydrate(
                TopicId.of(id),
                name,
                "desc",
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    private Content video(long id, String title) {
        return Content.rehydrate(
                ContentId.of(id),
                TopicId.of(1L),
                title,
                ContentType.VIDEO,
                Difficulty.BEGINNER,
                10,
                "https://x.y/" + id,
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                AuditFields.initial("system", CLOCK));
    }

    private InteractionEvent completeAt(long contentId, Instant at) {
        return InteractionEvent.complete(
                USER_ID, ContentId.of(contentId), at, Optional.empty(), Optional.empty());
    }

    private PersistedQuizAttempt attempt(long attemptId, long quizContentId, Instant at) {
        QuizAttempt qa =
                new QuizAttempt(
                        USER_ID,
                        ContentId.of(quizContentId),
                        new BigDecimal("80.00"),
                        4,
                        5,
                        List.of(
                                new PerItemFeedback(1, 1, 1, true, TopicId.of(1L)),
                                new PerItemFeedback(2, 1, 1, true, TopicId.of(1L)),
                                new PerItemFeedback(3, 1, 1, true, TopicId.of(1L)),
                                new PerItemFeedback(4, 1, 1, true, TopicId.of(1L)),
                                new PerItemFeedback(5, 1, 2, false, TopicId.of(1L))),
                        Map.of(TopicId.of(1L), new BigDecimal("1.000")),
                        at);
        return new PersistedQuizAttempt(attemptId, qa);
    }

    @Test
    void emptyStateReturnsEmptyLists() {
        when(userSkillRepository.findByUser(USER_ID)).thenReturn(List.of());
        when(interactionRepository.findRecentCompletes(eq(USER_ID), eq(5)))
                .thenReturn(List.of());
        when(quizAttemptRepository.findRecentByUser(eq(USER_ID), eq(10)))
                .thenReturn(List.of());

        StudentDashboardView view = service.load(USER_ID);

        assertThat(view.top6Mastery()).isEmpty();
        assertThat(view.recentCompletes()).isEmpty();
        assertThat(view.recentAttempts()).isEmpty();
    }

    @Test
    void topMasteryOrdersDescending() {
        when(userSkillRepository.findByUser(USER_ID))
                .thenReturn(
                        List.of(
                                skillFor(1L, 0.40),
                                skillFor(2L, 0.90),
                                skillFor(3L, 0.55)));
        when(topicRepository.findById(TopicId.of(1L)))
                .thenReturn(Optional.of(topic(1L, "Topic A")));
        when(topicRepository.findById(TopicId.of(2L)))
                .thenReturn(Optional.of(topic(2L, "Topic B")));
        when(topicRepository.findById(TopicId.of(3L)))
                .thenReturn(Optional.of(topic(3L, "Topic C")));
        when(interactionRepository.findRecentCompletes(eq(USER_ID), eq(5)))
                .thenReturn(List.of());
        when(quizAttemptRepository.findRecentByUser(eq(USER_ID), eq(10)))
                .thenReturn(List.of());

        StudentDashboardView view = service.load(USER_ID);

        assertThat(view.top6Mastery())
                .extracting(MasteryByTopic::masteryScore)
                .containsExactly(0.90, 0.55, 0.40);
        assertThat(view.top6Mastery())
                .extracting(MasteryByTopic::topicName)
                .containsExactly("Topic B", "Topic C", "Topic A");
    }

    @Test
    void topMasteryCapsAt6WhenUserHasMoreTopics() {
        List<UserSkill> many = new ArrayList<>();
        for (long i = 1; i <= 10; i++) {
            many.add(skillFor(i, 0.10 * i));
            // Lenient: only the top-6 topic ids will actually be
            // resolved; the bottom 4 stubs are intentionally redundant
            // so we don't have to know which six the cap selects.
            lenient()
                    .when(topicRepository.findById(TopicId.of(i)))
                    .thenReturn(Optional.of(topic(i, "Topic " + i)));
        }
        when(userSkillRepository.findByUser(USER_ID)).thenReturn(many);
        when(interactionRepository.findRecentCompletes(eq(USER_ID), eq(5)))
                .thenReturn(List.of());
        when(quizAttemptRepository.findRecentByUser(eq(USER_ID), eq(10)))
                .thenReturn(List.of());

        StudentDashboardView view = service.load(USER_ID);

        assertThat(view.top6Mastery()).hasSize(6);
        // The six highest mastery scores out of 0.1..1.0 are 1.0..0.5.
        assertThat(view.top6Mastery())
                .extracting(MasteryByTopic::masteryScore)
                .isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(view.top6Mastery().get(0).masteryScore()).isEqualTo(1.0);
        assertThat(view.top6Mastery().get(5).masteryScore()).isEqualTo(0.5);
    }

    @Test
    void recentCompletesUsesLimit5AndPreservesRepoOrder() {
        when(userSkillRepository.findByUser(USER_ID)).thenReturn(List.of());
        List<InteractionEvent> events =
                List.of(
                        completeAt(11L, T0.minusSeconds(60)),
                        completeAt(12L, T0.minusSeconds(120)),
                        completeAt(13L, T0.minusSeconds(180)),
                        completeAt(14L, T0.minusSeconds(240)),
                        completeAt(15L, T0.minusSeconds(300)));
        when(interactionRepository.findRecentCompletes(eq(USER_ID), eq(5)))
                .thenReturn(events);
        when(contentRepository.findById(ContentId.of(11L)))
                .thenReturn(Optional.of(video(11L, "Vid 11")));
        when(contentRepository.findById(ContentId.of(12L)))
                .thenReturn(Optional.of(video(12L, "Vid 12")));
        when(contentRepository.findById(ContentId.of(13L)))
                .thenReturn(Optional.of(video(13L, "Vid 13")));
        when(contentRepository.findById(ContentId.of(14L)))
                .thenReturn(Optional.of(video(14L, "Vid 14")));
        when(contentRepository.findById(ContentId.of(15L)))
                .thenReturn(Optional.of(video(15L, "Vid 15")));
        when(quizAttemptRepository.findRecentByUser(eq(USER_ID), eq(10)))
                .thenReturn(List.of());

        StudentDashboardView view = service.load(USER_ID);

        assertThat(view.recentCompletes()).hasSize(5);
        assertThat(view.recentCompletes())
                .extracting(RecentCompletion::contentId)
                .containsExactly(11L, 12L, 13L, 14L, 15L);
    }

    @Test
    void recentAttemptsUsesLimit10AndPreservesRepoOrder() {
        when(userSkillRepository.findByUser(USER_ID)).thenReturn(List.of());
        when(interactionRepository.findRecentCompletes(eq(USER_ID), eq(5)))
                .thenReturn(List.of());
        List<PersistedQuizAttempt> attempts = new ArrayList<>();
        for (long i = 0; i < 10; i++) {
            long quizId = 200L + i;
            attempts.add(attempt(100L + i, quizId, T0.minusSeconds(60 * (i + 1))));
            when(contentRepository.findById(ContentId.of(quizId)))
                    .thenReturn(Optional.of(video(quizId, "Quiz " + quizId)));
        }
        when(quizAttemptRepository.findRecentByUser(eq(USER_ID), eq(10)))
                .thenReturn(attempts);

        StudentDashboardView view = service.load(USER_ID);

        assertThat(view.recentAttempts()).hasSize(10);
        assertThat(view.recentAttempts().get(0).attemptId()).isEqualTo(100L);
        assertThat(view.recentAttempts().get(9).attemptId()).isEqualTo(109L);
        assertThat(view.recentAttempts())
                .extracting(RecentAttempt::quizTitle)
                .first()
                .isEqualTo("Quiz 200");
    }

    @Test
    void missingTopicReturnsUnknownLabel() {
        when(userSkillRepository.findByUser(USER_ID))
                .thenReturn(List.of(skillFor(99L, 0.70)));
        when(topicRepository.findById(TopicId.of(99L))).thenReturn(Optional.empty());
        when(interactionRepository.findRecentCompletes(eq(USER_ID), eq(5)))
                .thenReturn(List.of());
        when(quizAttemptRepository.findRecentByUser(eq(USER_ID), eq(10)))
                .thenReturn(List.of());

        StudentDashboardView view = service.load(USER_ID);

        assertThat(view.top6Mastery()).hasSize(1);
        assertThat(view.top6Mastery().get(0).topicName()).isEqualTo("(unknown)");
    }

    @Test
    void missingContentInRecentCompletesReturnsDeletedLabel() {
        when(userSkillRepository.findByUser(USER_ID)).thenReturn(List.of());
        when(interactionRepository.findRecentCompletes(eq(USER_ID), eq(5)))
                .thenReturn(List.of(completeAt(404L, T0)));
        when(contentRepository.findById(ContentId.of(404L))).thenReturn(Optional.empty());
        when(quizAttemptRepository.findRecentByUser(eq(USER_ID), eq(10)))
                .thenReturn(List.of());

        StudentDashboardView view = service.load(USER_ID);

        assertThat(view.recentCompletes()).hasSize(1);
        assertThat(view.recentCompletes().get(0).title()).isEqualTo("(deleted)");
    }

    @Test
    void missingContentInRecentAttemptsReturnsDeletedLabel() {
        when(userSkillRepository.findByUser(USER_ID)).thenReturn(List.of());
        when(interactionRepository.findRecentCompletes(eq(USER_ID), eq(5)))
                .thenReturn(List.of());
        when(quizAttemptRepository.findRecentByUser(eq(USER_ID), eq(10)))
                .thenReturn(List.of(attempt(700L, 999L, T0)));
        when(contentRepository.findById(ContentId.of(999L))).thenReturn(Optional.empty());

        StudentDashboardView view = service.load(USER_ID);

        assertThat(view.recentAttempts()).hasSize(1);
        assertThat(view.recentAttempts().get(0).quizTitle()).isEqualTo("(deleted)");
    }
}
