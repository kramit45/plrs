package com.plrs.application.outbox;

import com.plrs.domain.common.DomainValidationException;
import java.time.Instant;
import java.util.Optional;

/**
 * Application-layer value object for one row of {@code plrs_ops.outbox_event}
 * (§3.c.1.5). Mirrors the per-row contract of the transactional outbox
 * pattern (§2.e.3.6) — pending events are written in the same DB
 * transaction as the business state-change, then drained asynchronously
 * to Kafka by the scheduled job (step 76).
 *
 * <p>Lives in {@code plrs-application} (not {@code plrs-domain}) because
 * the outbox is a persistence-coupling concern — it exists because we
 * have a database. The domain doesn't know about it.
 *
 * <p>{@code outboxId} is {@link Optional#empty()} for a freshly-built
 * pending event and present after the repository assigns it.
 *
 * <p>Field rules mirror the V9 CHECK constraints:
 *
 * <ul>
 *   <li>{@code aggregateType}: trimmed non-blank, length ≤ 40
 *   <li>{@code aggregateId}: trimmed non-blank, length ≤ 60
 *   <li>{@code payloadJson}: non-null and non-blank (the publisher does
 *       not validate JSON structure — that's the producer's job)
 *   <li>{@code attempts}: in {@code [0, 20]}
 *   <li>{@code lastError}: when present, length ≤ 500
 * </ul>
 *
 * <p>Traces to: §2.e.3.6 (transactional outbox), §3.c.1.5 (DDL), FR-18.
 */
public record OutboxEvent(
        Optional<Long> outboxId,
        String aggregateType,
        String aggregateId,
        String payloadJson,
        Instant createdAt,
        Optional<Instant> deliveredAt,
        short attempts,
        Optional<String> lastError) {

    public static final int MAX_AGGREGATE_TYPE_LENGTH = 40;
    public static final int MAX_AGGREGATE_ID_LENGTH = 60;
    public static final int MAX_LAST_ERROR_LENGTH = 500;
    public static final short MAX_ATTEMPTS = 20;

    public OutboxEvent {
        if (outboxId == null) {
            throw new DomainValidationException(
                    "OutboxEvent outboxId must not be null (use Optional.empty() before persist)");
        }
        if (aggregateType == null || aggregateType.trim().isEmpty()) {
            throw new DomainValidationException("OutboxEvent aggregateType must not be blank");
        }
        if (aggregateType.length() > MAX_AGGREGATE_TYPE_LENGTH) {
            throw new DomainValidationException(
                    "OutboxEvent aggregateType must be at most "
                            + MAX_AGGREGATE_TYPE_LENGTH
                            + " characters, got "
                            + aggregateType.length());
        }
        if (aggregateId == null || aggregateId.trim().isEmpty()) {
            throw new DomainValidationException("OutboxEvent aggregateId must not be blank");
        }
        if (aggregateId.length() > MAX_AGGREGATE_ID_LENGTH) {
            throw new DomainValidationException(
                    "OutboxEvent aggregateId must be at most "
                            + MAX_AGGREGATE_ID_LENGTH
                            + " characters, got "
                            + aggregateId.length());
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new DomainValidationException("OutboxEvent payloadJson must not be blank");
        }
        if (createdAt == null) {
            throw new DomainValidationException("OutboxEvent createdAt must not be null");
        }
        if (deliveredAt == null) {
            throw new DomainValidationException(
                    "OutboxEvent deliveredAt must not be null (use Optional.empty())");
        }
        if (attempts < 0 || attempts > MAX_ATTEMPTS) {
            throw new DomainValidationException(
                    "OutboxEvent attempts must be in [0, "
                            + MAX_ATTEMPTS
                            + "], got "
                            + attempts);
        }
        if (lastError == null) {
            throw new DomainValidationException(
                    "OutboxEvent lastError must not be null (use Optional.empty())");
        }
        if (lastError.isPresent() && lastError.get().length() > MAX_LAST_ERROR_LENGTH) {
            throw new DomainValidationException(
                    "OutboxEvent lastError must be at most "
                            + MAX_LAST_ERROR_LENGTH
                            + " characters, got "
                            + lastError.get().length());
        }
    }

    /**
     * Builds a fresh, undelivered, never-attempted event suitable for
     * passing to {@link OutboxRepository#save}.
     */
    public static OutboxEvent pending(
            String aggregateType, String aggregateId, String payloadJson, Instant now) {
        return new OutboxEvent(
                Optional.empty(),
                aggregateType,
                aggregateId,
                payloadJson,
                now,
                Optional.empty(),
                (short) 0,
                Optional.empty());
    }
}
