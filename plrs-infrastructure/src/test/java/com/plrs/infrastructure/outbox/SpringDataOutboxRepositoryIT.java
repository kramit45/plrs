package com.plrs.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.application.outbox.OutboxEvent;
import com.plrs.application.outbox.OutboxRepository;
import com.plrs.infrastructure.testsupport.PostgresTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for {@link SpringDataOutboxRepository}. Drives the
 * application-layer port to confirm Spring wires the adapter, exercises
 * save/load round-trip with JSONB, FIFO undelivered ordering,
 * markDelivered, and the recordFailure attempt cap.
 *
 * <p>Traces to: §3.c.1.5 (outbox_event), §2.e.3.6, FR-18.
 */
@SpringBootTest(
        classes = SpringDataOutboxRepositoryIT.OutboxRepoITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=plrs_ops",
            // Disable the drain job in this IT — it would pull in a Clock
            // bean we don't supply in the nested ITApp.
            "plrs.outbox.drain.enabled=false"
        })
@Transactional
class SpringDataOutboxRepositoryIT extends PostgresTestBase {

    @Autowired private OutboxRepository outboxRepository;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager em;

    @Test
    void saveReturnsEventWithOutboxId() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");

        OutboxEvent saved =
                outboxRepository.save(
                        OutboxEvent.pending("QUIZ", "agg-1", "{\"v\":1}", t0));

        assertThat(saved.outboxId()).isPresent();
        assertThat(saved.outboxId().get()).isPositive();
        assertThat(saved.aggregateType()).isEqualTo("QUIZ");
        assertThat(saved.payloadJson()).isEqualTo("{\"v\":1}");
    }

    @Test
    void findUndeliveredReturnsOnlyUndeliveredInFifoOrder() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        OutboxEvent first =
                outboxRepository.save(OutboxEvent.pending("QUIZ", "a-1", "{\"v\":1}", t0));
        OutboxEvent second =
                outboxRepository.save(
                        OutboxEvent.pending("QUIZ", "a-2", "{\"v\":2}", t0.plusSeconds(1)));
        OutboxEvent third =
                outboxRepository.save(
                        OutboxEvent.pending("QUIZ", "a-3", "{\"v\":3}", t0.plusSeconds(2)));
        em.flush();

        outboxRepository.markDelivered(second.outboxId().orElseThrow(), Instant.now());
        em.flush();

        List<OutboxEvent> undelivered = outboxRepository.findUndelivered(10);

        assertThat(undelivered)
                .extracting(e -> e.outboxId().orElseThrow())
                .containsExactly(first.outboxId().orElseThrow(), third.outboxId().orElseThrow());
    }

    @Test
    void markDeliveredSetsTimestampAndRemovesFromUndelivered() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        OutboxEvent saved =
                outboxRepository.save(OutboxEvent.pending("QUIZ", "a-1", "{}", t0));
        em.flush();

        Instant deliveredAt = Instant.parse("2026-04-25T10:00:05Z");
        outboxRepository.markDelivered(saved.outboxId().orElseThrow(), deliveredAt);
        em.flush();

        assertThat(outboxRepository.findUndelivered(10)).isEmpty();
    }

    @Test
    void recordFailureIncrementsAttemptsAndStoresError() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        OutboxEvent saved =
                outboxRepository.save(OutboxEvent.pending("QUIZ", "a-1", "{}", t0));
        em.flush();

        outboxRepository.recordFailure(saved.outboxId().orElseThrow(), "broker offline");
        em.flush();

        OutboxEvent reloaded =
                outboxRepository.findUndelivered(10).stream()
                        .filter(e -> e.outboxId().equals(saved.outboxId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(reloaded.attempts()).isEqualTo((short) 1);
        assertThat(reloaded.lastError()).contains("broker offline");
    }

    @Test
    void recordFailureOnAttempt20SucceedsAttempt21Throws() throws SQLException {
        // Seed a row with attempts=20 directly via SQL to bypass the
        // record-by-record increment loop (faster + isolates the boundary).
        long outboxId;
        try (Connection conn = dataSource.getConnection();
                var ps = conn.prepareStatement(
                        "INSERT INTO plrs_ops.outbox_event"
                                + " (aggregate_type, aggregate_id, payload_json, attempts)"
                                + " VALUES ('QUIZ', 'cap', CAST('{}' AS JSONB), 20)"
                                + " RETURNING outbox_id");
                var rs = ps.executeQuery()) {
            rs.next();
            outboxId = rs.getLong("outbox_id");
        }

        // attempts is already 20 → the bound is reached → next call must throw.
        // @Repository advice wraps IllegalStateException as
        // InvalidDataAccessApiUsageException; message survives.
        long finalId = outboxId;
        assertThatThrownBy(() -> outboxRepository.recordFailure(finalId, "still down"))
                .hasMessageContaining("attempts cap");
    }

    @Test
    void recordFailureUnderCapSucceeds() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        OutboxEvent saved =
                outboxRepository.save(OutboxEvent.pending("QUIZ", "a-1", "{}", t0));
        em.flush();

        for (int i = 0; i < 20; i++) {
            outboxRepository.recordFailure(saved.outboxId().orElseThrow(), "attempt " + i);
            em.flush();
        }

        // 21st attempt should hit the cap.
        long finalId = saved.outboxId().orElseThrow();
        assertThatThrownBy(() -> outboxRepository.recordFailure(finalId, "21st"))
                .hasMessageContaining("attempts cap");
    }

    @Test
    void payloadJsonRoundTripsViaJsonb() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        String payload = "{\"key\":\"value\",\"nested\":{\"x\":1}}";

        OutboxEvent saved =
                outboxRepository.save(OutboxEvent.pending("QUIZ", "a-rt", payload, t0));
        em.flush();
        em.clear();

        OutboxEvent reloaded =
                outboxRepository.findUndelivered(10).stream()
                        .filter(e -> e.outboxId().equals(saved.outboxId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(reloaded.payloadJson()).contains("\"key\"").contains("\"value\"");
    }

    @org.springframework.boot.autoconfigure.SpringBootApplication(
            exclude = {
                org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class,
                org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration.class
            })
    static class OutboxRepoITApp {}
}
