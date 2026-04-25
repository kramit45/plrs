package com.plrs.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.user.UserId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HybridRankerTest {

    private static final UserId USER =
            UserId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));
    private static final ContentId C1 = ContentId.of(1L);
    private static final ContentId C2 = ContentId.of(2L);

    @Mock private CfScorer cfScorer;
    @Mock private CbScorer cbScorer;
    @Mock private PopularityScorer popularityScorer;

    private HybridRanker ranker() {
        return new HybridRanker(cfScorer, cbScorer, popularityScorer);
    }

    @Test
    void warmPathBlendsLambdaCfAnd1MinusLambdaCb() {
        // λ = 0.65; expected: 0.65 * 0.8 + 0.35 * 0.4 = 0.66.
        when(cfScorer.score(eq(USER), any())).thenReturn(Map.of(C1, 0.8));
        when(cbScorer.score(eq(USER), any())).thenReturn(Map.of(C1, 0.4));
        when(popularityScorer.score(any())).thenReturn(Map.of(C1, 0.1));

        Map<ContentId, Blended> out = ranker().blend(USER, Set.of(C1));

        Blended b = out.get(C1);
        assertThat(b.score()).isCloseTo(0.66, within(1e-9));
        assertThat(b.cf()).isEqualTo(0.8);
        assertThat(b.cb()).isEqualTo(0.4);
        assertThat(b.popularity()).isEqualTo(0.1);
        assertThat(b.coldStart()).isFalse();
    }

    @Test
    void coldStartBranchEmitsPopularityWhenCfAndCbAverageBelowThreshold() {
        when(cfScorer.score(eq(USER), any())).thenReturn(Map.of(C1, 0.0, C2, 0.0));
        when(cbScorer.score(eq(USER), any())).thenReturn(Map.of(C1, 0.01, C2, 0.0));
        when(popularityScorer.score(any())).thenReturn(Map.of(C1, 0.5, C2, 0.9));

        Map<ContentId, Blended> out = ranker().blend(USER, Set.of(C1, C2));

        // Both averages are below the 0.05 threshold → cold start;
        // every Blended.score equals popularity.
        assertThat(out.get(C1).coldStart()).isTrue();
        assertThat(out.get(C2).coldStart()).isTrue();
        assertThat(out.get(C1).score()).isCloseTo(0.5, within(1e-9));
        assertThat(out.get(C2).score()).isCloseTo(0.9, within(1e-9));
    }

    @Test
    void averageJustAboveThresholdAvoidsColdStartFallback() {
        // CF avg = 0.06 (above 0.05); not cold-start.
        when(cfScorer.score(eq(USER), any())).thenReturn(Map.of(C1, 0.06, C2, 0.06));
        when(cbScorer.score(eq(USER), any())).thenReturn(Map.of(C1, 0.0, C2, 0.0));
        when(popularityScorer.score(any())).thenReturn(Map.of(C1, 0.7, C2, 0.7));

        Map<ContentId, Blended> out = ranker().blend(USER, Set.of(C1, C2));

        assertThat(out.get(C1).coldStart()).isFalse();
        // 0.65 * 0.06 + 0.35 * 0 = 0.039, NOT 0.7.
        assertThat(out.get(C1).score()).isCloseTo(0.65 * 0.06, within(1e-9));
    }

    @Test
    void emptyCandidateSetReturnsEmpty() {
        assertThat(ranker().blend(USER, Set.of())).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(cfScorer, cbScorer, popularityScorer);
    }

    @Test
    void scorerReturningPartialMapBacksOffToZeroForUnknownCandidates() {
        // CF scorer returns only C1; C2 should default to 0.
        when(cfScorer.score(eq(USER), any())).thenReturn(Map.of(C1, 0.8));
        when(cbScorer.score(eq(USER), any())).thenReturn(Map.of(C1, 0.4, C2, 0.4));
        when(popularityScorer.score(any())).thenReturn(Map.of(C1, 0.1, C2, 0.5));

        Map<ContentId, Blended> out = ranker().blend(USER, Set.of(C1, C2));

        assertThat(out.get(C2).cf()).isZero();
        assertThat(out.get(C2).cb()).isCloseTo(0.4, within(1e-9));
    }
}
