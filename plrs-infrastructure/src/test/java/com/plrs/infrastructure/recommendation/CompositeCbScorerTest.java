package com.plrs.infrastructure.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.plrs.application.recommendation.MlServiceClient;
import com.plrs.application.recommendation.MlServiceException;
import com.plrs.application.recommendation.SimNeighbour;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.interaction.EventType;
import com.plrs.domain.interaction.InteractionEvent;
import com.plrs.domain.interaction.InteractionRepository;
import com.plrs.domain.user.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CompositeCbScorerTest {

    private static final UserId USER =
            UserId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));
    private static final ContentId C1 = ContentId.of(1L);
    private static final ContentId C2 = ContentId.of(2L);
    private static final ContentId HIST_A = ContentId.of(100L);
    private static final ContentId HIST_B = ContentId.of(101L);

    @Mock private MlServiceClient ml;
    @Mock private RedisCbScorer fallback;
    @Mock private InteractionRepository interactionRepository;

    private CompositeCbScorer scorer() {
        return new CompositeCbScorer(ml, fallback, interactionRepository);
    }

    private static InteractionEvent completed(ContentId c) {
        return InteractionEvent.complete(
                USER,
                c,
                Instant.parse("2026-04-25T10:00:00Z"),
                Optional.empty(),
                Optional.empty());
    }

    @Test
    void mlUnreachableDelegatesToFallback() {
        when(ml.isReachable()).thenReturn(false);
        when(fallback.score(USER, Set.of(C1, C2)))
                .thenReturn(Map.of(C1, 0.6, C2, 0.2));

        Map<ContentId, Double> out = scorer().score(USER, Set.of(C1, C2));

        assertThat(out).isEqualTo(Map.of(C1, 0.6, C2, 0.2));
        verify(ml, never()).cbSimilar(any(), anyInt());
    }

    @Test
    void mlReachableAggregatesAcrossCompletedHistory() {
        when(ml.isReachable()).thenReturn(true);
        when(interactionRepository.findRecentByEventType(
                        eq(USER), eq(EventType.COMPLETE), anyInt()))
                .thenReturn(List.of(completed(HIST_A), completed(HIST_B)));
        // Both completed items list C1 as a neighbour (sims 0.4 + 0.6);
        // only HIST_A surfaces C2 (sim 0.2). With history size 2, the
        // aggregated similarity is divided by 2:
        //   C1 = (0.4 + 0.6) / 2 = 0.5
        //   C2 = (0.2 + 0.0) / 2 = 0.1
        when(ml.cbSimilar(eq(HIST_A), anyInt()))
                .thenReturn(
                        List.of(
                                new SimNeighbour(C1.value(), 0.4),
                                new SimNeighbour(C2.value(), 0.2)));
        when(ml.cbSimilar(eq(HIST_B), anyInt()))
                .thenReturn(List.of(new SimNeighbour(C1.value(), 0.6)));

        Map<ContentId, Double> out = scorer().score(USER, Set.of(C1, C2));

        assertThat(out.get(C1)).isCloseTo(0.5, within(1e-9));
        assertThat(out.get(C2)).isCloseTo(0.1, within(1e-9));
        verify(fallback, never()).score(any(), any());
    }

    @Test
    void midFlightExceptionFallsBack() {
        when(ml.isReachable()).thenReturn(true);
        when(interactionRepository.findRecentByEventType(
                        eq(USER), eq(EventType.COMPLETE), anyInt()))
                .thenReturn(List.of(completed(HIST_A)));
        when(ml.cbSimilar(any(), anyInt()))
                .thenThrow(new MlServiceException("upstream down"));
        when(fallback.score(USER, Set.of(C1)))
                .thenReturn(Map.of(C1, 0.42));

        Map<ContentId, Double> out = scorer().score(USER, Set.of(C1));

        assertThat(out).isEqualTo(Map.of(C1, 0.42));
    }

    @Test
    void emptyCandidatesReturnsEmpty() {
        assertThat(scorer().score(USER, Set.of())).isEmpty();
        verify(ml, never()).isReachable();
    }

    @Test
    void completedItemsAreNotRecommendedBack() {
        when(ml.isReachable()).thenReturn(true);
        when(interactionRepository.findRecentByEventType(
                        eq(USER), eq(EventType.COMPLETE), anyInt()))
                .thenReturn(List.of(completed(C1)));
        when(ml.cbSimilar(eq(C1), anyInt()))
                .thenReturn(List.of(new SimNeighbour(C2.value(), 0.5)));

        Map<ContentId, Double> out = scorer().score(USER, Set.of(C1, C2));

        // C1 is in history → must score 0 and not be a recommendation.
        assertThat(out.get(C1)).isZero();
        assertThat(out.get(C2)).isCloseTo(0.5, within(1e-9));
    }
}
