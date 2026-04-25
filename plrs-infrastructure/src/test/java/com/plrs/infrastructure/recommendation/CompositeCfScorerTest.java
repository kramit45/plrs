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
class CompositeCfScorerTest {

    private static final UserId USER =
            UserId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));
    private static final ContentId C1 = ContentId.of(1L);
    private static final ContentId C2 = ContentId.of(2L);
    private static final ContentId HIST_A = ContentId.of(100L);

    @Mock private MlServiceClient ml;
    @Mock private RedisCfScorer fallback;
    @Mock private InteractionRepository interactionRepository;

    private CompositeCfScorer scorer() {
        return new CompositeCfScorer(ml, fallback, interactionRepository);
    }

    private static InteractionEvent positive(ContentId c) {
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
                .thenReturn(Map.of(C1, 0.9, C2, 0.5));

        Map<ContentId, Double> out = scorer().score(USER, Set.of(C1, C2));

        assertThat(out).isEqualTo(Map.of(C1, 0.9, C2, 0.5));
        verify(ml, never()).cfSimilar(any(), anyInt());
    }

    @Test
    void mlReachableUsesMlAndIntersectsWithUserHistory() {
        when(ml.isReachable()).thenReturn(true);
        when(interactionRepository.findRecentPositives(eq(USER), anyInt(), anyInt()))
                .thenReturn(List.of(positive(HIST_A)));
        // C1's slab includes HIST_A → match → similarity 0.9.
        when(ml.cfSimilar(eq(C1), anyInt()))
                .thenReturn(List.of(new SimNeighbour(HIST_A.value(), 0.9)));
        // C2's slab has no overlap → score 0.
        when(ml.cfSimilar(eq(C2), anyInt()))
                .thenReturn(List.of(new SimNeighbour(999L, 0.4)));

        Map<ContentId, Double> out = scorer().score(USER, Set.of(C1, C2));

        // After normalisation C1 (the only matched candidate) → 1.0.
        assertThat(out.get(C1)).isCloseTo(1.0, within(1e-9));
        assertThat(out.get(C2)).isZero();
        verify(fallback, never()).score(any(), any());
    }

    @Test
    void midFlightExceptionFallsBack() {
        when(ml.isReachable()).thenReturn(true);
        when(interactionRepository.findRecentPositives(eq(USER), anyInt(), anyInt()))
                .thenReturn(List.of(positive(HIST_A)));
        when(ml.cfSimilar(any(), anyInt()))
                .thenThrow(new MlServiceException("upstream down"));
        when(fallback.score(USER, Set.of(C1, C2)))
                .thenReturn(Map.of(C1, 0.7, C2, 0.3));

        Map<ContentId, Double> out = scorer().score(USER, Set.of(C1, C2));

        assertThat(out).isEqualTo(Map.of(C1, 0.7, C2, 0.3));
    }

    @Test
    void emptyCandidatesReturnsEmptyWithoutCallingEither() {
        Map<ContentId, Double> out = scorer().score(USER, Set.of());

        assertThat(out).isEmpty();
        verify(ml, never()).isReachable();
        verify(fallback, never()).score(any(), any());
    }

    @Test
    void coldStartUserShortCircuitsToZeros() {
        when(ml.isReachable()).thenReturn(true);
        when(interactionRepository.findRecentPositives(eq(USER), anyInt(), anyInt()))
                .thenReturn(List.of());

        Map<ContentId, Double> out = scorer().score(USER, Set.of(C1, C2));

        assertThat(out).containsOnly(
                org.assertj.core.api.Assertions.entry(C1, 0.0),
                org.assertj.core.api.Assertions.entry(C2, 0.0));
        verify(ml, never()).cfSimilar(any(), anyInt());
    }
}
