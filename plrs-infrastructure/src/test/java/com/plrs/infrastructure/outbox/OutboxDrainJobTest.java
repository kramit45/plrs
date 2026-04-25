package com.plrs.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.plrs.application.outbox.OutboxEvent;
import com.plrs.application.outbox.OutboxPublisher;
import com.plrs.application.outbox.OutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxDrainJobTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    @Mock private OutboxRepository repo;
    @Mock private OutboxPublisher publisher;

    private OutboxDrainJob job;

    @BeforeEach
    void setUp() {
        job = new OutboxDrainJob(repo, publisher, CLOCK, 25);
    }

    private static OutboxEvent saved(long id) {
        return new OutboxEvent(
                Optional.of(id),
                "QUIZ",
                "agg-" + id,
                "{}",
                T0,
                Optional.empty(),
                (short) 0,
                Optional.empty());
    }

    @Test
    void drainWithEmptyBatchDoesNothing() {
        when(repo.findUndelivered(25)).thenReturn(List.of());

        job.drain();

        verify(publisher, never()).publish(any());
        verify(repo, never()).markDelivered(any(), any());
        verify(repo, never()).recordFailure(any(), any());
    }

    @Test
    void drainPublishesEachEventThenMarksDelivered() {
        when(repo.findUndelivered(25)).thenReturn(List.of(saved(1L), saved(2L), saved(3L)));

        job.drain();

        verify(publisher, times(3)).publish(any(OutboxEvent.class));
        verify(repo).markDelivered(eq(1L), eq(T0));
        verify(repo).markDelivered(eq(2L), eq(T0));
        verify(repo).markDelivered(eq(3L), eq(T0));
        verify(repo, never()).recordFailure(any(), any());
    }

    @Test
    void drainRecordsFailureWhenPublisherThrows() {
        OutboxEvent good1 = saved(1L);
        OutboxEvent failing = saved(2L);
        OutboxEvent good2 = saved(3L);
        when(repo.findUndelivered(25)).thenReturn(List.of(good1, failing, good2));
        // Sequential stubbing matches the deterministic iteration order:
        // call 1 (good1) succeeds, call 2 (failing) throws, call 3 (good2)
        // succeeds. Using sequential rather than predicate-based stubbing
        // avoids Mockito strict-stubbing's argument-mismatch warning.
        doNothing()
                .doThrow(new RuntimeException("broker offline"))
                .doNothing()
                .when(publisher)
                .publish(any(OutboxEvent.class));

        job.drain();

        verify(publisher, times(3)).publish(any());
        verify(repo).markDelivered(eq(1L), any());
        verify(repo).markDelivered(eq(3L), any());
        verify(repo, never()).markDelivered(eq(2L), any());
        verify(repo).recordFailure(eq(2L), anyString());
    }

    @Test
    void drainLogsSummaryWhenAnyProcessingOccurred() {
        // Smoke check: drain() returns normally and a summary log line is
        // emitted (not asserted via log capture here — covered by the IT).
        when(repo.findUndelivered(25)).thenReturn(List.of(saved(1L)));

        assertThatCode(() -> job.drain()).doesNotThrowAnyException();

        verify(publisher).publish(any());
        verify(repo).markDelivered(eq(1L), any());
    }

    @Test
    void drainSurvivesRecordFailureAlsoThrowing() {
        OutboxEvent failing = saved(7L);
        when(repo.findUndelivered(25)).thenReturn(List.of(failing));
        doThrow(new RuntimeException("publish failed"))
                .when(publisher)
                .publish(any());
        doThrow(new RuntimeException("recordFailure also blew up"))
                .when(repo)
                .recordFailure(eq(7L), anyString());

        assertThatCode(() -> job.drain())
                .as("drain must not propagate; both failures are logged")
                .doesNotThrowAnyException();

        verify(repo).recordFailure(eq(7L), anyString());
        verify(repo, never()).markDelivered(any(), any());
    }
}
