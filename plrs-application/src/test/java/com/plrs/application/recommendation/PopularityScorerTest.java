package com.plrs.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.interaction.InteractionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PopularityScorerTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    @Mock private InteractionRepository repo;

    private PopularityScorer scorer() {
        return new PopularityScorer(repo, CLOCK);
    }

    @Test
    void normalisesScoresToUnitIntervalDividingByMaxCount() {
        ContentId a = ContentId.of(1L);
        ContentId b = ContentId.of(2L);
        ContentId c = ContentId.of(3L);
        when(repo.countByContentSince(any(), any()))
                .thenReturn(Map.of(a, 50L, b, 100L, c, 25L));

        Map<ContentId, Double> out = scorer().score(List.of(a, b, c));

        assertThat(out.get(a)).isCloseTo(0.50, within(1e-9));
        assertThat(out.get(b)).isCloseTo(1.00, within(1e-9));
        assertThat(out.get(c)).isCloseTo(0.25, within(1e-9));
        assertThat(out).allSatisfy(
                (id, score) -> assertThat(score).isBetween(0.0, 1.0));
    }

    @Test
    void candidatesWithZeroEventsScoreZeroAndArePresentInOutput() {
        ContentId a = ContentId.of(1L);
        ContentId b = ContentId.of(2L);
        when(repo.countByContentSince(any(), any())).thenReturn(Map.of(a, 10L));

        Map<ContentId, Double> out = scorer().score(List.of(a, b));

        assertThat(out).containsOnlyKeys(a, b);
        assertThat(out.get(b)).isZero();
    }

    @Test
    void emptyWindowYieldsAllZeroScores() {
        ContentId a = ContentId.of(1L);
        ContentId b = ContentId.of(2L);
        when(repo.countByContentSince(any(), any())).thenReturn(Map.of());

        Map<ContentId, Double> out = scorer().score(List.of(a, b));

        assertThat(out.get(a)).isZero();
        assertThat(out.get(b)).isZero();
    }

    @Test
    void emptyCandidateSetReturnsEmpty() {
        Map<ContentId, Double> out = scorer().score(List.of());
        assertThat(out).isEmpty();
    }

    @Test
    void windowIsExactlyThirtyDaysBack() {
        ContentId a = ContentId.of(1L);
        when(repo.countByContentSince(any(), any())).thenReturn(Map.of(a, 1L));

        scorer().score(List.of(a));

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.verify(repo).countByContentSince(any(Collection.class), since.capture());
        assertThat(Duration.between(since.getValue(), T0).toDays()).isEqualTo(30L);
    }

    @Test
    void singleCandidateWithEventsScoresOne() {
        ContentId only = ContentId.of(42L);
        when(repo.countByContentSince(eq(List.of(only)), any())).thenReturn(Map.of(only, 7L));

        assertThat(scorer().score(List.of(only)).get(only)).isCloseTo(1.0, within(1e-9));
    }
}
