package com.plrs.infrastructure.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.plrs.application.eval.EvalRun;
import com.plrs.application.eval.EvalRunRepository;
import com.plrs.infrastructure.testsupport.PostgresTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for {@link SpringDataEvalRunRepository}. Drives
 * the {@link EvalRunRepository} port through the JPA adapter and
 * confirms save returns a populated id and findLatest reads the most
 * recent {@code ran_at}.
 */
@SpringBootTest(
        classes = SpringDataEvalRunRepositoryIT.EvalRepoITApp.class,
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
class SpringDataEvalRunRepositoryIT extends PostgresTestBase {

    @Autowired private EvalRunRepository repository;
    @PersistenceContext private EntityManager em;

    @Test
    void saveAssignsSurrogateKeyAndRoundTripsAllColumns() {
        Instant ranAt = Instant.parse("2026-04-25T10:00:00Z");
        EvalRun fresh =
                new EvalRun(
                        Optional.empty(),
                        ranAt,
                        "hybrid_v1",
                        (short) 10,
                        Optional.of(new BigDecimal("0.4500")),
                        Optional.of(new BigDecimal("0.5500")),
                        Optional.of(new BigDecimal("0.3000")),
                        Optional.of(new BigDecimal("0.6500")),
                        Optional.of(new BigDecimal("2.5000")),
                        Optional.of(12));

        EvalRun saved = repository.save(fresh);
        em.flush();

        assertThat(saved.evalRunSk()).isPresent();
        assertThat(saved.ranAt()).isEqualTo(ranAt);
        assertThat(saved.variantName()).isEqualTo("hybrid_v1");
        assertThat(saved.k()).isEqualTo((short) 10);
        assertThat(saved.precisionAtK()).hasValue(new BigDecimal("0.4500"));
        assertThat(saved.diversity()).hasValue(new BigDecimal("0.6500"));
        assertThat(saved.novelty()).hasValue(new BigDecimal("2.5000"));
        assertThat(saved.nUsers()).contains(12);
    }

    @Test
    void findLatestReturnsMostRecentRanAt() {
        Instant earlier = Instant.parse("2026-04-24T10:00:00Z");
        Instant later = Instant.parse("2026-04-25T11:00:00Z");
        repository.save(
                new EvalRun(
                        Optional.empty(),
                        earlier,
                        "hybrid_v1",
                        (short) 10,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        repository.save(
                new EvalRun(
                        Optional.empty(),
                        later,
                        "hybrid_v1",
                        (short) 10,
                        Optional.of(new BigDecimal("0.7000")),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        em.flush();

        EvalRun latest = repository.findLatest().orElseThrow();
        assertThat(latest.ranAt()).isEqualTo(later);
        assertThat(latest.precisionAtK()).hasValue(new BigDecimal("0.7000"));
    }

    @Test
    void findLatestEmptyOnEmptyTable() {
        // The @Transactional rollback isolates this method's data; in a
        // fresh schema the table is empty.
        assertThat(repository.findLatest()).isEmpty();
    }

    @SpringBootApplication(
            scanBasePackages = "com.plrs.infrastructure.eval",
            exclude = {
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    @org.springframework.boot.autoconfigure.domain.EntityScan(
            basePackages = "com.plrs.infrastructure.eval")
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(
            basePackages = "com.plrs.infrastructure.eval")
    static class EvalRepoITApp {}
}
