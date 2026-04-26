package com.plrs.infrastructure.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.recommendation.Recommendation;
import com.plrs.domain.recommendation.RecommendationReason;
import com.plrs.domain.recommendation.RecommendationRepository;
import com.plrs.domain.recommendation.RecommendationScore;
import com.plrs.domain.user.UserId;
import com.plrs.infrastructure.testsupport.PostgresTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for {@link SpringDataRecommendationRepository}.
 * Drives the {@link RecommendationRepository} port to confirm Spring
 * wires the adapter, exercises the saveAll batch path, the targeted
 * recordClick UPDATE (including its earliest-click-wins idempotency),
 * the find lookup, and findRecent's order-and-limit contract.
 *
 * <p>Traces to: §3.a, §3.c.1.4, FR-26/27/29.
 */
@SpringBootTest(
        classes = SpringDataRecommendationRepositoryIT.RecsRepoITApp.class,
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
class SpringDataRecommendationRepositoryIT extends PostgresTestBase {

    private static final String VALID_BCRYPT =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    @Autowired private RecommendationRepository repository;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager em;

    private UserId userId;
    private ContentId contentA;
    private ContentId contentB;
    private ContentId contentC;

    @BeforeEach
    void seed() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            UUID uid = UUID.randomUUID();
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.users"
                            + " (id, email, password_hash, created_at, updated_at, created_by)"
                            + " VALUES (?, ?, ?, NOW(), NOW(), 'rec-it')")) {
                ps.setObject(1, uid);
                ps.setString(2, "rec-" + uid + "@example.com");
                ps.setString(3, VALID_BCRYPT);
                ps.executeUpdate();
            }
            userId = UserId.of(uid);

            long topicId;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                            + " VALUES (?, 'rec-it') RETURNING topic_id")) {
                ps.setString(1, "rec-topic-" + UUID.randomUUID());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    topicId = rs.getLong("topic_id");
                }
            }

            contentA = insertContent(conn, topicId);
            contentB = insertContent(conn, topicId);
            contentC = insertContent(conn, topicId);
        }
    }

    private static ContentId insertContent(Connection conn, long topicId) throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.content"
                        + " (topic_id, title, ctype, difficulty, est_minutes, url)"
                        + " VALUES (?, ?, 'VIDEO', 'BEGINNER', 5, 'https://x.y')"
                        + " RETURNING content_id")) {
            ps.setLong(1, topicId);
            ps.setString(2, "rec-content-" + UUID.randomUUID());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return ContentId.of(rs.getLong("content_id"));
            }
        }
    }

    private Recommendation rec(
            ContentId contentId, Instant servedAt, double score, int rank, String reason) {
        return Recommendation.rehydrate(
                userId,
                contentId,
                servedAt,
                RecommendationScore.of(score),
                rank,
                new RecommendationReason(reason),
                "popularity_v1",
                java.util.Optional.empty(),
                java.util.Optional.empty());
    }

    @Test
    void saveAllBatchPersistsEveryRow() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        repository.saveAll(
                List.of(
                        rec(contentA, t0, 0.90, 1, "rank 1"),
                        rec(contentB, t0, 0.70, 2, "rank 2"),
                        rec(contentC, t0, 0.50, 3, "rank 3")));
        em.flush();
        em.clear();

        List<Recommendation> recent = repository.findRecent(userId, 10);
        assertThat(recent).hasSize(3);
        assertThat(recent)
                .extracting(r -> r.contentId().value())
                .containsExactlyInAnyOrder(
                        contentA.value(), contentB.value(), contentC.value());
    }

    @Test
    void findReturnsTheRowKeyedOnComposite() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        repository.saveAll(List.of(rec(contentA, t0, 0.42, 1, "find me")));
        em.flush();
        em.clear();

        Recommendation loaded = repository.find(userId, contentA, t0).orElseThrow();
        assertThat(loaded.score().value()).isEqualTo(0.42);
        assertThat(loaded.reason().text()).isEqualTo("find me");
        assertThat(loaded.modelVariant()).isEqualTo("popularity_v1");

        assertThat(
                        repository.find(
                                userId, contentA, t0.plusSeconds(1)))
                .as("different createdAt → different row")
                .isEmpty();
    }

    @Test
    void findRecentLimitsAndOrdersDesc() {
        Instant base = Instant.parse("2026-04-25T10:00:00Z");
        repository.saveAll(
                List.of(
                        rec(contentA, base.minusSeconds(300), 0.5, 1, "old"),
                        rec(contentA, base.minusSeconds(60), 0.6, 1, "newer"),
                        rec(contentA, base, 0.7, 1, "newest")));
        em.flush();
        em.clear();

        List<Recommendation> top2 = repository.findRecent(userId, 2);
        assertThat(top2).hasSize(2);
        assertThat(top2.get(0).reason().text()).isEqualTo("newest");
        assertThat(top2.get(1).reason().text()).isEqualTo("newer");
    }

    @Test
    void findRecentZeroLimitReturnsEmptyWithoutQuery() {
        // Spec contract: limit == 0 short-circuits to empty. No DB hit.
        assertThat(repository.findRecent(userId, 0)).isEmpty();
    }

    @Test
    void findRecentNegativeLimitRejected() {
        // The @Repository advice wraps IllegalArgumentException into
        // InvalidDataAccessApiUsageException (Spring's standard
        // persistence-translation behaviour). Match on the message
        // instead of the wrapper class.
        assertThatThrownBy(() -> repository.findRecent(userId, -1))
                .hasMessageContaining("limit must be >= 0");
    }

    @Test
    void recordClickStampsTheRowOnce() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        repository.saveAll(List.of(rec(contentA, t0, 0.5, 1, "ok")));
        em.flush();

        Instant click = t0.plusSeconds(60);
        repository.recordClick(userId, contentA, t0, click);
        em.flush();
        em.clear();

        Recommendation clicked = repository.find(userId, contentA, t0).orElseThrow();
        assertThat(clicked.clickedAt()).contains(click);

        // Earliest-click-wins: a second recordClick with a later
        // timestamp does NOT overwrite the original.
        repository.recordClick(userId, contentA, t0, click.plusSeconds(120));
        em.flush();
        em.clear();
        Recommendation again = repository.find(userId, contentA, t0).orElseThrow();
        assertThat(again.clickedAt()).contains(click);
    }

    @Test
    void recordClickOnUnknownRowIsNoOp() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        // No rec was saved, but the call must not throw.
        repository.recordClick(userId, contentA, t0, t0.plusSeconds(60));
        em.flush();

        assertThat(repository.find(userId, contentA, t0)).isEmpty();
    }

    @Test
    void saveAllRejectsDuplicateCompositeKey() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        repository.saveAll(List.of(rec(contentA, t0, 0.5, 1, "first")));
        em.flush();

        assertThatThrownBy(
                        () -> {
                            repository.saveAll(List.of(rec(contentA, t0, 0.7, 2, "second")));
                            em.flush();
                        })
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @SpringBootApplication(
            scanBasePackages = {
                "com.plrs.infrastructure.recommendation",
                "com.plrs.infrastructure.user",
                "com.plrs.infrastructure.content",
                "com.plrs.infrastructure.topic",
                "com.plrs.infrastructure.warehouse"
            },
            exclude = {
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    @org.springframework.boot.autoconfigure.domain.EntityScan(
            basePackages = {
                "com.plrs.infrastructure.recommendation",
                "com.plrs.infrastructure.user",
                "com.plrs.infrastructure.content",
                "com.plrs.infrastructure.topic"
            })
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(
            basePackages = {
                "com.plrs.infrastructure.recommendation",
                "com.plrs.infrastructure.user",
                "com.plrs.infrastructure.content",
                "com.plrs.infrastructure.topic"
            })
    static class RecsRepoITApp {}
}
