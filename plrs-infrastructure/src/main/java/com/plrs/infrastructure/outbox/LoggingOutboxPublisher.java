package com.plrs.infrastructure.outbox;

import com.plrs.application.outbox.OutboxEvent;
import com.plrs.application.outbox.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default no-op {@link OutboxPublisher}. Logs each event at INFO and
 * returns successfully — the drain job's contract just needs
 * <i>something</i> to call before {@code markDelivered}.
 *
 * <p>Active when {@code plrs.kafka.enabled} is unset or {@code false}.
 * The {@link KafkaOutboxPublisher} takes over when the property is
 * {@code true}; the two gates are complementary so exactly one bean
 * implements {@link OutboxPublisher} at runtime.
 *
 * <p>Traces to: §2.e.3.6 (transactional outbox), FR-18 (event
 * publishing).
 */
@Component
@ConditionalOnProperty(
        name = "plrs.kafka.enabled",
        havingValue = "false",
        matchIfMissing = true)
public final class LoggingOutboxPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info(
                "OUTBOX_PUBLISH aggregate_type={} aggregate_id={} outbox_id={} payload_size={}",
                event.aggregateType(),
                event.aggregateId(),
                event.outboxId().orElse(-1L),
                event.payloadJson().length());
    }
}
