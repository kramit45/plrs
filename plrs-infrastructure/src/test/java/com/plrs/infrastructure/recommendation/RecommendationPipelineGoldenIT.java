package com.plrs.infrastructure.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plrs.application.recommendation.RecommendationService;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.recommendation.Recommendation;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * End-to-end golden test for the full recommender pipeline:
 * candidates → filters → CF + CB → hybrid blend → MMR rerank → reasons.
 *
 * <p>Runs the {@link ItemSimilarityJob} and {@link TfIdfBuildJob}
 * against a Postgres + Redis Testcontainers fixture, then drives
 * {@link RecommendationService#generate} for four hand-crafted users
 * and asserts:
 *
 * <ol>
 *   <li>A user whose history is wholly inside topic T1 sees a
 *       T1-dominated slate.
 *   <li>A user with multi-topic history sees ≥ 2 distinct topics in
 *       their top 5 (a smoke check that MMR is wired in).
 *   <li>Every emitted reason matches one of the four
 *       {@link com.plrs.application.recommendation.ExplanationTemplate}
 *       phrasings.
 *   <li>A brand-new user with no interactions gets at least one
 *       "Popular among learners" cold-start reason.
 * </ol>
 *
 * <p>Spec deviation: the spec places this test in plrs-application as
 * {@code RecommendationPipelineGoldenTest.java}; it lives here as
 * {@code RecommendationPipelineGoldenIT.java} because (a) plrs-application
 * has no Testcontainers on its test classpath and (b) the {@code *IT.java}
 * suffix matches the existing Failsafe pattern. Same spec deviation as
 * {@link CfGoldenIT}.
 *
 * <p>Each {@code @BeforeEach} truncates the operational tables and
 * Redis so assertions don't pick up cumulative noise from sibling
 * tests.
 */
@SpringBootTest(
        classes = RecommendationPipelineGoldenIT.PipelineGoldenApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false",
            "plrs.recommender.itemsim.cron=0 0 2 1 1 *",
            "plrs.recommender.tfidf.cron=0 0 3 1 1 *",
            "plrs.jwt.generate-if-missing=true",
            "plrs.test.pipeline-golden=true"
        })
class RecommendationPipelineGoldenIT {

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

    private static final String[] TOPIC_NAMES = {
        "Algebra", "Calculus", "Statistics", "Geometry", "Trigonometry", "Number Theory"
    };

    // 6 topics × {6, 3, 3, 3, 3, 2} items = 20.
    private static final String[][] CONTENT_TITLES = {
        {
            "Algebra basics for beginners",
            "Algebra equations and proofs",
            "Algebra polynomials and factoring",
            "Algebra advanced topics primer",
            "Algebra exponents and logarithms",
            "Algebra word problems collection"
        },
        {
            "Calculus introduction with limits",
            "Calculus derivatives explained",
            "Calculus integrals and series"
        },
        {
            "Statistics for absolute beginners",
            "Statistics distributions and tests",
            "Statistics regression analysis primer"
        },
        {
            "Geometry shapes and circles",
            "Geometry coordinate systems",
            "Geometry triangles and proofs"
        },
        {
            "Trigonometry sine cosine tangent",
            "Trigonometry identities catalogue",
            "Trigonometry waveforms primer"
        },
        {
            "Number Theory primes and divisibility",
            "Number Theory modular arithmetic"
        }
    };

    @Autowired private DataSource dataSource;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ItemSimilarityJob itemSimilarityJob;
    @Autowired private TfIdfBuildJob tfIdfBuildJob;
    @Autowired private RecommendationService recommendationService;
    @Autowired private ContentRepository contentRepository;

    private UUID userA;
    private UUID userB;
    private UUID userC;
    private UUID userD;
    private UUID userNew;
    private long[] topicIds;
    private long[] contentIds;
    private TopicId topic1;

    @BeforeEach
    void seedFixture() throws SQLException {
        clearState();

        try (Connection conn = dataSource.getConnection()) {
            userA = insertUser(conn, "user-a");
            userB = insertUser(conn, "user-b");
            userC = insertUser(conn, "user-c");
            userD = insertUser(conn, "user-d");
            userNew = insertUser(conn, "user-new");

            topicIds = new long[TOPIC_NAMES.length];
            for (int t = 0; t < TOPIC_NAMES.length; t++) {
                topicIds[t] = insertTopic(conn, TOPIC_NAMES[t]);
            }
            topic1 = TopicId.of(topicIds[0]);

            int totalItems = 0;
            for (String[] titles : CONTENT_TITLES) {
                totalItems += titles.length;
            }
            contentIds = new long[totalItems];
            int idx = 0;
            for (int t = 0; t < CONTENT_TITLES.length; t++) {
                for (String title : CONTENT_TITLES[t]) {
                    contentIds[idx++] = insertContent(conn, topicIds[t], title);
                }
            }

            // Interaction layout (offsets are item indexes in the
            // contentIds array — T1 = 0..5, T2 = 6..8, T3 = 9..11,
            // T4 = 12..14, T5 = 15..17, T6 = 18..19):
            //
            //   userD: completes every T1 item (0..5). Topic-loyal
            //     scaffolding so co-engagement keeps T1 items tightly
            //     clustered without bleeding into other topics.
            //   userC: completes every T2 (6..8), T3 (9..11), and the
            //     first T4 item (12). Cross-topic scaffolding for the
            //     T2 ↔ T3 cluster userB taps into.
            //   userA: completes T1[0], T1[1] only — strong T1 focus.
            //     CF surfaces the four remaining T1 items via userD's
            //     overlapping engagement; CB surfaces them via shared
            //     "Algebra" vocabulary. Both push T1 items high.
            //   userB: completes T2[6], T3[9] — two-topic spread. Top
            //     5 should naturally span ≥2 topics; MMR keeps it that
            //     way under near-duplicates.
            //   userNew: no interactions → cold-start path.
            //
            // Spec deviation: 5 users instead of 4 — userD is purely
            // CF scaffolding so T1's co-engagement signal is
            // discriminable from cross-topic noise. The four "test"
            // users (A, B, C, NEW) match the spec; D is offline-only.
            Instant base = Instant.parse("2026-04-25T10:00:00Z");
            int t = 0;
            // userD: T1 loyalty — 0..5.
            for (int ci = 0; ci <= 5; ci++) {
                completeAt(conn, userD, contentIds[ci], base.minusSeconds(t++ * 60L));
            }
            // userC: T2..T4[0] — 6..12.
            for (int ci = 6; ci <= 12; ci++) {
                completeAt(conn, userC, contentIds[ci], base.minusSeconds(t++ * 60L));
            }
            completeAt(conn, userA, contentIds[0], base.minusSeconds(t++ * 60L));
            completeAt(conn, userA, contentIds[1], base.minusSeconds(t++ * 60L));
            completeAt(conn, userB, contentIds[6], base.minusSeconds(t++ * 60L));
            completeAt(conn, userB, contentIds[9], base.minusSeconds(t++ * 60L));
        }

        itemSimilarityJob.recomputeNow();
        tfIdfBuildJob.rebuildNow();
    }

    @Test
    void studentWithStrongTopicFocusSeesTopicDominatedRecs() {
        UserId user = UserId.of(userA);
        List<Recommendation> recs =
                recommendationService.generate(user, 10, "hybrid_v1");

        long t1Count =
                recs.stream()
                        .filter(
                                r ->
                                        contentRepository
                                                .findById(r.contentId())
                                                .orElseThrow()
                                                .topicId()
                                                .equals(topic1))
                        .count();
        assertThat(t1Count)
                .as(
                        "user with T1-only history should see ≥4 T1 items in their top 10")
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    void mmrEnsuresTop5HaveAtLeast2DistinctTopics() {
        UserId user = UserId.of(userB);
        List<Recommendation> recs =
                recommendationService.generate(user, 5, "hybrid_v1");

        Set<TopicId> topics = new HashSet<>();
        for (Recommendation r : recs) {
            topics.add(
                    contentRepository
                            .findById(r.contentId())
                            .orElseThrow()
                            .topicId());
        }
        assertThat(topics)
                .as("MMR-diversified top 5 should span ≥ 2 distinct topics")
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void reasonsMatchSignalDominance() {
        UserId user = UserId.of(userA);
        List<Recommendation> recs =
                recommendationService.generate(user, 5, "hybrid_v1");

        assertThat(recs).isNotEmpty();
        assertThat(recs)
                .allSatisfy(
                        r ->
                                assertThat(r.reason().text())
                                        .matches(
                                                "(Recommended because.*similar.*"
                                                        + "|Matches your interests.*"
                                                        + "|Highly rated.*"
                                                        + "|Popular among learners.*)"));
    }

    @Test
    void coldStartUserGetsPopularityReasons() {
        UserId user = UserId.of(userNew);
        List<Recommendation> recs =
                recommendationService.generate(user, 5, "hybrid_v1");

        assertThat(recs).isNotEmpty();
        assertThat(recs)
                .as("cold-start user gets at least one popularity-phrased reason")
                .anyMatch(r -> r.reason().text().contains("Popular among learners"));
    }

    private void clearState() throws SQLException {
        try (Connection conn = dataSource.getConnection();
                var st = conn.createStatement()) {
            st.execute(
                    "TRUNCATE plrs_ops.recommendations,"
                            + " plrs_ops.interactions,"
                            + " plrs_ops.user_skills,"
                            + " plrs_ops.model_artifacts,"
                            + " plrs_ops.content,"
                            + " plrs_ops.topics,"
                            + " plrs_ops.users CASCADE");
        }
        // Wipe Redis so prior sim slabs / TF-IDF matrices don't bleed in.
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    private static UUID insertUser(Connection conn, String tag) throws SQLException {
        UUID uid = UUID.randomUUID();
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.users"
                        + " (id, email, password_hash,"
                        + "  created_at, updated_at, created_by)"
                        + " VALUES (?, ?, ?, NOW(), NOW(), 'pipeline-golden')")) {
            ps.setObject(1, uid);
            ps.setString(2, tag + "-" + uid + "@example.com");
            ps.setString(3, VALID_BCRYPT);
            ps.executeUpdate();
        }
        return uid;
    }

    private static long insertTopic(Connection conn, String name) throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                        + " VALUES (?, 'pipeline-golden') RETURNING topic_id")) {
            ps.setString(1, name + "-" + UUID.randomUUID());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("topic_id");
            }
        }
    }

    private static long insertContent(Connection conn, long topicId, String title)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.content"
                        + " (topic_id, title, ctype, difficulty,"
                        + "  est_minutes, url)"
                        + " VALUES (?, ?, 'VIDEO', 'BEGINNER', 5, 'https://x.y')"
                        + " RETURNING content_id")) {
            ps.setLong(1, topicId);
            ps.setString(2, title + " " + UUID.randomUUID());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("content_id");
            }
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

    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "plrs.test.pipeline-golden",
            havingValue = "true")
    @SpringBootApplication(
            scanBasePackages = {
                "com.plrs.application",
                "com.plrs.infrastructure"
            })
    @org.springframework.boot.autoconfigure.domain.EntityScan(
            basePackages = "com.plrs.infrastructure")
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(
            basePackages = "com.plrs.infrastructure")
    @org.springframework.context.annotation.ComponentScan(
            basePackages = {"com.plrs.application", "com.plrs.infrastructure"},
            excludeFilters =
                    @org.springframework.context.annotation.ComponentScan.Filter(
                            type =
                                    org.springframework.context.annotation.FilterType
                                            .REGEX,
                            // Same exclusion pattern as CfGoldenIT — drops
                            // any sibling *IT class so this context doesn't
                            // pick up their @Bean methods.
                            pattern = "com\\.plrs\\.infrastructure\\..*IT(\\$.*)?"))
    static class PipelineGoldenApp {

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
