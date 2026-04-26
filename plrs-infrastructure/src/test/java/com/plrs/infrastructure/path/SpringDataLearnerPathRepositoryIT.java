package com.plrs.infrastructure.path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.path.LearnerPath;
import com.plrs.domain.path.LearnerPathDraft;
import com.plrs.domain.path.LearnerPathRepository;
import com.plrs.domain.path.LearnerPathStatus;
import com.plrs.domain.path.LearnerPathStep;
import com.plrs.domain.path.PathId;
import com.plrs.domain.path.StepStatus;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import com.plrs.infrastructure.testsupport.PostgresTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HashMap;
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
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * IT for {@link SpringDataLearnerPathRepository}: round-trips JSONB
 * snapshots, exercises the partial-unique conflict on a second active
 * row for the same (user, target), and confirms findById /
 * findActiveByUserAndTarget / findRecentByUser hit the right rows.
 *
 * <p>Traces to: §3.a, §3.c.1.4, §3.b.4.3, FR-31.
 */
@SpringBootTest(
        classes = SpringDataLearnerPathRepositoryIT.PathRepoITApp.class,
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
class SpringDataLearnerPathRepositoryIT extends PostgresTestBase {

    private static final String VALID_BCRYPT =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    @Autowired private LearnerPathRepository repository;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager em;

    private UserId userId;
    private TopicId targetTopic;
    private ContentId contentA;
    private ContentId contentB;

    @BeforeEach
    void seed() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            UUID uid = UUID.randomUUID();
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.users"
                            + " (id, email, password_hash, created_at, updated_at, created_by)"
                            + " VALUES (?, ?, ?, NOW(), NOW(), 'path-it')")) {
                ps.setObject(1, uid);
                ps.setString(2, "path-" + uid + "@example.com");
                ps.setString(3, VALID_BCRYPT);
                ps.executeUpdate();
            }
            userId = UserId.of(uid);

            long topicId;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                            + " VALUES (?, 'path-it') RETURNING topic_id")) {
                ps.setString(1, "path-topic-" + UUID.randomUUID());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    topicId = rs.getLong("topic_id");
                }
            }
            targetTopic = TopicId.of(topicId);

            contentA = insertContent(conn, topicId, "A " + UUID.randomUUID());
            contentB = insertContent(conn, topicId, "B " + UUID.randomUUID());
        }
    }

    private ContentId insertContent(Connection conn, long topicId, String title) throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.content"
                        + " (topic_id, title, ctype, difficulty, est_minutes, url)"
                        + " VALUES (?, ?, 'VIDEO', 'BEGINNER', 5, 'https://x.y')"
                        + " RETURNING content_id")) {
            ps.setLong(1, topicId);
            ps.setString(2, title);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return ContentId.of(rs.getLong("content_id"));
            }
        }
    }

    private LearnerPathDraft draft() {
        Map<TopicId, MasteryScore> snap = new HashMap<>();
        snap.put(targetTopic, MasteryScore.of(0.4));
        return new LearnerPathDraft(
                userId,
                targetTopic,
                List.of(
                        LearnerPathStep.pending(1, contentA, false, "Prereq"),
                        LearnerPathStep.pending(2, contentB, true, "Review")),
                snap);
    }

    @Test
    void saveAndReadBackRoundTripsAllFields() {
        LearnerPath saved = repository.save(draft());
        em.flush();
        em.clear();

        LearnerPath reloaded = repository.findById(saved.id().orElseThrow()).orElseThrow();

        assertThat(reloaded.id()).isEqualTo(saved.id());
        assertThat(reloaded.userId()).isEqualTo(userId);
        assertThat(reloaded.targetTopicId()).isEqualTo(targetTopic);
        assertThat(reloaded.status()).isEqualTo(LearnerPathStatus.NOT_STARTED);
        assertThat(reloaded.steps()).hasSize(2);
        assertThat(reloaded.steps().get(0).contentId()).isEqualTo(contentA);
        assertThat(reloaded.steps().get(0).addedAsReview()).isFalse();
        assertThat(reloaded.steps().get(1).addedAsReview()).isTrue();
        assertThat(reloaded.masteryStartSnapshot())
                .containsEntry(targetTopic, MasteryScore.of(0.4));
        assertThat(reloaded.masteryEndSnapshot()).isEmpty();
    }

    @Test
    void updateReplacesStepSetAndStatus() {
        LearnerPath saved = repository.save(draft());
        em.flush();
        em.clear();

        LearnerPath running =
                saved.start(Clock.systemUTC()).markStepDone(1, Clock.systemUTC());
        repository.update(running);
        em.flush();
        em.clear();

        LearnerPath reloaded = repository.findById(saved.id().orElseThrow()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(LearnerPathStatus.IN_PROGRESS);
        assertThat(reloaded.startedAt()).isPresent();
        assertThat(reloaded.steps().get(0).status()).isEqualTo(StepStatus.DONE);
        assertThat(reloaded.steps().get(0).completedAt()).isPresent();
        assertThat(reloaded.steps().get(1).status()).isEqualTo(StepStatus.PENDING);
    }

    @Test
    void completePersistsEndSnapshotJsonb() {
        LearnerPath running = repository.save(draft()).start(Clock.systemUTC());
        Map<TopicId, MasteryScore> end = new HashMap<>();
        end.put(targetTopic, MasteryScore.of(0.85));
        LearnerPath done = running.complete(end, Clock.systemUTC());

        repository.update(done);
        em.flush();
        em.clear();

        LearnerPath reloaded = repository.findById(done.id().orElseThrow()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(LearnerPathStatus.COMPLETED);
        assertThat(reloaded.masteryEndSnapshot())
                .isPresent()
                .get()
                .extracting(m -> m.get(targetTopic))
                .isEqualTo(MasteryScore.of(0.85));
    }

    @Test
    void findActiveReturnsTheActivePathOnly() {
        LearnerPath active = repository.save(draft());
        em.flush();

        assertThat(repository.findActiveByUserAndTarget(userId, targetTopic))
                .isPresent()
                .get()
                .extracting(p -> p.id().orElseThrow())
                .isEqualTo(active.id().orElseThrow());

        // Mark abandoned → no active row.
        repository.update(active.abandon(Clock.systemUTC()));
        em.flush();
        assertThat(repository.findActiveByUserAndTarget(userId, targetTopic)).isEmpty();
    }

    @Test
    void partialUniqueRejectsSecondActivePath() {
        repository.save(draft());
        em.flush();

        assertThatThrownBy(
                        () -> {
                            repository.save(draft());
                            em.flush();
                        })
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findRecentByUserOrdersByGeneratedAtDesc() {
        LearnerPath p1 = repository.save(draft());
        em.flush();
        // Mark first abandoned so the second save doesn't trip the partial unique.
        repository.update(p1.abandon(Clock.systemUTC()));
        em.flush();
        LearnerPath p2 = repository.save(draft());
        em.flush();

        List<LearnerPath> recent = repository.findRecentByUser(userId, 5);
        assertThat(recent).hasSize(2);
        // The most recent (p2) must come first.
        assertThat(recent.get(0).id().orElseThrow()).isEqualTo(p2.id().orElseThrow());
        assertThat(recent.get(1).id().orElseThrow()).isEqualTo(p1.id().orElseThrow());
    }

    @Test
    void findByIdMissingReturnsEmpty() {
        assertThat(repository.findById(PathId.of(999_999L))).isEmpty();
    }

    @SpringBootApplication(
            scanBasePackages = {"com.plrs.infrastructure.path", "com.plrs.infrastructure.user"},
            exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
    @org.springframework.boot.autoconfigure.domain.EntityScan(
            basePackages = {"com.plrs.infrastructure.path", "com.plrs.infrastructure.user"})
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(
            basePackages = {"com.plrs.infrastructure.path", "com.plrs.infrastructure.user"})
    static class PathRepoITApp {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }
    }
}
