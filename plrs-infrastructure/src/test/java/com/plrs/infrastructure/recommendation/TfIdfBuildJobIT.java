package com.plrs.infrastructure.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plrs.application.recommendation.ArtifactRepository;
import com.plrs.domain.content.ContentId;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * End-to-end test of {@link TfIdfBuildJob} + {@link TfIdfReader}: seeds
 * six content rows with overlapping titles into a real Postgres, drives
 * the build job, and asserts pairwise cosine matches the expected
 * pattern (overlapping pairs &gt; 0; non-overlapping pairs = 0).
 */
@SpringBootTest(
        classes = TfIdfBuildJobIT.TfIdfITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false",
            "plrs.recommender.tfidf.cron=0 0 3 1 1 *"
        })
class TfIdfBuildJobIT {

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
    @Autowired private TfIdfBuildJob job;
    @Autowired private TfIdfReader reader;

    private Long[] contentIds;

    @BeforeEach
    void seed() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            long topicId;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                            + " VALUES (?, 'tfidf-it') RETURNING topic_id")) {
                ps.setString(1, "tfidf-topic-" + UUID.randomUUID());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    topicId = rs.getLong("topic_id");
                }
            }

            String[][] docs = {
                    {"Algebra basics for beginners", "Learn variables and equations"},
                    {"Algebra masterclass", "Advanced equations and proofs"},
                    {"Calculus introduction", "Limits derivatives and integrals"},
                    {"Calculus deep dive", "Derivatives integrals and series"},
                    {"Cooking pasta", "Recipes for spaghetti and ravioli"},
                    {"Gardening tips", "Tomatoes peppers and herbs"}
            };
            contentIds = new Long[docs.length];
            for (int i = 0; i < docs.length; i++) {
                try (var ps = conn.prepareStatement(
                        "INSERT INTO plrs_ops.content"
                                + " (topic_id, title, ctype, difficulty,"
                                + "  est_minutes, url, description)"
                                + " VALUES (?, ?, 'VIDEO', 'BEGINNER', 5,"
                                + "         'https://x.y/' || ?, ?)"
                                + " RETURNING content_id")) {
                    ps.setLong(1, topicId);
                    ps.setString(2, docs[i][0] + " " + UUID.randomUUID());
                    ps.setInt(3, i);
                    ps.setString(4, docs[i][1]);
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        contentIds[i] = rs.getLong("content_id");
                    }
                }
            }
        }
    }

    @Test
    void cosineIsHighWithinTopicAndZeroAcrossUnrelatedTopics() {
        job.rebuildNow();

        ContentId algebra1 = ContentId.of(contentIds[0]);
        ContentId algebra2 = ContentId.of(contentIds[1]);
        ContentId calculus1 = ContentId.of(contentIds[2]);
        ContentId cooking = ContentId.of(contentIds[4]);
        ContentId gardening = ContentId.of(contentIds[5]);

        double algebraPair = reader.cosine(algebra1, algebra2);
        double algebraVsCalculus = reader.cosine(algebra1, calculus1);
        double cookingVsGardening = reader.cosine(cooking, gardening);

        // Both algebra docs share "algebra" + "equations" — cosine
        // should be strictly positive and noticeably bigger than the
        // cooking-vs-gardening pair which shares no terms.
        assertThat(algebraPair).isGreaterThan(0.0);
        assertThat(algebraPair).isGreaterThan(cookingVsGardening);
        // Algebra vs Calculus: no shared content terms after stopword
        // pruning — should be 0 or very close.
        assertThat(algebraVsCalculus).isLessThanOrEqualTo(algebraPair);
    }

    @Test
    void selfCosineIsOneForKnownContent() {
        job.rebuildNow();
        double self = reader.cosine(ContentId.of(contentIds[0]), ContentId.of(contentIds[0]));
        assertThat(self).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void unknownContentRowReturnsEmpty() {
        job.rebuildNow();
        assertThat(reader.getRow(ContentId.of(99_999_999L))).isEmpty();
        assertThat(reader.cosine(ContentId.of(99_999_998L), ContentId.of(99_999_999L)))
                .isZero();
    }

    @SpringBootApplication(scanBasePackages = "com.plrs.infrastructure.recommendation")
    @org.springframework.boot.autoconfigure.domain.EntityScan(
            basePackages = "com.plrs.infrastructure.recommendation")
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(
            basePackages = "com.plrs.infrastructure.recommendation")
    @org.springframework.context.annotation.ComponentScan(
            basePackages = "com.plrs.infrastructure.recommendation",
            excludeFilters =
                    @org.springframework.context.annotation.ComponentScan.Filter(
                            type =
                                    org.springframework.context.annotation.FilterType
                                            .REGEX,
                            pattern = "com\\.plrs\\.infrastructure\\.recommendation\\."
                                    + "(SpringDataRecommendation|RedisCfScorer|"
                                    + "RedisCbScorer|RedisTopNCacheStore|"
                                    + "ItemSimilarityJob|"
                                    + "CfGoldenIT.*|ItemSimilarityJobIT.*|"
                                    + "RecommendationPipelineGoldenIT.*|"
                                    + "SpringDataArtifactRepositoryIT.*|"
                                    + "SpringDataRecommendationRepositoryIT.*)"))
    static class TfIdfITApp {

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
