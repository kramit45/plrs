package com.plrs.infrastructure.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.application.recommendation.ArtifactPayload;
import com.plrs.application.recommendation.ArtifactRepository;
import com.plrs.infrastructure.testsupport.PostgresTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for {@link SpringDataArtifactRepository}. Drives
 * the {@link ArtifactRepository} port to confirm Spring wires the
 * adapter and exercises every method (upsert insert, upsert replace,
 * find round-trip, find miss, size_bytes CHECK).
 *
 * <p>Traces to: §3.c.1.5.
 */
@SpringBootTest(
        classes = SpringDataArtifactRepositoryIT.ArtifactRepoITApp.class,
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
class SpringDataArtifactRepositoryIT extends PostgresTestBase {

    @Autowired private ArtifactRepository repository;
    @PersistenceContext private EntityManager em;

    @Test
    void upsertThenFindRoundTripsEveryColumn() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        ArtifactPayload payload =
                ArtifactPayload.ofString(
                        "SIM_SLAB", "1001", "[{\"contentId\":42,\"similarity\":0.9}]",
                        "v1", t0);

        repository.upsert(payload);
        em.flush();

        ArtifactPayload loaded = repository.find("SIM_SLAB", "1001").orElseThrow();
        assertThat(loaded.artifactType()).isEqualTo("SIM_SLAB");
        assertThat(loaded.artifactKey()).isEqualTo("1001");
        assertThat(loaded.asString())
                .isEqualTo("[{\"contentId\":42,\"similarity\":0.9}]");
        assertThat(loaded.version()).isEqualTo("v1");
        assertThat(loaded.computedAt()).isEqualTo(t0);
    }

    @Test
    void upsertReplacesExistingRow() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        repository.upsert(
                ArtifactPayload.ofString("SIM_SLAB", "2002", "first", "v1", t0));
        em.flush();

        repository.upsert(
                ArtifactPayload.ofString(
                        "SIM_SLAB", "2002", "second-revised", "v2", t0.plusSeconds(60)));
        em.flush();
        em.clear();

        ArtifactPayload loaded = repository.find("SIM_SLAB", "2002").orElseThrow();
        assertThat(loaded.asString()).isEqualTo("second-revised");
        assertThat(loaded.version()).isEqualTo("v2");
    }

    @Test
    void findOnMissingRowReturnsEmpty() {
        assertThat(repository.find("SIM_SLAB", "no-such-key")).isEmpty();
    }

    @Test
    void compositeKeyDistinguishesTypeFromKey() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        repository.upsert(
                ArtifactPayload.ofString("SIM_SLAB", "shared", "sim-payload", "v1", t0));
        repository.upsert(
                ArtifactPayload.ofString("TFIDF", "shared", "tfidf-payload", "v1", t0));
        em.flush();

        assertThat(repository.find("SIM_SLAB", "shared").orElseThrow().asString())
                .isEqualTo("sim-payload");
        assertThat(repository.find("TFIDF", "shared").orElseThrow().asString())
                .isEqualTo("tfidf-payload");
    }

    @Test
    void unknownArtifactTypeRejectedByCheckConstraint() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        ArtifactPayload bad =
                ArtifactPayload.ofString("UNKNOWN_TYPE", "x", "{}", "v1", t0);

        assertThatThrownBy(
                        () -> {
                            repository.upsert(bad);
                            em.flush();
                        })
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Standard JPA-enabled IT context. Redis auto-config is excluded
     * because this IT only exercises the Postgres path; ItemSimilarityJob
     * and the Redis* beans stay dormant courtesy of their
     * {@code @ConditionalOnProperty(name = "spring.data.redis.host")}
     * gate (the IT never sets that property).
     */
    @SpringBootApplication(
            scanBasePackages = "com.plrs.infrastructure.recommendation",
            exclude = {
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    @org.springframework.boot.autoconfigure.domain.EntityScan(
            basePackages = "com.plrs.infrastructure.recommendation")
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(
            basePackages = "com.plrs.infrastructure.recommendation")
    static class ArtifactRepoITApp {}
}
