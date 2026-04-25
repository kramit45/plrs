package com.plrs.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OutboxEventTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");

    @Test
    void pendingFactorySetsAttemptsZeroAndNoIdOrDelivery() {
        OutboxEvent e = OutboxEvent.pending("QUIZ_ATTEMPT", "agg-1", "{\"x\":1}", T0);

        assertThat(e.outboxId()).isEmpty();
        assertThat(e.deliveredAt()).isEmpty();
        assertThat(e.lastError()).isEmpty();
        assertThat(e.attempts()).isZero();
        assertThat(e.aggregateType()).isEqualTo("QUIZ_ATTEMPT");
        assertThat(e.aggregateId()).isEqualTo("agg-1");
        assertThat(e.payloadJson()).isEqualTo("{\"x\":1}");
        assertThat(e.createdAt()).isEqualTo(T0);
    }

    @Test
    void rejectsBlankAggregateType() {
        assertThatThrownBy(() -> OutboxEvent.pending("   ", "agg-1", "{}", T0))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("aggregateType");
    }

    @Test
    void rejectsBlankAggregateId() {
        assertThatThrownBy(() -> OutboxEvent.pending("QUIZ", "", "{}", T0))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("aggregateId");
    }

    @Test
    void rejectsBlankPayload() {
        assertThatThrownBy(() -> OutboxEvent.pending("QUIZ", "agg-1", "   ", T0))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("payloadJson");
    }

    @Test
    void rejectsAggregateTypeOver40Chars() {
        String tooLong = "x".repeat(41);

        assertThatThrownBy(() -> OutboxEvent.pending(tooLong, "agg-1", "{}", T0))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("40");
    }

    @Test
    void rejectsAggregateIdOver60Chars() {
        String tooLong = "x".repeat(61);

        assertThatThrownBy(() -> OutboxEvent.pending("QUIZ", tooLong, "{}", T0))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("60");
    }

    @Test
    void rejectsAttemptsOutOfBound() {
        assertThatThrownBy(
                        () ->
                                new OutboxEvent(
                                        Optional.of(1L),
                                        "QUIZ",
                                        "agg-1",
                                        "{}",
                                        T0,
                                        Optional.empty(),
                                        (short) 21,
                                        Optional.empty()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("[0, 20]");
        assertThatThrownBy(
                        () ->
                                new OutboxEvent(
                                        Optional.of(1L),
                                        "QUIZ",
                                        "agg-1",
                                        "{}",
                                        T0,
                                        Optional.empty(),
                                        (short) -1,
                                        Optional.empty()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("[0, 20]");
    }

    @Test
    void rejectsLastErrorOver500Chars() {
        String tooLong = "x".repeat(501);

        assertThatThrownBy(
                        () ->
                                new OutboxEvent(
                                        Optional.of(1L),
                                        "QUIZ",
                                        "agg-1",
                                        "{}",
                                        T0,
                                        Optional.empty(),
                                        (short) 1,
                                        Optional.of(tooLong)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("500");
    }

    @Test
    void acceptsEmptyOptionals() {
        OutboxEvent e =
                new OutboxEvent(
                        Optional.empty(),
                        "QUIZ",
                        "agg-1",
                        "{}",
                        T0,
                        Optional.empty(),
                        (short) 0,
                        Optional.empty());

        assertThat(e.outboxId()).isEmpty();
        assertThat(e.deliveredAt()).isEmpty();
        assertThat(e.lastError()).isEmpty();
    }

    @Test
    void rejectsNullOptionals() {
        assertThatThrownBy(
                        () ->
                                new OutboxEvent(
                                        null,
                                        "QUIZ",
                                        "agg-1",
                                        "{}",
                                        T0,
                                        Optional.empty(),
                                        (short) 0,
                                        Optional.empty()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("outboxId");
    }
}
