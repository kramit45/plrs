package com.plrs.infrastructure.recommendation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sparse user-item weight matrix that {@link ItemSimilarityJob} builds
 * before computing item-item cosine. Plain Java only — Iter 3 demo
 * scale (~120 items, ~5k interactions) doesn't justify a sparse
 * matrix library. The Python service in step 127 replaces this.
 *
 * <p>Stored as a parallel pair of {@link LinkedHashMap}s for stable
 * iteration order (essential for the test's golden-similarity
 * checks); rows are user buckets indexed by 0..n_users-1, columns are
 * content ids indexed by 0..n_items-1, with the actual ids exposed
 * via {@link #userIds()} / {@link #contentIds()}.
 */
final class CooccurrenceMatrix {

    /** Per-event-type weight applied when building the matrix. */
    static final double WEIGHT_COMPLETE = 0.8;
    static final double WEIGHT_BOOKMARK = 0.4;
    static final double WEIGHT_LIKE = 0.6;

    private final List<java.util.UUID> userIds = new ArrayList<>();
    private final List<Long> contentIds = new ArrayList<>();
    private final Map<java.util.UUID, Integer> userIndex = new LinkedHashMap<>();
    private final Map<Long, Integer> contentIndex = new LinkedHashMap<>();
    private double[][] cells;

    /** Builder seeded from an unbounded interaction stream. */
    static class Builder {
        private final List<Object[]> rows = new ArrayList<>();

        Builder add(java.util.UUID userId, long contentId, double weight) {
            rows.add(new Object[] {userId, contentId, weight});
            return this;
        }

        CooccurrenceMatrix build() {
            CooccurrenceMatrix m = new CooccurrenceMatrix();
            for (Object[] row : rows) {
                java.util.UUID uid = (java.util.UUID) row[0];
                long cid = (long) row[1];
                m.userIndex.computeIfAbsent(
                        uid,
                        k -> {
                            m.userIds.add(k);
                            return m.userIds.size() - 1;
                        });
                m.contentIndex.computeIfAbsent(
                        cid,
                        k -> {
                            m.contentIds.add(k);
                            return m.contentIds.size() - 1;
                        });
            }
            int nUsers = m.userIds.size();
            int nItems = m.contentIds.size();
            m.cells = new double[nUsers][nItems];
            for (Object[] row : rows) {
                int u = m.userIndex.get((java.util.UUID) row[0]);
                int c = m.contentIndex.get((long) row[1]);
                double w = (double) row[2];
                // Cap per-cell weight at 1.0; multiple interactions on
                // the same (user, item) take the max rather than
                // summing so a single event type doesn't dominate.
                if (w > m.cells[u][c]) {
                    m.cells[u][c] = Math.min(w, 1.0);
                }
            }
            return m;
        }
    }

    static Builder builder() {
        return new Builder();
    }

    int numUsers() {
        return userIds.size();
    }

    int numItems() {
        return contentIds.size();
    }

    List<Long> contentIds() {
        return List.copyOf(contentIds);
    }

    List<java.util.UUID> userIds() {
        return List.copyOf(userIds);
    }

    /** Cell weight at (userIdx, itemIdx). */
    double get(int userIdx, int itemIdx) {
        return cells[userIdx][itemIdx];
    }

    /** L2-normalises every column in place; columns with all zeros stay zero. */
    void l2NormaliseColumns() {
        int nUsers = numUsers();
        int nItems = numItems();
        for (int j = 0; j < nItems; j++) {
            double sumSq = 0.0;
            for (int i = 0; i < nUsers; i++) {
                sumSq += cells[i][j] * cells[i][j];
            }
            if (sumSq <= 0.0) {
                continue;
            }
            double norm = Math.sqrt(sumSq);
            for (int i = 0; i < nUsers; i++) {
                cells[i][j] = cells[i][j] / norm;
            }
        }
    }

    /**
     * Computes item-item cosine similarity. Assumes the matrix has
     * already been L2-normalised by column — the dot product of two
     * normalised columns IS the cosine.
     *
     * @return {@code numItems × numItems} matrix where {@code [i][j]}
     *     is the cosine between item i and item j.
     */
    double[][] itemItemCosine() {
        int nUsers = numUsers();
        int nItems = numItems();
        double[][] out = new double[nItems][nItems];
        for (int i = 0; i < nItems; i++) {
            for (int j = i; j < nItems; j++) {
                double sum = 0.0;
                for (int u = 0; u < nUsers; u++) {
                    sum += cells[u][i] * cells[u][j];
                }
                out[i][j] = sum;
                out[j][i] = sum;
            }
        }
        return out;
    }
}
