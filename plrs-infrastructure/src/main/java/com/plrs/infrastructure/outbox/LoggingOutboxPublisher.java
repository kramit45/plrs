package com.plrs.infrastructure.outbox;

import com.plrs.application.outbox.OutboxEvent;
import com.plrs.application.outbox.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Iter 2 no-op {@link OutboxPublisher}. Logs each event at INFO and
 * returns successfully — there is no Kafka producer in Iter 2; the
 * drain job's contract just needs <i>something</i> to call before
 * {@code markDelivered}.
 *
 * <p>{@code @ConditionalOnMissingBean(name="kafkaOutboxPublisher")} so
 * Iter 3 can drop in a Kafka adapter under that bean name and this
 * logger steps aside automatically.
 *
 * <p>Traces to: §2.e.3.6 (transactional outbox), FR-18 (event
 * publishing). Iter 2 no-op adapter; replaced in Iter 3 by
 * {@code KafkaOutboxPublisher} conditional on
 * {@code kafka.enabled=true}.
 */
@Component
@ConditionalOnMissingBean(name = "kafkaOutboxPublisher")
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
