package com.plrs.infrastructure.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity mirror of one row in {@code plrs_ops.outbox_event} (V9).
 * {@code payload_json} maps via {@link JdbcTypeCode}({@link SqlTypes#JSON})
 * so the Java {@code String} is round-tripped through Postgres
 * {@code JSONB} without manual converter wiring.
 *
 * <p>Two state mutators ({@link #markDelivered(Instant)} and
 * {@link #recordFailure(String)}) are exposed because the drain job
 * needs to update individual fields without rebuilding the whole
 * entity. Other fields are immutable post-construction.
 *
 * <p>Traces to: §3.c.1.5 (outbox_event schema), §2.e.3.6.
 */
@Entity
@Table(name = "outbox_event", schema = "plrs_ops")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "outbox_id")
    private Long outboxId;

    @Column(name = "aggregate_type", nullable = false, length = 40)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 60)
    private String aggregateId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "JSONB")
    private String payloadJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "attempts", nullable = false)
    private short attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    public OutboxJpaEntity(
            Long outboxId,
            String aggregateType,
            String aggregateId,
            String payloadJson,
            Instant createdAt,
            Instant deliveredAt,
            short attempts,
            String lastError) {
        this.outboxId = outboxId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.payloadJson = payloadJson;
        this.createdAt = createdAt;
        this.deliveredAt = deliveredAt;
        this.attempts = attempts;
        this.lastError = lastError;
    }

    public void markDelivered(Instant when) {
        this.deliveredAt = when;
    }

    public void recordFailure(String error) {
        this.attempts = (short) (this.attempts + 1);
        this.lastError = error;
    }
}
