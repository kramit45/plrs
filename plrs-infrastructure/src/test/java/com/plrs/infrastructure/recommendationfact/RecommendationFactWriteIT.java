package com.plrs.infrastructure.recommendationfact;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * IT for the Iter 4 behaviour added in step 152: when the
 * {@link RecommendationRepository#saveAll} writes to ops, it also
 * mirrors a row to {@code plrs_dw.fact_recommendation} so the FR-36
 * KPI views see the slate without waiting for the Kafka → ETL hop.
 *
 * <p>Verifies the fact row appears, the dim_* rows are lazily created
 * when missing, and {@code recordClick} mirrors {@code was_clicked}
 * back to the fact row.
 *
 * <p>Traces to: FR-36, §3.c.2.
 */
@SpringBootTest(
        classes = RecommendationFactWriteIT.FactITApp.class,
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
class RecommendationFactWriteIT extends PostgresTestBase {

    private static final String VALID_BCRYPT =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    @Autowired private RecommendationRepository repository;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager em;

    private UserId userId;
    private ContentId contentId;
    private long topicIdRaw;

    @BeforeEach
    void seed() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            UUID uid = UUID.randomUUID();
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.users"
                            + " (id, email, password_hash, created_at, updated_at, created_by)"
                            + " VALUES (?, ?, ?, NOW(), NOW(), 'fact-it')")) {
                ps.setObject(1, uid);
                ps.setString(2, "fact-" + uid + "@example.com");
                ps.setString(3, VALID_BCRYPT);
                ps.executeUpdate();
            }
            userId = UserId.of(uid);

            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                            + " VALUES (?, 'fact-it') RETURNING topic_id")) {
                ps.setString(1, "fact-topic-" + UUID.randomUUID());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    topicIdRaw = rs.getLong("topic_id");
                }
            }

            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.content"
                            + " (topic_id, title, ctype, difficulty, est_minutes, url)"
                            + " VALUES (?, ?, 'VIDEO', 'BEGINNER', 5, 'https://x.y')"
                            + " RETURNING content_id")) {
                ps.setLong(1, topicIdRaw);
                ps.setString(2, "fact-content-" + UUID.randomUUID());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    contentId = ContentId.of(rs.getLong("content_id"));
                }
            }
        }
    }

    private Recommendation rec(Instant t0, double score, int rank) {
        return Recommendation.rehydrate(
                userId,
                contentId,
                t0,
                RecommendationScore.of(score),
                rank,
                new RecommendationReason("Popular among learners"),
                "popularity_v1",
                Optional.empty(),
                Optional.empty());
    }

    private long countNative(String sql) {
        // Same EntityManager session = sees uncommitted writes from this @Transactional test.
        Object n = em.createNativeQuery(sql).getSingleResult();
        return ((Number) n).longValue();
    }

    @Test
    void saveAllAlsoWritesFactRecommendationRow() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        repository.saveAll(List.of(rec(t0, 0.7, 1)));
        em.flush();

        long n =
                countNative(
                        "SELECT COUNT(*) FROM plrs_dw.fact_recommendation"
                                + " WHERE created_at = '" + t0 + "'");
        assertThat(n).as("fact row mirrored from ops save").isEqualTo(1L);
    }

    @Test
    void dimRowsAreLazilyCreatedWhenMissing() {
        Instant t0 = Instant.parse("2026-04-25T11:00:00Z");
        repository.saveAll(List.of(rec(t0, 0.8, 1)));
        em.flush();

        assertThat(
                        countNative(
                                "SELECT COUNT(*) FROM plrs_dw.dim_user"
                                        + " WHERE user_id = '" + userId.value() + "'"))
                .isEqualTo(1L);
        assertThat(
                        countNative(
                                "SELECT COUNT(*) FROM plrs_dw.dim_content"
                                        + " WHERE content_id = " + contentId.value()))
                .isEqualTo(1L);
        assertThat(
                        countNative(
                                "SELECT COUNT(*) FROM plrs_dw.dim_topic"
                                        + " WHERE topic_id = " + topicIdRaw))
                .isEqualTo(1L);
    }

    @Test
    void recordClickMirrorsToFactWasClicked() {
        Instant t0 = Instant.parse("2026-04-25T12:00:00Z");
        repository.saveAll(List.of(rec(t0, 0.9, 1)));
        em.flush();

        Instant click = t0.plusSeconds(60);
        repository.recordClick(userId, contentId, t0, click);
        em.flush();

        Object clicked =
                em.createNativeQuery(
                                "SELECT was_clicked FROM plrs_dw.fact_recommendation"
                                        + " WHERE created_at = '" + t0 + "'"
                                        + "   AND user_sk = (SELECT user_sk FROM plrs_dw.dim_user"
                                        + "                  WHERE user_id = '" + userId.value() + "')")
                        .getSingleResult();
        assertThat((Boolean) clicked).isTrue();
    }

    @SpringBootApplication(
            scanBasePackages = {
                "com.plrs.infrastructure.recommendation",
                "com.plrs.infrastructure.user",
                "com.plrs.infrastructure.topic",
                "com.plrs.infrastructure.content",
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
                "com.plrs.infrastructure.topic",
                "com.plrs.infrastructure.content"
            })
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(
            basePackages = {
                "com.plrs.infrastructure.recommendation",
                "com.plrs.infrastructure.user",
                "com.plrs.infrastructure.topic",
                "com.plrs.infrastructure.content"
            })
    static class FactITApp {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
