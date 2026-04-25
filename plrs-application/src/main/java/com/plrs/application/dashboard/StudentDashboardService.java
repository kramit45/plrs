package com.plrs.application.dashboard;

import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.interaction.InteractionEvent;
import com.plrs.domain.interaction.InteractionRepository;
import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.mastery.UserSkill;
import com.plrs.domain.mastery.UserSkillRepository;
import com.plrs.domain.quiz.PersistedQuizAttempt;
import com.plrs.domain.quiz.QuizAttemptRepository;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import com.plrs.domain.user.UserId;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Aggregator behind {@code GET /dashboard} (FR-35). Loads three sections:
 *
 * <ol>
 *   <li>top-6 mastery rows ordered by {@code mastery_score} DESC,
 *   <li>last 5 COMPLETE interactions ordered by {@code occurred_at} DESC,
 *   <li>last 10 quiz attempts ordered by {@code attempted_at} DESC.
 * </ol>
 *
 * <p>For each row, the linked aggregate (topic / content / quiz) is
 * resolved by id; missing aggregates render as a stable fallback label
 * ({@code "(unknown)"} for topics, {@code "(deleted)"} for content) so
 * the view never crashes on a stale cross-reference.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}
 * to keep the bean out of the no-DB smoke test.
 *
 * <p>Traces to: FR-35 (student dashboard sections).
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class StudentDashboardService {

    static final int TOP_N_MASTERY = 6;
    static final int RECENT_COMPLETES_LIMIT = 5;
    static final int RECENT_ATTEMPTS_LIMIT = 10;

    private final UserSkillRepository userSkillRepository;
    private final InteractionRepository interactionRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final ContentRepository contentRepository;
    private final TopicRepository topicRepository;

    public StudentDashboardService(
            UserSkillRepository userSkillRepository,
            InteractionRepository interactionRepository,
            QuizAttemptRepository quizAttemptRepository,
            ContentRepository contentRepository,
            TopicRepository topicRepository) {
        this.userSkillRepository = userSkillRepository;
        this.interactionRepository = interactionRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.contentRepository = contentRepository;
        this.topicRepository = topicRepository;
    }

    public StudentDashboardView load(UserId userId) {
        List<MasteryByTopic> top6 = loadTopMastery(userId);
        List<RecentCompletion> completes = loadRecentCompletes(userId);
        List<RecentAttempt> attempts = loadRecentAttempts(userId);
        return new StudentDashboardView(top6, completes, attempts);
    }

    private List<MasteryByTopic> loadTopMastery(UserId userId) {
        List<UserSkill> skills = userSkillRepository.findByUser(userId);
        return skills.stream()
                .sorted(
                        Comparator.comparing(
                                (UserSkill s) -> s.mastery().value(),
                                Comparator.reverseOrder()))
                .limit(TOP_N_MASTERY)
                .map(this::toMasteryByTopic)
                .toList();
    }

    private MasteryByTopic toMasteryByTopic(UserSkill skill) {
        TopicId topicId = skill.topicId();
        String name =
                topicRepository.findById(topicId).map(Topic::name).orElse("(unknown)");
        MasteryScore mastery = skill.mastery();
        return new MasteryByTopic(topicId.value(), name, mastery.value());
    }

    private List<RecentCompletion> loadRecentCompletes(UserId userId) {
        List<InteractionEvent> events =
                interactionRepository.findRecentCompletes(userId, RECENT_COMPLETES_LIMIT);
        return events.stream().map(this::toRecentCompletion).toList();
    }

    private RecentCompletion toRecentCompletion(InteractionEvent event) {
        ContentId contentId = event.contentId();
        String title =
                contentRepository.findById(contentId).map(Content::title).orElse("(deleted)");
        return new RecentCompletion(contentId.value(), title, event.occurredAt());
    }

    private List<RecentAttempt> loadRecentAttempts(UserId userId) {
        List<PersistedQuizAttempt> attempts =
                quizAttemptRepository.findRecentByUser(userId, RECENT_ATTEMPTS_LIMIT);
        return attempts.stream().map(this::toRecentAttempt).toList();
    }

    private RecentAttempt toRecentAttempt(PersistedQuizAttempt persisted) {
        ContentId quizId = persisted.attempt().quizContentId();
        String title =
                contentRepository.findById(quizId).map(Content::title).orElse("(deleted)");
        return new RecentAttempt(
                persisted.attemptId(),
                quizId.value(),
                title,
                persisted.attempt().score(),
                persisted.attempt().attemptedAt());
    }
}
