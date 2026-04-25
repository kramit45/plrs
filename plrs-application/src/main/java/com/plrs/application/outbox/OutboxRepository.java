package com.plrs.application.outbox;

import java.time.Instant;
import java.util.List;

/**
 * Application-layer port for the transactional outbox. The adapter
 * (step 75, infrastructure module) writes to {@code plrs_ops.outbox_event}.
 *
 * <p>Four methods, deliberately narrow:
 *
 * <ul>
 *   <li>{@link #save} — write a pending event (typically inside the
 *       same transaction as the business state-change).
 *   <li>{@link #findUndelivered} — drain-job lookup, FIFO by
 *       {@code created_at}.
 *   <li>{@link #markDelivered} — drain-job success path.
 *   <li>{@link #recordFailure} — drain-job failure path; bumps
 *       {@code attempts} and stores {@code lastError}. Caller routes to
 *       a DLQ if attempts reach the cap.
 * </ul>
 *
 * <p>No delete / cleanup method — retention is a separate operational
 * concern (Iter 4 will add a scheduled prune of delivered rows older
 * than N days).
 *
 * <p>Traces to: §2.e.3.6 (transactional outbox), FR-18 (event publishing).
 */
public interface OutboxRepository {

    /** Persists a pending event. Returns the event with {@code outboxId} populated. */
    OutboxEvent save(OutboxEvent event);

    /** Returns the next {@code limit} undelivered events in FIFO order by {@code created_at}. */
    List<OutboxEvent> findUndelivered(int limit);

    /** Marks the event as delivered with the supplied timestamp. */
    void markDelivered(Long outboxId, Instant deliveredAt);

    /**
     * Records a failed delivery attempt; increments {@code attempts} and
     * stores {@code error}. Throws {@link IllegalStateException} if the
     * resulting attempt count would exceed the bound — caller should
     * route to the DLQ at that point.
     */
    void recordFailure(Long outboxId, String error);
}
