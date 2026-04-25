package com.plrs.infrastructure.outbox;

import com.plrs.application.outbox.OutboxEvent;
import com.plrs.application.outbox.OutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

/**
 * Adapter implementing the application-layer
 * {@link com.plrs.application.outbox.OutboxRepository} port on top of
 * Spring Data JPA. Maps between the {@link OutboxEvent} record and
 * {@link OutboxJpaEntity}, and delegates to {@link OutboxJpaRepository}.
 *
 * <p>Not declared {@code final}: Spring Boot's observation / metrics
 * {@code AbstractAdvisingBeanPostProcessor} CGLIB-subclasses every
 * {@code @Component} bean. Same constraint as the other Spring Data
 * adapters in this module.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")} so
 * the bean is not created when {@code PlrsApplicationTests} runs its
 * no-DB smoke test.
 *
 * <p>Traces to: §3.c.1.5 (outbox_event), §2.e.3.6 (transactional outbox),
 * FR-18.
 */
@Repository
@ConditionalOnProperty(name = "spring.datasource.url")
public class SpringDataOutboxRepository implements OutboxRepository {

    private final OutboxJpaRepository jpa;

    public SpringDataOutboxRepository(OutboxJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public OutboxEvent save(OutboxEvent event) {
        OutboxJpaEntity entity =
                new OutboxJpaEntity(
                        event.outboxId().orElse(null),
                        event.aggregateType(),
                        event.aggregateId(),
                        event.payloadJson(),
                        event.createdAt(),
                        event.deliveredAt().orElse(null),
                        event.attempts(),
                        event.lastError().orElse(null));
        OutboxJpaEntity saved = jpa.save(entity);
        return toDomain(saved);
    }

    @Override
    public List<OutboxEvent> findUndelivered(int limit) {
        return jpa.findUndeliveredTop(PageRequest.of(0, limit)).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void markDelivered(Long outboxId, Instant deliveredAt) {
        OutboxJpaEntity entity =
                jpa.findById(outboxId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "outbox event not found: " + outboxId));
        entity.markDelivered(deliveredAt);
        jpa.save(entity);
    }

    @Override
    public void recordFailure(Long outboxId, String error) {
        OutboxJpaEntity entity =
                jpa.findById(outboxId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "outbox event not found: " + outboxId));
        if (entity.getAttempts() >= OutboxEvent.MAX_ATTEMPTS) {
            throw new IllegalStateException(
                    "outbox attempts cap reached for outbox_id=" + outboxId);
        }
        entity.recordFailure(error);
        jpa.save(entity);
    }

    private OutboxEvent toDomain(OutboxJpaEntity entity) {
        return new OutboxEvent(
                Optional.ofNullable(entity.getOutboxId()),
                entity.getAggregateType(),
                entity.getAggregateId(),
                entity.getPayloadJson(),
                entity.getCreatedAt(),
                Optional.ofNullable(entity.getDeliveredAt()),
                entity.getAttempts(),
                Optional.ofNullable(entity.getLastError()));
    }
}
