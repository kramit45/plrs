package com.plrs.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.plrs.application.outbox.OutboxEvent;
import com.plrs.application.outbox.OutboxRepository;
import com.plrs.infrastructure.testsupport.PostgresTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end IT for {@link OutboxDrainJob} against a real Postgres.
 *
 * <p>{@code plrs.outbox.drain.enabled=false} disables the scheduled
 * timer so the test invokes {@link OutboxDrainJob#drain()} directly.
 * The bean still autowires (the {@code @ConditionalOnProperty}
 * defaults to {@code matchIfMissing=true} but only the
 * {@code @Scheduled} timer is suppressed by the disabled flag — the
 * bean exists either way for this test). To guarantee the bean is in
 * scope, the nested ITApp uses {@code @EnableScheduling}.
 *
 * <p>Traces to: §2.e.3.6, FR-18.
 */
@SpringBootTest(
        classes = OutboxDrainJobIT.OutboxDrainITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=plrs_ops",
            "plrs.outbox.drain.enabled=true",
            // Push the scheduled fire time well beyond the test's lifetime
            // so we control invocation manually via drainJob.drain().
            "plrs.outbox.drain.fixed-delay-ms=600000",
            "plrs.outbox.drain.initial-delay-ms=600000",
            "plrs.outbox.drain.batch-size=10"
        })
@Transactional
class OutboxDrainJobIT extends PostgresTestBase {

    @Autowired private OutboxRepository outboxRepository;
    @Autowired private OutboxDrainJob drainJob;
    @PersistenceContext private EntityManager em;

    @Test
    void endToEndDrainsAllUndeliveredEvents() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        String prefix = "drain-" + UUID.randomUUID();
        outboxRepository.save(OutboxEvent.pending("QUIZ", prefix + "-1", "{\"v\":1}", t0));
        outboxRepository.save(
                OutboxEvent.pending("QUIZ", prefix + "-2", "{\"v\":2}", t0.plusSeconds(1)));
        outboxRepository.save(
                OutboxEvent.pending("QUIZ", prefix + "-3", "{\"v\":3}", t0.plusSeconds(2)));
        em.flush();

        drainJob.drain();
        em.flush();

        List<OutboxEvent> stillUndelivered =
                outboxRepository.findUndelivered(50).stream()
                        .filter(e -> e.aggregateId().startsWith(prefix))
                        .toList();
        assertThat(stillUndelivered).isEmpty();
    }

    @Test
    void drainWithNoUndeliveredIsSafeNoop() {
        // Pre-clean any rows leaked from sibling tests within the same JVM.
        // Use a fresh aggregate prefix so we can trust there are no
        // outstanding rows under our scope.
        drainJob.drain();
        em.flush();
        // Second invocation: nothing to do.
        drainJob.drain();
    }

    @SpringBootApplication(
            exclude = {
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    @EnableScheduling
    static class OutboxDrainITApp {

        @org.springframework.context.annotation.Bean
        public java.time.Clock clock() {
            return java.time.Clock.systemUTC();
        }
    }
}
