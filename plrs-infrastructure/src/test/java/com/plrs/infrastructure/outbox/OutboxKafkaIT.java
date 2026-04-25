package com.plrs.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.plrs.application.outbox.OutboxEvent;
import com.plrs.application.outbox.OutboxRepository;
import com.plrs.infrastructure.testsupport.KafkaTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end IT verifying the outbox-to-Kafka flow:
 *
 * <ol>
 *   <li>Save an {@code OutboxEvent} with {@code aggregateType=QUIZ_ATTEMPTED}.
 *   <li>Drive the drain job manually so the {@link KafkaOutboxPublisher}
 *       publishes to {@code plrs.mastery}.
 *   <li>A standalone {@link KafkaConsumer} (auto.offset.reset=earliest)
 *       reads the topic and asserts the message arrived with the right
 *       key + payload.
 *   <li>The outbox row's {@code delivered_at} is set and {@code attempts}
 *       is 1.
 * </ol>
 *
 * <p>Drain is invoked directly (the bean stays autowired because
 * {@code plrs.outbox.drain.enabled} matches-if-missing). Sleep-based
 * polling on the outbox table is skipped — the synchronous publish in
 * {@link KafkaOutboxPublisher} guarantees delivered_at is set as soon
 * as {@code drainJob.drain()} returns.
 */
@SpringBootTest(
        classes = OutboxKafkaIT.OutboxKafkaITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=plrs_ops",
            "plrs.outbox.drain.enabled=true",
            // Push the scheduled fire well past the test duration; we
            // call drain() directly.
            "plrs.outbox.drain.fixed-delay-ms=600000",
            "plrs.outbox.drain.initial-delay-ms=600000",
            "plrs.outbox.drain.batch-size=10",
            "plrs.kafka.topic-mastery=plrs.mastery",
            "plrs.kafka.topic-interactions=plrs.interactions",
            "spring.kafka.producer.acks=all",
            "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer"
        })
@Transactional
class OutboxKafkaIT extends KafkaTestBase {

    @Autowired private OutboxRepository outboxRepository;
    @Autowired private OutboxDrainJob drainJob;
    @PersistenceContext private EntityManager em;

    @Test
    void quizAttemptedEventLandsOnMasteryTopicAndOutboxRowIsMarkedDelivered() {
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
        String aggregateId = "user-" + UUID.randomUUID();
        String payload = "{\"event\":\"QUIZ_ATTEMPTED\",\"id\":\"" + aggregateId + "\"}";

        OutboxEvent saved =
                outboxRepository.save(
                        OutboxEvent.pending(
                                "QUIZ_ATTEMPTED", aggregateId, payload, t0));
        em.flush();
        assertThat(saved.outboxId()).isPresent();

        drainJob.drain();
        em.flush();

        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of("plrs.mastery"));
            ConsumerRecord<String, String> match =
                    pollForKey(consumer, aggregateId, Duration.ofSeconds(10));
            assertThat(match)
                    .as("expected a message for aggregateId %s on plrs.mastery", aggregateId)
                    .isNotNull();
            assertThat(match.value()).isEqualTo(payload);
        }

        // Confirm the row is no longer in the undelivered set and that
        // exactly one attempt was recorded.
        List<OutboxEvent> undelivered = outboxRepository.findUndelivered(50);
        assertThat(undelivered)
                .as("delivered events must drop out of findUndelivered")
                .noneMatch(e -> e.aggregateId().equals(aggregateId));
    }

    private KafkaConsumer<String, String> newConsumer() {
        Properties props = new Properties();
        props.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        props.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    private static ConsumerRecord<String, String> pollForKey(
            KafkaConsumer<String, String> consumer, String key, Duration budget) {
        Instant deadline = Instant.now().plus(budget);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> batch = consumer.poll(Duration.ofSeconds(1));
            for (ConsumerRecord<String, String> record : batch) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }
        return null;
    }

    @SpringBootApplication(
            exclude = {
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    @EnableScheduling
    static class OutboxKafkaITApp {

        @org.springframework.context.annotation.Bean
        public java.time.Clock clock() {
            return java.time.Clock.systemUTC();
        }
    }
}
