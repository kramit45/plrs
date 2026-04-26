package com.plrs.web.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * CI-cheap latency smoke test. Boots the full app with Testcontainers
 * Postgres + Redis, registers + logs in a student, then fires 100
 * sequential warm {@code GET /api/recommendations?k=10} requests and
 * asserts the P95 stays under a deliberately loose 500 ms ceiling.
 *
 * <p>NFR-13's published target is 300 ms warm, but the CI runner adds
 * variance (cold JVM, shared CPU, no MV pre-population) so the
 * threshold here trades sensitivity for stability. The full
 * NFR-13/14/17 verification lives in {@code test/jmeter/} and runs
 * out-of-band via {@code .github/workflows/perf.yml}.
 *
 * <p>Traces to: NFR-13 (smoke), NFR-17 (sustained sequential calls).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "plrs.jwt.generate-if-missing=true",
            "plrs.cors.allowed-origins=http://localhost:8080"
        })
class RecommendationsLatencyIT {

    /** Distinct users we rotate through to stay under the per-user rate limit. */
    private static final int N_USERS = 10;

    /** Calls fired per user (well under the 20/min PerUserRateLimiter cap). */
    private static final int CALLS_PER_USER = 10;

    /** Loose CI ceiling — see class Javadoc. */
    private static final long P95_CEILING_MS = 500L;

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("plrs")
                    .withUsername("plrs")
                    .withPassword("plrs");

    private static final GenericContainer<?> REDIS =
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
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate rest;
    @Autowired private DataSource dataSource;

    private final List<String> tokens = new ArrayList<>();

    @BeforeEach
    void registerLoginAndSeedCatalogue() {
        // Tiny content seed so the recommender has something to score
        // and order. Keep small — the test cares about latency tail,
        // not slate content.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        Long topicId =
                jdbc.queryForObject(
                        "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                                + " VALUES (?, 'perf-it') RETURNING topic_id",
                        Long.class,
                        "Perf Topic " + suffix);
        for (int i = 1; i <= 12; i++) {
            jdbc.update(
                    "INSERT INTO plrs_ops.content"
                            + " (topic_id, title, ctype, difficulty, est_minutes, url)"
                            + " VALUES (?, ?, 'ARTICLE', 'BEGINNER', 5, ?)",
                    topicId,
                    "Perf Item " + i + " " + suffix,
                    "https://example.com/perf-it/" + i);
        }

        // Register + log in N_USERS distinct students. Rotating across
        // them keeps each well under the 20-req/min PerUserRateLimiter
        // cap (NFR-31) — the test cares about per-request latency, not
        // limiter behaviour.
        tokens.clear();
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        for (int u = 0; u < N_USERS; u++) {
            String email = "perf-" + UUID.randomUUID() + "@example.com";
            String password = "PerfPass01";
            String body =
                    "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
            rest.postForEntity(
                    "http://localhost:" + port + "/api/auth/register",
                    new HttpEntity<>(body, json),
                    String.class);
            ResponseEntity<String> login =
                    rest.postForEntity(
                            "http://localhost:" + port + "/api/auth/login",
                            new HttpEntity<>(body, json),
                            String.class);
            assertThat(login.getStatusCode().value()).isEqualTo(200);
            String resp = login.getBody();
            int s = resp.indexOf("\"accessToken\":\"") + "\"accessToken\":\"".length();
            int e = resp.indexOf('"', s);
            tokens.add(resp.substring(s, e));
        }
    }

    @Test
    void p95LatencyUnderCeilingForSequentialWarmRequests() {
        // Warm up: one call per user so JIT + cache + connection pool
        // priming don't pollute the sample.
        for (String t : tokens) {
            fire(t);
        }

        int total = N_USERS * CALLS_PER_USER;
        List<Long> latenciesMs = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            String t = tokens.get(i % N_USERS);
            long t0 = System.nanoTime();
            ResponseEntity<String> r = fire(t);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertThat(r.getStatusCode().value())
                    .as("request %d (user %d) should succeed", i, i % N_USERS)
                    .isEqualTo(200);
            latenciesMs.add(ms);
        }

        long[] sorted = latenciesMs.stream().mapToLong(Long::longValue).sorted().toArray();
        long p95 = sorted[(int) Math.ceil(0.95 * sorted.length) - 1];
        long p50 = sorted[sorted.length / 2];
        long max = sorted[sorted.length - 1];

        System.out.println(
                "RecommendationsLatencyIT — sequential warm: p50="
                        + p50
                        + "ms p95="
                        + p95
                        + "ms max="
                        + max
                        + "ms (n="
                        + sorted.length
                        + ")");

        assertThat(p95)
                .as("P95 latency must stay under %d ms (loose CI ceiling)", P95_CEILING_MS)
                .isLessThan(P95_CEILING_MS);
    }

    private ResponseEntity<String> fire(String accessToken) {
        HttpHeaders auth = new HttpHeaders();
        auth.set("Authorization", "Bearer " + accessToken);
        return rest.exchange(
                "http://localhost:" + port + "/api/recommendations?k=10",
                HttpMethod.GET,
                new HttpEntity<>(auth),
                String.class);
    }
}
