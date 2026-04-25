package com.plrs.infrastructure.quiz;

import static org.assertj.core.api.Assertions.assertThat;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.quiz.PerItemFeedback;
import com.plrs.domain.quiz.QuizAttempt;
import com.plrs.domain.quiz.QuizAttemptRepository;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import com.plrs.infrastructure.testsupport.PostgresTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for {@link SpringDataQuizAttemptRepository}.
 * Drives the {@link QuizAttemptRepository} port to confirm Spring wires
 * the adapter, exercises save / findById / findRecentByUser /
 * existsByUserAndContent, and verifies the JSONB round-trip carries
 * both {@code perItemFeedback} and {@code topicWeights}.
 *
 * <p>Traces to: §3.c.1.4, FR-20.
 */
@SpringBootTest(
        classes = SpringDataQuizAttemptRepositoryIT.QuizAttemptRepoITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=plrs_ops"
        })
@Transactional
class SpringDataQuizAttemptRepositoryIT extends PostgresTestBase {

    private static final String VALID_BCRYPT =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    @Autowired private QuizAttemptRepository quizAttemptRepository;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager em;

    private UserId userId;
    private ContentId quizContentId;
    private TopicId topicId;

    @BeforeEach
    void seed() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            UUID uid = UUID.randomUUID();
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.users"
                            + " (id, email, password_hash, created_at, updated_at, created_by)"
                            + " VALUES (?, ?, ?, NOW(), NOW(), 'test')")) {
                ps.setObject(1, uid);
                ps.setString(2, "qarepo-" + uid + "@example.com");
                ps.setString(3, VALID_BCRYPT);
                ps.executeUpdate();
            }
            userId = UserId.of(uid);

            long tid;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                            + " VALUES (?, 'test') RETURNING topic_id")) {
                ps.setString(1, "qarepo-topic-" + UUID.randomUUID());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    tid = rs.getLong("topic_id");
                }
            }
            topicId = TopicId.of(tid);

            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.content"
                            + " (topic_id, title, ctype, difficulty, est_minutes, url)"
                            + " VALUES (?, ?, 'QUIZ', 'BEGINNER', 10, 'https://x.y')"
                            + " RETURNING content_id")) {
                ps.setLong(1, tid);
                ps.setString(2, "qarepo-content-" + UUID.randomUUID());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    quizContentId = ContentId.of(rs.getLong("content_id"));
                }
            }
        }
    }

    private QuizAttempt buildAttempt(Instant at, BigDecimal score, int correct, int total) {
        return new QuizAttempt(
                userId,
                quizContentId,
                score,
                correct,
                total,
                List.of(
                        new PerItemFeedback(1, 1, 1, true, topicId),
                        new PerItemFeedback(2, 2, 1, false, topicId)),
                Map.of(topicId, BigDecimal.ONE.setScale(3)),
                at);
    }

    @Test
    void saveReturnsAttemptValueEqualToInput() {
        QuizAttempt attempt =
                buildAttempt(
                        Instant.parse("2026-04-25T10:00:00Z"),
                        new BigDecimal("50.00"),
                        1,
                        2);

        QuizAttempt saved = quizAttemptRepository.save(attempt);
        em.flush();

        assertThat(saved.score()).isEqualByComparingTo("50.00");
        assertThat(saved.correctCount()).isEqualTo(1);
        assertThat(saved.totalCount()).isEqualTo(2);
        assertThat(saved.userId()).isEqualTo(userId);
        assertThat(saved.quizContentId()).isEqualTo(quizContentId);
    }

    @Test
    void findByIdRoundTripsScoreAndPerItemFeedback() {
        QuizAttempt attempt =
                buildAttempt(
                        Instant.parse("2026-04-25T10:00:00Z"),
                        new BigDecimal("50.00"),
                        1,
                        2);
        quizAttemptRepository.save(attempt);
        em.flush();
        em.clear();

        // Surrogate id via the same persistence context (@Transactional sees
        // its own uncommitted writes; a separate JDBC Connection would not).
        Long attemptId =
                ((Number) em.createNativeQuery(
                                        "SELECT attempt_id FROM plrs_ops.quiz_attempts"
                                                + " WHERE user_id = :uid AND content_id = :cid")
                                .setParameter("uid", userId.value())
                                .setParameter("cid", quizContentId.value())
                                .getSingleResult())
                        .longValue();

        QuizAttempt loaded = quizAttemptRepository.findById(attemptId).orElseThrow();

        assertThat(loaded.score()).isEqualByComparingTo("50.00");
        assertThat(loaded.correctCount()).isEqualTo(1);
        assertThat(loaded.totalCount()).isEqualTo(2);
        assertThat(loaded.perItemFeedback()).hasSize(2);
        assertThat(loaded.perItemFeedback().get(0).isCorrect()).isTrue();
        assertThat(loaded.perItemFeedback().get(1).isCorrect()).isFalse();
    }

    @Test
    void perItemJsonSerialisesTopicWeightsAndPerItemFeedback() {
        QuizAttempt attempt =
                buildAttempt(
                        Instant.parse("2026-04-25T10:00:00Z"),
                        new BigDecimal("50.00"),
                        1,
                        2);
        quizAttemptRepository.save(attempt);
        em.flush();

        Object[] row =
                (Object[])
                        em.createNativeQuery(
                                        "SELECT per_item_json->>'per_item' AS p,"
                                                + "       per_item_json->>'topic_weights' AS w"
                                                + "  FROM plrs_ops.quiz_attempts"
                                                + " WHERE user_id = :uid AND content_id = :cid")
                                .setParameter("uid", userId.value())
                                .setParameter("cid", quizContentId.value())
                                .getSingleResult();
        assertThat((String) row[0]).contains("itemOrder").contains("isCorrect");
        assertThat((String) row[1]).contains(String.valueOf(topicId.value()));
    }

    @Test
    void findRecentByUserReturnsDescendingByAttemptedAt() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        quizAttemptRepository.save(buildAttempt(t0, new BigDecimal("10.00"), 0, 2));
        quizAttemptRepository.save(
                buildAttempt(t0.plusSeconds(60), new BigDecimal("20.00"), 0, 2));
        quizAttemptRepository.save(
                buildAttempt(t0.plusSeconds(120), new BigDecimal("30.00"), 1, 2));
        em.flush();

        List<QuizAttempt> recent = quizAttemptRepository.findRecentByUser(userId, 2);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).score()).isEqualByComparingTo("30.00");
        assertThat(recent.get(1).score()).isEqualByComparingTo("20.00");
    }

    @Test
    void existsByUserAndContentTrueAfterSave() {
        assertThat(quizAttemptRepository.existsByUserAndContent(userId, quizContentId)).isFalse();

        quizAttemptRepository.save(
                buildAttempt(
                        Instant.parse("2026-04-25T10:00:00Z"),
                        new BigDecimal("100.00"),
                        2,
                        2));
        em.flush();

        assertThat(quizAttemptRepository.existsByUserAndContent(userId, quizContentId)).isTrue();
    }

    @SpringBootApplication(
            exclude = {
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    static class QuizAttemptRepoITApp {

        @org.springframework.context.annotation.Bean
        public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }
    }
}
