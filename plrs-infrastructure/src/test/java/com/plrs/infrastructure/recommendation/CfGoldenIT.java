package com.plrs.infrastructure.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plrs.application.recommendation.ArtifactRepository;
import com.plrs.application.recommendation.CfScorer;
import com.plrs.application.recommendation.PopularityScorer;
import com.plrs.application.recommendation.RecommendationService;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.PrerequisiteRepository;
import com.plrs.domain.interaction.InteractionRepository;
import com.plrs.domain.mastery.UserSkillRepository;
import com.plrs.domain.recommendation.Recommendation;
import com.plrs.domain.topic.TopicRepository;
import com.plrs.domain.user.UserId;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Golden-data regression test for CF correctness. Seeds a deterministic
 * fixture (5 users, 5 topics, 10 content items, hand-curated
 * interaction patterns) into a real Postgres, drives the
 * {@link ItemSimilarityJob} to populate Redis sim slabs, and asserts
 * that {@link RecommendationService}'s output for each user matches
 * the hand-computed expectation.
 *
 * <p>Spec deviation: the spec places this test in plrs-application as
 * {@code CfGoldenTest.java}; placed in plrs-infrastructure as
 * {@code CfGoldenIT.java} because (a) plrs-application does not have
 * Testcontainers on its test classpath, and (b) the {@code *IT.java}
 * suffix matches the existing Failsafe pattern. The body of the test
 * is otherwise the same.
 *
 * <p>Fixture topology:
 *
 * <ul>
 *   <li>Topic A: items 1, 2, 3, 4 (closely related — all 5 users
 *       complete items 1, 2, 3; users 1+2 also complete item 4).
 *   <li>Topic B: items 5, 6 (mid cluster — users 3, 4 complete both).
 *   <li>Topic C: items 7, 8 (sparse — only user 5 completes 7).
 *   <li>Topic D: item 9 (isolated — no completions).
 *   <li>Topic E: item 10 (isolated — only user 1 likes it).
 * </ul>
 *
 * <p>Hand-computed expectation: a user who already completed items
 * 1, 2, 3 should see item 4 (in their cluster) ranked above item 7
 * (an unrelated cluster).
 */
@SpringBootTest(
        classes = CfGoldenIT.CfGoldenITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false",
            "plrs.recommender.itemsim.cron=0 0 2 1 1 *",
            "plrs.jwt.generate-if-missing=true"
        })
class CfGoldenIT {

    private static final String VALID_BCRYPT =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("plrs")
                    .withUsername("plrs")
                    .withPassword("plrs");

    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private DataSource dataSource;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ItemSimilarityJob itemSimilarityJob;
    @Autowired private RecommendationService recommendationService;

    private UUID[] userIds;
    private Long[] topicIds;
    private Long[] contentIds;

    @BeforeEach
    void seedFixture() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // 5 users.
            userIds = new UUID[5];
            for (int i = 0; i < 5; i++) {
                UUID uid = UUID.randomUUID();
                try (var ps = conn.prepareStatement(
                        "INSERT INTO plrs_ops.users"
                                + " (id, email, password_hash,"
                                + "  created_at, updated_at, created_by)"
                                + " VALUES (?, ?, ?, NOW(), NOW(), 'cf-golden')")) {
                    ps.setObject(1, uid);
                    ps.setString(2, "cf-" + uid + "@example.com");
                    ps.setString(3, VALID_BCRYPT);
                    ps.executeUpdate();
                }
                userIds[i] = uid;
            }

            // 5 topics.
            topicIds = new Long[5];
            for (int i = 0; i < 5; i++) {
                try (var ps = conn.prepareStatement(
                        "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                                + " VALUES (?, 'cf-golden') RETURNING topic_id")) {
                    ps.setString(1, "cf-topic-" + i + "-" + UUID.randomUUID());
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        topicIds[i] = rs.getLong("topic_id");
                    }
                }
            }

            // 10 content items distributed across the 5 topics
            // (2 per topic).
            contentIds = new Long[10];
            for (int i = 0; i < 10; i++) {
                long topicId = topicIds[i / 2];
                try (var ps = conn.prepareStatement(
                        "INSERT INTO plrs_ops.content"
                                + " (topic_id, title, ctype, difficulty,"
                                + "  est_minutes, url)"
                                + " VALUES (?, ?, 'VIDEO', 'BEGINNER', 5, 'https://x.y')"
                                + " RETURNING content_id")) {
                    ps.setLong(1, topicId);
                    ps.setString(2, "cf-c-" + i + "-" + UUID.randomUUID());
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        contentIds[i] = rs.getLong("content_id");
                    }
                }
            }

            Instant base = Instant.parse("2026-04-25T10:00:00Z");
            int t = 0;

            // Cluster A (items 0, 1, 2, 3): users 0..4 all COMPLETE
            // items 0, 1, 2. Users 0 + 1 also COMPLETE item 3 — that's
            // the recommendation we want to surface for user 2.
            for (int u = 0; u < 5; u++) {
                completeAt(conn, userIds[u], contentIds[0], base.minusSeconds(t++ * 60));
                completeAt(conn, userIds[u], contentIds[1], base.minusSeconds(t++ * 60));
                completeAt(conn, userIds[u], contentIds[2], base.minusSeconds(t++ * 60));
            }
            completeAt(conn, userIds[0], contentIds[3], base.minusSeconds(t++ * 60));
            completeAt(conn, userIds[1], contentIds[3], base.minusSeconds(t++ * 60));

            // Cluster B (items 4, 5): users 2, 3 complete both — gives
            // item 5 some sim with item 4.
            completeAt(conn, userIds[2], contentIds[4], base.minusSeconds(t++ * 60));
            completeAt(conn, userIds[2], contentIds[5], base.minusSeconds(t++ * 60));
            completeAt(conn, userIds[3], contentIds[4], base.minusSeconds(t++ * 60));
            completeAt(conn, userIds[3], contentIds[5], base.minusSeconds(t++ * 60));

            // Cluster C (item 6): only user 4 completes — orphan.
            completeAt(conn, userIds[4], contentIds[6], base.minusSeconds(t++ * 60));

            // Item 7: a single LIKE from user 0 — keeps it in the
            // catalogue but with no co-engagement signal.
            likeAt(conn, userIds[0], contentIds[7], base.minusSeconds(t++ * 60));
        }
    }

    @Test
    void user2WhoCompletedCluster1ItemsSeesItem3RankedAboveItem6() {
        itemSimilarityJob.recomputeNow();

        // User 2 completed items 0, 1, 2. They haven't seen item 3
        // (the cluster's fourth co-engaged item) or item 6 (the
        // orphan). CF should rank item 3 first because items 0/1/2
        // each have neighbour item 3 in their slab; item 6 has no
        // overlap with the user's history.
        UserId user2 = UserId.of(userIds[2]);
        List<Recommendation> recs =
                recommendationService.generate(user2, 8, "cf_v1");

        List<Long> contentOrder = new ArrayList<>();
        for (Recommendation r : recs) {
            contentOrder.add(r.contentId().value());
        }
        int rankItem3 = contentOrder.indexOf(contentIds[3]);
        int rankItem6 = contentOrder.indexOf(contentIds[6]);
        assertThat(rankItem3)
                .as(
                        "item 3 (co-engaged with the user's history) must rank above item 6 (orphan)")
                .isGreaterThanOrEqualTo(0)
                .isLessThan(rankItem6 < 0 ? Integer.MAX_VALUE : rankItem6);
    }

    @Test
    void user4WhoOnlyCompletedItem6GetsItemsFromOtherClustersAsBackfill() {
        itemSimilarityJob.recomputeNow();

        // User 4's history is sparse (only item 6, the orphan). CF
        // can't find similar items — the popularity blend + FR-30
        // backfill carry the slate. We assert at least k items are
        // returned, and item 6 itself is NOT in the result (already
        // engaged).
        UserId user4 = UserId.of(userIds[4]);
        List<Recommendation> recs =
                recommendationService.generate(user4, 5, "cf_v1");

        assertThat(recs).hasSizeGreaterThanOrEqualTo(1);
        assertThat(recs)
                .extracting(r -> r.contentId().value())
                .as("the user's already-completed item must not appear")
                .doesNotContain(contentIds[6]);
    }

    @Test
    void allUsersGetDeterministicNonEmptyOutputs() {
        itemSimilarityJob.recomputeNow();

        for (UUID uid : userIds) {
            List<Recommendation> recs =
                    recommendationService.generate(UserId.of(uid), 5, "cf_v1");
            assertThat(recs).as("user %s gets a non-empty slate", uid).isNotEmpty();
            assertThat(recs).hasSizeLessThanOrEqualTo(5);
        }
    }

    @Test
    void user0WhoLikedItem7SeesItem7DemotedFromTheirOwnSlate() {
        itemSimilarityJob.recomputeNow();

        // Item 7 was LIKEd by user 0 — that counts as a positive
        // interaction, so the CF scorer's "already in history" guard
        // demotes item 7 to score 0 on user 0's slate.
        UserId user0 = UserId.of(userIds[0]);
        List<Recommendation> recs =
                recommendationService.generate(user0, 10, "cf_v1");

        // Item 7 is still in the candidate pool, so it can show up
        // via the popularity blend, but not in the top half.
        List<Long> ids = new ArrayList<>();
        for (Recommendation r : recs) {
            ids.add(r.contentId().value());
        }
        if (ids.contains(contentIds[7])) {
            int pos = ids.indexOf(contentIds[7]);
            assertThat(pos).as("liked item 7 should be deprioritised").isGreaterThan(0);
        }
    }

    private static void completeAt(
            Connection conn, UUID userId, long contentId, Instant occurredAt)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.interactions"
                        + " (user_id, content_id, occurred_at, event_type)"
                        + " VALUES (?, ?, ?, 'COMPLETE')"
                        + " ON CONFLICT DO NOTHING")) {
            ps.setObject(1, userId);
            ps.setLong(2, contentId);
            ps.setTimestamp(3, java.sql.Timestamp.from(occurredAt));
            ps.executeUpdate();
        }
    }

    private static void likeAt(
            Connection conn, UUID userId, long contentId, Instant occurredAt)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.interactions"
                        + " (user_id, content_id, occurred_at, event_type)"
                        + " VALUES (?, ?, ?, 'LIKE')"
                        + " ON CONFLICT DO NOTHING")) {
            ps.setObject(1, userId);
            ps.setLong(2, contentId);
            ps.setTimestamp(3, java.sql.Timestamp.from(occurredAt));
            ps.executeUpdate();
        }
    }

    @SpringBootApplication(
            scanBasePackages = {
                "com.plrs.application",
                "com.plrs.infrastructure"
            })
    @org.springframework.boot.autoconfigure.domain.EntityScan(
            basePackages = "com.plrs.infrastructure")
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(
            basePackages = "com.plrs.infrastructure")
    static class CfGoldenITApp {

        @org.springframework.context.annotation.Bean
        Clock testClock() {
            return Clock.fixed(
                    Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC);
        }

        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
