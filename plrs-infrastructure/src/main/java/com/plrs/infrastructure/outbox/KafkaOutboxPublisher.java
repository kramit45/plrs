package com.plrs.infrastructure.outbox;

import com.plrs.application.outbox.OutboxEvent;
import com.plrs.application.outbox.OutboxPublisher;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Iter 3 Kafka adapter for {@link OutboxPublisher}. Delivers each
 * outbox event to the topic returned by {@link KafkaTopicResolver},
 * keyed on {@code OutboxEvent.aggregateId()} so all events for the
 * same aggregate land on the same partition (ordered consumption
 * downstream).
 *
 * <p>Send is synchronous with a 5-second timeout — the drain job
 * loops one event at a time and any failure must surface to it so
 * the outbox row gets {@code recordFailure} and stays pending.
 *
 * <p>Activated by {@code plrs.kafka.enabled=true}; the
 * {@link LoggingOutboxPublisher} stays in place when the property
 * is unset or {@code false}, and {@code @Primary} makes the Kafka
 * impl preferred whenever both happen to be on the classpath.
 *
 * <p>Traces to: §2.e.3.6 (transactional outbox), §2.c.5.3 EIR-08
 * (Kafka), FR-18, NFR-10 (at-least-once delivery).
 */
@Component
@Primary
@ConditionalOnProperty(name = "plrs.kafka.enabled", havingValue = "true")
public final class KafkaOutboxPublisher implements OutboxPublisher {

    private static final long SEND_TIMEOUT_SECONDS = 5L;
    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final KafkaTopicResolver topics;

    public KafkaOutboxPublisher(
            KafkaTemplate<String, String> kafka, KafkaTopicResolver topics) {
        this.kafka = kafka;
        this.topics = topics;
    }

    @Override
    public void publish(OutboxEvent event) {
        String topic = topics.resolve(event.aggregateType());
        String key = event.aggregateId();
        try {
            kafka.send(topic, key, event.payloadJson())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.debug(
                    "OUTBOX_PUBLISH topic={} key={} outbox_id={} payload_size={}",
                    topic,
                    key,
                    event.outboxId().orElse(-1L),
                    event.payloadJson().length());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Kafka publish interrupted: " + e.getMessage(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new RuntimeException("Kafka publish failed: " + e.getMessage(), e);
        }
    }
}
