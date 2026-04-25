package com.plrs.application.recommendation;

import java.util.Optional;

/**
 * Result of a {@link MlServiceClient#rebuildFeatures} or
 * {@link MlServiceClient#recomputeCf} call.
 *
 * <p>Mirrors the JSON the Python ML service returns:
 *
 * <ul>
 *   <li>{@code status} — {@code OK} or {@code SKIPPED}.
 *   <li>{@code items}, {@code users}, {@code vocabSize} — populated
 *       on the corresponding success path; otherwise empty.
 *   <li>{@code reason} — populated when {@code status == SKIPPED}.
 * </ul>
 */
public record RebuildResult(
        String status,
        Optional<Integer> items,
        Optional<Integer> users,
        Optional<Integer> vocabSize,
        Optional<String> reason) {

    public boolean isOk() {
        return "OK".equals(status);
    }
}
