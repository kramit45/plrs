package com.plrs.infrastructure.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

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
class RedisCbScorerTest {

    private static final UserId USER =
            UserId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));
    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");

    @Mock private InteractionRepository interactionRepository;
    @Mock private TfIdfReader tfIdfReader;

    private RedisCbScorer scorer() {
        return new RedisCbScorer(interactionRepository, tfIdfReader);
    }

    private static InteractionEvent completedAt(long contentId, Instant at) {
        return InteractionEvent.complete(
                USER,
                ContentId.of(contentId),
                at,
                Optional.empty(),
                Optional.empty());
    }

    @Test
    void emptyHistoryYieldsAllZeros() {
        when(interactionRepository.findRecentByEventType(eq(USER), eq(EventType.COMPLETE), anyInt()))
                .thenReturn(List.of());

        Map<ContentId, Double> out =
                scorer().score(USER, Set.of(ContentId.of(1L), ContentId.of(2L)));

        assertThat(out)
                .containsOnlyKeys(ContentId.of(1L), ContentId.of(2L))
                .allSatisfy((id, score) -> assertThat(score).isZero());
    }

    @Test
    void candidateAlreadyInHistoryScoresZero() {
        when(interactionRepository.findRecentByEventType(eq(USER), eq(EventType.COMPLETE), anyInt()))
                .thenReturn(List.of(completedAt(42L, T0)));
        when(tfIdfReader.centroid(any())).thenReturn(new double[] {0.6, 0.8});

        Map<ContentId, Double> out = scorer().score(USER, Set.of(ContentId.of(42L)));

        assertThat(out.get(ContentId.of(42L))).isZero();
    }

    @Test
    void similarCandidateRanksHigherThanDissimilar() {
        when(interactionRepository.findRecentByEventType(eq(USER), eq(EventType.COMPLETE), anyInt()))
                .thenReturn(List.of(completedAt(10L, T0)));
        when(tfIdfReader.centroid(any())).thenReturn(new double[] {0.6, 0.8});
        when(tfIdfReader.cosineWith(eq(ContentId.of(20L)), any())).thenReturn(0.85);
        when(tfIdfReader.cosineWith(eq(ContentId.of(30L)), any())).thenReturn(0.10);

        Map<ContentId, Double> out =
                scorer().score(USER, Set.of(ContentId.of(20L), ContentId.of(30L)));

        assertThat(out.get(ContentId.of(20L))).isCloseTo(0.85, within(1e-9));
        assertThat(out.get(ContentId.of(30L))).isCloseTo(0.10, within(1e-9));
    }

    @Test
    void allZeroCentroidShortCircuitsToZeroForEveryCandidate() {
        when(interactionRepository.findRecentByEventType(eq(USER), eq(EventType.COMPLETE), anyInt()))
                .thenReturn(List.of(completedAt(99L, T0)));
        when(tfIdfReader.centroid(any())).thenReturn(new double[] {0.0, 0.0});

        Map<ContentId, Double> out = scorer().score(USER, Set.of(ContentId.of(1L)));

        assertThat(out.get(ContentId.of(1L))).isZero();
    }

    @Test
    void candidateNotInTfIdfIndexScoresZero() {
        when(interactionRepository.findRecentByEventType(eq(USER), eq(EventType.COMPLETE), anyInt()))
                .thenReturn(List.of(completedAt(10L, T0)));
        when(tfIdfReader.centroid(any())).thenReturn(new double[] {0.6, 0.8});
        // Candidate 999 has no TF-IDF row — cosineWith returns 0.
        when(tfIdfReader.cosineWith(eq(ContentId.of(999L)), any())).thenReturn(0.0);

        Map<ContentId, Double> out = scorer().score(USER, Set.of(ContentId.of(999L)));

        assertThat(out.get(ContentId.of(999L))).isZero();
    }

    @Test
    void emptyCandidateSetReturnsEmpty() {
        Map<ContentId, Double> out = scorer().score(USER, Set.of());
        assertThat(out).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(interactionRepository, tfIdfReader);
    }
}
