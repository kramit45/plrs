package com.plrs.infrastructure.outbox;

import com.plrs.application.outbox.OutboxEvent;
import com.plrs.application.outbox.OutboxPublisher;
import com.plrs.application.outbox.OutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled drain of the transactional outbox. Polls
 * {@link OutboxRepository#findUndelivered} every
 * {@code plrs.outbox.drain.fixed-delay-ms} milliseconds (default 5s),
 * publishes each event via {@link OutboxPublisher}, and either marks it
 * delivered or records the failure. At-least-once semantics: a successful
 * publish followed by a failed {@code markDelivered} would re-publish on
 * the next poll; consumers dedupe by {@code outbox_id}.
 *
 * <p>{@code @ConditionalOnProperty(plrs.outbox.drain.enabled, ...,
 * matchIfMissing=true)} so production runs by default and tests can
 * disable the auto-poll via {@code plrs.outbox.drain.enabled=false}
 * before invoking {@link #drain()} explicitly.
 *
 * <p>Traces to: §2.e.3.6 (transactional outbox), FR-18 (event publishing),
 * TX-06.
 */
@Component
@ConditionalOnProperty(
        name = "plrs.outbox.drain.enabled",
        havingValue = "true",
        matchIfMissing = true)
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
        com.plrs.application.outbox.OutboxRepository.class)
public final class OutboxDrainJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxDrainJob.class);

    private final OutboxRepository repo;
    private final OutboxPublisher publisher;
    private final Clock clock;
    private final int batchSize;

    public OutboxDrainJob(
            OutboxRepository repo,
            OutboxPublisher publisher,
            Clock clock,
            @Value("${plrs.outbox.drain.batch-size:25}") int batchSize) {
        this.repo = repo;
        this.publisher = publisher;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${plrs.outbox.drain.fixed-delay-ms:5000}",
            initialDelayString = "${plrs.outbox.drain.initial-delay-ms:5000}")
    public void drain() {
        List<OutboxEvent> batch = repo.findUndelivered(batchSize);
        if (batch.isEmpty()) {
            return;
        }
        int ok = 0;
        int failed = 0;
        for (OutboxEvent e : batch) {
            try {
                publisher.publish(e);
                repo.markDelivered(e.outboxId().orElseThrow(), Instant.now(clock));
                ok++;
            } catch (Exception ex) {
                log.warn(
                        "Outbox publish failed outbox_id={} msg={}",
                        e.outboxId().orElse(-1L),
                        ex.getMessage());
                try {
                    repo.recordFailure(e.outboxId().orElseThrow(), ex.getMessage());
                } catch (Exception recEx) {
                    log.error(
                            "Failed to record outbox failure outbox_id={}",
                            e.outboxId().orElse(-1L),
                            recEx);
                }
                failed++;
            }
        }
        log.info("Outbox drain: published={} failed={}", ok, failed);
    }
}
