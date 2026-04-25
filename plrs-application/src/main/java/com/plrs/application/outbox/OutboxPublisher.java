package com.plrs.application.outbox;

/**
 * Application-layer port for publishing outbox events to the external
 * messaging system. The Iter 2 adapter is a no-op that logs at INFO
 * ({@code LoggingOutboxPublisher}); the Iter 3 adapter is a Kafka
 * producer.
 *
 * <p>{@link #publish(OutboxEvent)} returns void on success and throws
 * on failure. The drain job (step 76) catches the exception, calls
 * {@link OutboxRepository#recordFailure}, and moves on to the next
 * event in the batch — at-least-once delivery semantics.
 *
 * <p>Traces to: §2.e.3.6 (transactional outbox), FR-18 (event publishing).
 */
public interface OutboxPublisher {

    /**
     * Publishes the event. Throws on transport / serialization / broker
     * failure; the drain job records the failure and proceeds.
     */
    void publish(OutboxEvent event);
}
