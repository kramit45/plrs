package com.plrs.infrastructure.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CooccurrenceMatrixTest {

    private static final UUID U1 = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID U2 = UUID.fromString("22222222-0000-0000-0000-000000000002");
    private static final UUID U3 = UUID.fromString("33333333-0000-0000-0000-000000000003");

    @Test
    void buildAssignsCorrectCellsAndCapsAtOne() {
        CooccurrenceMatrix m =
                CooccurrenceMatrix.builder()
                        .add(U1, 100L, 0.6)
                        .add(U1, 100L, 0.4) // max wins, cell stays 0.6
                        .add(U1, 200L, 1.5) // capped at 1.0
                        .add(U2, 100L, 0.8)
                        .build();

        int u1 = m.userIds().indexOf(U1);
        int u2 = m.userIds().indexOf(U2);
        int item100 = m.contentIds().indexOf(100L);
        int item200 = m.contentIds().indexOf(200L);
        assertThat(m.get(u1, item100)).isCloseTo(0.6, within(1e-9));
        assertThat(m.get(u1, item200)).isCloseTo(1.0, within(1e-9));
        assertThat(m.get(u2, item100)).isCloseTo(0.8, within(1e-9));
    }

    @Test
    void l2NormaliseColumnsMakesColumnNormsOne() {
        CooccurrenceMatrix m =
                CooccurrenceMatrix.builder()
                        .add(U1, 1L, 0.6)
                        .add(U2, 1L, 0.8)
                        .build();
        m.l2NormaliseColumns();

        int u1 = m.userIds().indexOf(U1);
        int u2 = m.userIds().indexOf(U2);
        int col = m.contentIds().indexOf(1L);
        double sumSq = m.get(u1, col) * m.get(u1, col) + m.get(u2, col) * m.get(u2, col);
        assertThat(Math.sqrt(sumSq)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void allZeroColumnSurvivesNormalisation() {
        CooccurrenceMatrix m =
                CooccurrenceMatrix.builder()
                        .add(U1, 1L, 0.5)
                        .add(U2, 2L, 0.5)
                        .build();
        // Reset col 1 to all zeros via a fresh builder where item 2 has no
        // users — easier here: build manually with two items, one of which
        // has no rows.
        CooccurrenceMatrix m2 =
                CooccurrenceMatrix.builder()
                        .add(U1, 1L, 0.0) // contributes a 0 cell on col 1
                        .add(U2, 2L, 0.5)
                        .build();
        m2.l2NormaliseColumns();
        int u1 = m2.userIds().indexOf(U1);
        int col1 = m2.contentIds().indexOf(1L);
        assertThat(m2.get(u1, col1)).isZero();
    }

    @Test
    void itemItemCosineOnIdenticalColumnsIsOne() {
        CooccurrenceMatrix m =
                CooccurrenceMatrix.builder()
                        .add(U1, 1L, 0.5)
                        .add(U2, 1L, 0.5)
                        .add(U1, 2L, 0.5)
                        .add(U2, 2L, 0.5)
                        .build();
        m.l2NormaliseColumns();
        double[][] sim = m.itemItemCosine();
        int item1 = m.contentIds().indexOf(1L);
        int item2 = m.contentIds().indexOf(2L);
        assertThat(sim[item1][item2]).isCloseTo(1.0, within(1e-9));
        // Symmetric.
        assertThat(sim[item2][item1]).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void itemItemCosineOnOrthogonalColumnsIsZero() {
        CooccurrenceMatrix m =
                CooccurrenceMatrix.builder()
                        .add(U1, 1L, 1.0)
                        .add(U2, 2L, 1.0)
                        .build();
        m.l2NormaliseColumns();
        double[][] sim = m.itemItemCosine();
        int item1 = m.contentIds().indexOf(1L);
        int item2 = m.contentIds().indexOf(2L);
        assertThat(sim[item1][item2]).isZero();
    }

    @Test
    void itemSelfSimilarityIsOneAfterNormalisation() {
        CooccurrenceMatrix m =
                CooccurrenceMatrix.builder().add(U1, 1L, 0.5).add(U2, 1L, 0.7).build();
        m.l2NormaliseColumns();
        double[][] sim = m.itemItemCosine();
        int item1 = m.contentIds().indexOf(1L);
        assertThat(sim[item1][item1]).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void partialOverlapYieldsCosineBetweenZeroAndOne() {
        // U1, U2 both interact with both items at equal weight.
        // U3 only with item 1. item 1 ~ {U1, U2, U3}, item 2 ~ {U1, U2}.
        CooccurrenceMatrix m =
                CooccurrenceMatrix.builder()
                        .add(U1, 1L, 1.0)
                        .add(U2, 1L, 1.0)
                        .add(U3, 1L, 1.0)
                        .add(U1, 2L, 1.0)
                        .add(U2, 2L, 1.0)
                        .build();
        m.l2NormaliseColumns();
        double[][] sim = m.itemItemCosine();
        int item1 = m.contentIds().indexOf(1L);
        int item2 = m.contentIds().indexOf(2L);
        // Expected: sqrt(2/3) ≈ 0.8165
        assertThat(sim[item1][item2])
                .isCloseTo(Math.sqrt(2.0 / 3.0), within(1e-9));
    }
}
