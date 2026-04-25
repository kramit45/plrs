package com.plrs.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.plrs.application.outbox.OutboxEvent;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class KafkaOutboxPublisherTest {

    @Mock
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafka;

    private KafkaTopicResolver topics() {
        return new KafkaTopicResolver("plrs.interactions", "plrs.mastery");
    }

    @SuppressWarnings("unchecked")
    private KafkaOutboxPublisher publisher() {
        return new KafkaOutboxPublisher(kafka, topics());
    }

    private static OutboxEvent event(String aggregateType, String aggregateId) {
        return OutboxEvent.pending(
                aggregateType,
                aggregateId,
                "{\"k\":\"v\"}",
                Instant.parse("2026-04-25T10:00:00Z"));
    }

    private static CompletableFuture<SendResult<String, String>> okFuture(
            String topic, int partition) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, "k", "v");
        RecordMetadata md =
                new RecordMetadata(new TopicPartition(topic, partition), 0L, 0, 0L, 0, 0);
        return CompletableFuture.completedFuture(new SendResult<>(record, md));
    }

    @Test
    @SuppressWarnings("unchecked")
    void quizAttemptedEventGoesToMasteryTopicWithAggregateIdAsKey() {
        when(kafka.send("plrs.mastery", "user-42", "{\"k\":\"v\"}"))
                .thenReturn(okFuture("plrs.mastery", 0));

        publisher().publish(event("QUIZ_ATTEMPTED", "user-42"));

        ArgumentCaptor<String> topicCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCap = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(kafka)
                .send(topicCap.capture(), keyCap.capture(), payloadCap.capture());
        assertThat(topicCap.getValue()).isEqualTo("plrs.mastery");
        assertThat(keyCap.getValue()).isEqualTo("user-42");
        assertThat(payloadCap.getValue()).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void interactionEventGoesToInteractionsTopic() {
        when(kafka.send("plrs.interactions", "evt-1", "{\"k\":\"v\"}"))
                .thenReturn(okFuture("plrs.interactions", 0));

        publisher().publish(event("INTERACTION", "evt-1"));

        org.mockito.Mockito.verify(kafka).send("plrs.interactions", "evt-1", "{\"k\":\"v\"}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownAggregateRoutesToDefaultTopic() {
        when(kafka.send(KafkaTopicResolver.DEFAULT_TOPIC, "x", "{\"k\":\"v\"}"))
                .thenReturn(okFuture(KafkaTopicResolver.DEFAULT_TOPIC, 0));

        publisher().publish(event("SOMETHING_NEW", "x"));

        org.mockito.Mockito.verify(kafka)
                .send(KafkaTopicResolver.DEFAULT_TOPIC, "x", "{\"k\":\"v\"}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void brokerFailureSurfacesAsRuntimeException() {
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker not reachable"));
        when(kafka.send("plrs.mastery", "x", "{\"k\":\"v\"}")).thenReturn(failed);

        assertThatThrownBy(() -> publisher().publish(event("QUIZ_ATTEMPTED", "x")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Kafka publish failed");
    }

    @Test
    void outboxEventBuilderPopulatesNonOptionalFields() {
        // Sanity guard so the test fixture stays in lockstep with the
        // OutboxEvent record's invariants.
        OutboxEvent e = event("QUIZ_ATTEMPTED", "x");
        assertThat(e.aggregateType()).isEqualTo("QUIZ_ATTEMPTED");
        assertThat(e.aggregateId()).isEqualTo("x");
        assertThat(e.deliveredAt()).isEqualTo(Optional.empty());
    }
}
