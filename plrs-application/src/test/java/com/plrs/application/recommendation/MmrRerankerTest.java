package com.plrs.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.plrs.domain.content.ContentId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MmrRerankerTest {

    private static final ContentId C1 = ContentId.of(1L);
    private static final ContentId C2 = ContentId.of(2L);
    private static final ContentId C3 = ContentId.of(3L);
    private static final ContentId C4 = ContentId.of(4L);

    @Mock private ContentSimilarity similarity;

    private MmrReranker reranker() {
        return new MmrReranker(similarity);
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertThat(reranker().rerank(List.of(), Map.of(), 5)).isEmpty();
    }

    @Test
    void nonPositiveKReturnsEmpty() {
        assertThat(reranker().rerank(List.of(C1, C2), Map.of(C1, 1.0, C2, 0.5), 0))
                .isEmpty();
        assertThat(reranker().rerank(List.of(C1, C2), Map.of(C1, 1.0, C2, 0.5), -1))
                .isEmpty();
    }

    @Test
    void firstSlotIsAlwaysHighestRelevance() {
        // Even if every candidate were perfectly similar to every other,
        // the first slot is just "pick the front of the list".
        lenient().when(similarity.cosine(any(), any())).thenReturn(0.99);

        List<ContentId> out =
                reranker()
                        .rerank(
                                List.of(C1, C2, C3),
                                Map.of(C1, 0.9, C2, 0.5, C3, 0.4),
                                3);

        assertThat(out).startsWith(C1);
    }

    @Test
    void zeroSimilarityPreservesRelevanceOrder() {
        // With sim ≡ 0, MMR collapses to 0.30 * relevance — order must
        // match the relevance ranking.
        when(similarity.cosine(any(), any())).thenReturn(0.0);

        List<ContentId> out =
                reranker()
                        .rerank(
                                List.of(C1, C2, C3),
                                Map.of(C1, 0.9, C2, 0.5, C3, 0.4),
                                3);

        assertThat(out).containsExactly(C1, C2, C3);
    }

    @Test
    void similarItemsAreSeparatedByDiverseAlternative() {
        // C1 and C2 are clones (sim = 0.9). C3 is unrelated to C1
        // (sim = 0.0). With λ = 0.30:
        //   slot 1 = C1 (top relevance).
        //   slot 2 candidates:
        //     C2: 0.30 * 0.85 - 0.70 * 0.9   = 0.255 - 0.63   = -0.375
        //     C3: 0.30 * 0.80 - 0.70 * 0.0   = 0.240 - 0.000  =  0.240
        //   → C3 wins, beating C2 despite lower relevance.
        Map<ContentId, Double> rel = Map.of(C1, 0.95, C2, 0.85, C3, 0.80);
        when(similarity.cosine(eq(C2), eq(C1))).thenReturn(0.9);
        when(similarity.cosine(eq(C3), eq(C1))).thenReturn(0.0);
        // Tail-of-loop sim queries when picking the third slot.
        lenient().when(similarity.cosine(eq(C2), eq(C3))).thenReturn(0.0);

        List<ContentId> out =
                reranker().rerank(List.of(C1, C2, C3), rel, 3);

        assertThat(out).containsExactly(C1, C3, C2);
    }

    @Test
    void respectsKEvenWhenPoolIsLarger() {
        when(similarity.cosine(any(), any())).thenReturn(0.0);

        List<ContentId> out =
                reranker()
                        .rerank(
                                List.of(C1, C2, C3, C4),
                                Map.of(C1, 0.9, C2, 0.7, C3, 0.5, C4, 0.3),
                                2);

        assertThat(out).hasSize(2);
        assertThat(out).containsExactly(C1, C2);
    }

    @Test
    void unknownRelevanceDefaultsToZero() {
        // Slot 1 = C1 (only item with a relevance entry).
        // Slot 2 candidates have rel = 0; sim(C2,C1)=0.10, sim(C3,C1)=0.50 →
        // C2 wins because diversity penalty is smaller.
        when(similarity.cosine(eq(C2), eq(C1))).thenReturn(0.10);
        when(similarity.cosine(eq(C3), eq(C1))).thenReturn(0.50);

        Map<ContentId, Double> rel = new HashMap<>();
        rel.put(C1, 0.9);

        List<ContentId> out =
                reranker().rerank(List.of(C1, C2, C3), rel, 3);

        assertThat(out).containsExactly(C1, C2, C3);
    }
}
