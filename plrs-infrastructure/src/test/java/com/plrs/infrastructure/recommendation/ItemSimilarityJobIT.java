package com.plrs.infrastructure.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * End-to-end test of {@link ItemSimilarityJob}: seeds 5 users + 6
 * content items + 30 interactions into a real Postgres (the same
 * container Iter 2 ITs use), runs {@link ItemSimilarityJob#recomputeNow},
 * then asserts the {@code sim:item:*} keys are present in Redis with
 * non-trivial neighbours.
 *
 * <p>Combines Postgres + Redis containers in-class (rather than
 * extending two TestBases) — the same approach as the Playwright IT.
 */
@SpringBootTest(
        classes = ItemSimilarityJobIT.SimJobITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false",
            // Cron is "0 30 2 * * *" — runs once a day at 02:30. The
            // bean stays enabled (matchIfMissing=true) so the test can
            // autowire it; the schedule won't fire during the test run.
            "plrs.recommender.itemsim.cron=0 0 2 1 1 *"
        })
class ItemSimilarityJobIT {

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
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemSimilarityJob job;

    private Long[] contentIds;
    private UUID[] userIds;

    @BeforeEach
    void seed() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // 5 users.
            userIds = new UUID[5];
            for (int i = 0; i < 5; i++) {
                UUID uid = UUID.randomUUID();
                try (var ps = conn.prepareStatement(
                        "INSERT INTO plrs_ops.users"
                                + " (id, email, password_hash,"
                                + "  created_at, updated_at, created_by)"
                                + " VALUES (?, ?, ?, NOW(), NOW(), 'sim-it')")) {
                    ps.setObject(1, uid);
                    ps.setString(2, "sim-" + uid + "@example.com");
                    ps.setString(3, VALID_BCRYPT);
                    ps.executeUpdate();
                }
                userIds[i] = uid;
            }

            // 1 topic.
            long topicId;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                            + " VALUES (?, 'sim-it') RETURNING topic_id")) {
                ps.setString(1, "sim-topic-" + UUID.randomUUID());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    topicId = rs.getLong("topic_id");
                }
            }

            // 6 content items.
            contentIds = new Long[6];
            for (int i = 0; i < 6; i++) {
                try (var ps = conn.prepareStatement(
                        "INSERT INTO plrs_ops.content"
                                + " (topic_id, title, ctype, difficulty,"
                                + "  est_minutes, url)"
                                + " VALUES (?, ?, 'VIDEO', 'BEGINNER', 5, 'https://x.y')"
                                + " RETURNING content_id")) {
                    ps.setLong(1, topicId);
                    ps.setString(2, "sim-c-" + i + "-" + UUID.randomUUID());
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        contentIds[i] = rs.getLong("content_id");
                    }
                }
            }

            // 30 interactions: users 0..4 mostly co-engage with items 0+1
            // (-> high cosine), and users 0..4 occasionally touch other
            // items so the cosine for orthogonal pairs stays low.
            int[] dwellSeconds = new int[] {30, 60, 120, 180};
            int eventCounter = 0;
            for (int u = 0; u < 5; u++) {
                // Strong overlap on items 0 and 1.
                insertInteraction(
                        conn, userIds[u], contentIds[0],
                        secondsAgoToInstant(eventCounter++ * 60),
                        "COMPLETE", null);
                insertInteraction(
                        conn, userIds[u], contentIds[1],
                        secondsAgoToInstant(eventCounter++ * 60),
                        "COMPLETE", null);
                // User-specific extras for the remaining items.
                insertInteraction(
                        conn, userIds[u], contentIds[2 + (u % 4)],
                        secondsAgoToInstant(eventCounter++ * 60),
                        "VIEW", dwellSeconds[u % dwellSeconds.length]);
            }
            // 30 = 5 * (2 COMPLETEs + 1 VIEW + 3 padding events spread)
            // Pad to exactly 30 interactions with LIKE events on item 0.
            while (eventCounter < 30) {
                int u = eventCounter % 5;
                insertInteraction(
                        conn, userIds[u], contentIds[0],
                        secondsAgoToInstant(eventCounter * 60),
                        "LIKE", null);
                eventCounter++;
            }
        }
    }

    @Test
    void recomputeNowWritesNeighbourSlabsToRedis() throws Exception {
        job.recomputeNow();

        // Item 0 should have at least one neighbour (item 1, given the
        // five-user co-engagement).
        String slab = redis.opsForValue().get("sim:item:" + contentIds[0]);
        assertThat(slab).as("sim slab for item 0 must be present").isNotNull();

        List<Map<String, Object>> neighbours =
                objectMapper.readValue(slab, new TypeReference<>() {});
        assertThat(neighbours).isNotEmpty();
        Map<Long, Double> byContentId = new HashMap<>();
        for (Map<String, Object> n : neighbours) {
            byContentId.put(
                    ((Number) n.get("contentId")).longValue(),
                    ((Number) n.get("similarity")).doubleValue());
        }
        assertThat(byContentId).containsKey(contentIds[1]);
        assertThat(byContentId.get(contentIds[1])).isGreaterThan(0.5);
    }

    @Test
    void slabHasTwentyFourHourTtl() {
        job.recomputeNow();

        Long ttlSeconds = redis.getExpire("sim:item:" + contentIds[0]);
        assertThat(ttlSeconds).isNotNull();
        // TTL should be just under 24h (Redis returns seconds-remaining).
        assertThat(ttlSeconds).isBetween(1L, Duration.ofHours(24).toSeconds() + 5L);
    }

    private static java.time.Instant secondsAgoToInstant(int secondsAgo) {
        return java.time.Instant.parse("2026-04-25T10:00:00Z").minusSeconds(secondsAgo);
    }

    private static void insertInteraction(
            Connection conn,
            UUID userId,
            long contentId,
            java.time.Instant occurredAt,
            String eventType,
            Integer dwellSec)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.interactions"
                        + " (user_id, content_id, occurred_at, event_type, dwell_sec)"
                        + " VALUES (?, ?, ?, ?, ?)"
                        + " ON CONFLICT DO NOTHING")) {
            ps.setObject(1, userId);
            ps.setLong(2, contentId);
            ps.setTimestamp(3, java.sql.Timestamp.from(occurredAt));
            ps.setString(4, eventType);
            if (dwellSec == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, dwellSec);
            }
            ps.executeUpdate();
        }
    }

    /**
     * Narrow component-scan filter excluding the JPA-backed adapters
     * in the same package — without it, the IT context tries to wire
     * SpringDataRecommendationRepository / RedisTopNCacheStore /
     * ContentJpaEntity etc., which require the JPA auto-configs we
     * deliberately disable here.
     */
    @SpringBootApplication(
            exclude = {
                HibernateJpaAutoConfiguration.class,
                JpaRepositoriesAutoConfiguration.class
            })
    @org.springframework.context.annotation.ComponentScan(
            basePackages = "com.plrs.infrastructure.recommendation",
            excludeFilters =
                    @org.springframework.context.annotation.ComponentScan.Filter(
                            type =
                                    org.springframework.context.annotation.FilterType
                                            .REGEX,
                            pattern = "com\\.plrs\\.infrastructure\\.recommendation\\.SpringData.*|"
                                    + "com\\.plrs\\.infrastructure\\.recommendation\\.Redis.*"))
    static class SimJobITApp {

        @org.springframework.context.annotation.Bean
        java.time.Clock testClock() {
            return java.time.Clock.fixed(
                    java.time.Instant.parse("2026-04-25T10:00:00Z"),
                    java.time.ZoneOffset.UTC);
        }

        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        /**
         * Stub artifact repository — the dual-write adapter is exercised
         * end-to-end in {@code SpringDataArtifactRepositoryIT}; this IT
         * focuses on the Redis side and just needs the bean to exist so
         * ItemSimilarityJob can wire.
         */
        @org.springframework.context.annotation.Bean
        com.plrs.application.recommendation.ArtifactRepository stubArtifactRepository() {
            return new com.plrs.application.recommendation.ArtifactRepository() {
                @Override
                public void upsert(
                        com.plrs.application.recommendation.ArtifactPayload payload) {
                    // no-op
                }

                @Override
                public java.util.Optional<
                                com.plrs.application.recommendation.ArtifactPayload>
                        find(String artifactType, String artifactKey) {
                    return java.util.Optional.empty();
                }
            };
        }
    }
}
