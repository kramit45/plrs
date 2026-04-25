package com.plrs.application.quiz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plrs.application.cache.TopNCache;
import com.plrs.application.content.ContentNotFoundException;
import com.plrs.application.outbox.OutboxEvent;
import com.plrs.application.outbox.OutboxRepository;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.mastery.UserSkill;
import com.plrs.domain.mastery.UserSkillRepository;
import com.plrs.domain.quiz.PersistedQuizAttempt;
import com.plrs.domain.quiz.QuizAttempt;
import com.plrs.domain.quiz.QuizAttemptRepository;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import com.plrs.domain.user.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Submits one quiz attempt and applies the full TX-01 transactional
 * invariant in a single {@code @Transactional} method (§2.e.2.4.2):
 *
 * <ol>
 *   <li>Acquire the per-({@code user, quiz}) advisory lock (§3.b.7.2).
 *   <li>Score the quiz via {@link Content#score} (server-authoritative,
 *       FR-20).
 *   <li>Persist the {@link QuizAttempt}.
 *   <li>For each topic in {@code attempt.topicWeights()}, EWMA-update the
 *       learner's {@link UserSkill} with effective alpha
 *       {@code α_effective = ALPHA_QUIZ × topicWeight} (§3.c.5.7) and
 *       upsert it.
 *   <li>Bump {@code users.user_skills_version} (TRG-3 enforces monotonic
 *       increase, §3.b.5.3).
 *   <li>Insert one {@code QUIZ_ATTEMPTED} outbox event so downstream
 *       consumers see the change after commit (§2.e.3.6).
 *   <li>Register a {@code TransactionSynchronization.afterCommit} hook
 *       that invalidates the per-user top-N cache (TX-04, step 91).
 *       Best-effort — the {@code user_skills_version} bump is the
 *       authoritative invariant (§2.e.2.3.3).
 * </ol>
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}
 * so the bean is not created for the no-DB smoke test.
 *
 * <p>Traces to: §3.b.7.2 (advisory lock), §3.c.5.7 (EWMA), §2.e.2.4.2
 * (TX-01), §2.e.3.6 (outbox), FR-20, FR-21.
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
@Transactional
public class SubmitQuizAttemptUseCase {

    /**
     * Base learning rate for the EWMA update (§3.c.5.7). The per-topic
     * effective alpha is {@code ALPHA_QUIZ × topicWeight}.
     */
    public static final BigDecimal ALPHA_QUIZ = new BigDecimal("0.40");

    private final ContentRepository contentRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final UserSkillRepository userSkillRepository;
    private final UserRepository userRepository;
    private final OutboxRepository outboxRepository;
    private final AdvisoryLockService lockService;
    private final TopNCache topNCache;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SubmitQuizAttemptUseCase(
            ContentRepository contentRepository,
            QuizAttemptRepository quizAttemptRepository,
            UserSkillRepository userSkillRepository,
            UserRepository userRepository,
            OutboxRepository outboxRepository,
            AdvisoryLockService lockService,
            TopNCache topNCache,
            ObjectMapper objectMapper,
            Clock clock) {
        this.contentRepository = contentRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.userSkillRepository = userSkillRepository;
        this.userRepository = userRepository;
        this.outboxRepository = outboxRepository;
        this.lockService = lockService;
        this.topNCache = topNCache;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @com.plrs.application.audit.Auditable(
            action = "QUIZ_ATTEMPTED",
            entityType = "quiz_attempt")
    public SubmitQuizAttemptResult handle(SubmitQuizAttemptCommand cmd) {
        // §3.b.7.2: per-(user, quiz) advisory lock prevents double-submit
        // races and serialises the EWMA mastery update for this learner
        // and quiz.
        lockService.acquireLock("quiz:" + cmd.userUuid() + ":" + cmd.quizContentId());

        UserId userId = UserId.of(cmd.userUuid());
        ContentId contentId = ContentId.of(cmd.quizContentId());

        Content quiz =
                contentRepository
                        .findById(contentId)
                        .orElseThrow(() -> new ContentNotFoundException(contentId));
        if (quiz.ctype() != ContentType.QUIZ) {
            throw new IllegalArgumentException(
                    "Content is not a quiz: " + contentId.value());
        }

        QuizAttempt attempt = quiz.score(userId, cmd.answers(), clock);
        PersistedQuizAttempt persisted = quizAttemptRepository.save(attempt);

        BigDecimal scoreFraction = attempt.scoreFraction();
        List<MasteryDelta> deltas = new ArrayList<>(attempt.topicWeights().size());
        for (Map.Entry<TopicId, BigDecimal> entry : attempt.topicWeights().entrySet()) {
            TopicId topicId = entry.getKey();
            BigDecimal weight = entry.getValue();
            double alphaEffective = ALPHA_QUIZ.multiply(weight).doubleValue();

            UserSkill current =
                    userSkillRepository
                            .find(userId, topicId)
                            .orElseGet(() -> UserSkill.initial(userId, topicId, clock));
            UserSkill updated = current.applyEwma(scoreFraction, alphaEffective, clock);
            userSkillRepository.upsert(updated);
            deltas.add(new MasteryDelta(topicId, current.mastery(), updated.mastery()));
        }

        userRepository.bumpSkillsVersion(userId);

        Instant now = Instant.now(clock);
        outboxRepository.save(
                OutboxEvent.pending(
                        "QUIZ_ATTEMPTED",
                        persisted.attemptId().toString(),
                        serialiseEventPayload(persisted, attempt, deltas),
                        now));

        registerCacheInvalidationOnCommit(userId);

        return new SubmitQuizAttemptResult(
                persisted.attemptId(),
                attempt.score(),
                attempt.correctCount(),
                attempt.totalCount(),
                attempt.perItemFeedback(),
                List.copyOf(deltas));
    }

    private String serialiseEventPayload(
            PersistedQuizAttempt persisted, QuizAttempt attempt, List<MasteryDelta> deltas) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attemptId", persisted.attemptId());
        payload.put("userId", attempt.userId().value().toString());
        payload.put("quizContentId", attempt.quizContentId().value());
        payload.put("score", attempt.score());
        payload.put("attemptedAt", attempt.attemptedAt().toString());
        List<Map<String, Object>> deltaPayload = new ArrayList<>(deltas.size());
        for (MasteryDelta d : deltas) {
            Map<String, Object> dp = new LinkedHashMap<>();
            dp.put("topicId", d.topicId().value());
            dp.put("oldMastery", d.oldMastery().value());
            dp.put("newMastery", d.newMastery().value());
            deltaPayload.add(dp);
        }
        payload.put("deltas", deltaPayload);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise QUIZ_ATTEMPTED payload", e);
        }
    }

    private void registerCacheInvalidationOnCommit(UserId userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            topNCache.invalidate(userId);
                        }
                    });
        } else {
            // No active transaction — invalidate immediately. The
            // adapter swallows Redis failures, so this never throws.
            topNCache.invalidate(userId);
        }
    }
}
