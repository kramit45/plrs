package com.plrs.web.recommendation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wire shape of one recommendation in the
 * {@code GET /api/recommendations} response. Flattens the
 * {@link com.plrs.domain.recommendation.Recommendation} aggregate
 * plus the looked-up {@link com.plrs.domain.content.Content} and its
 * topic into a single Jackson-friendly record — primitives only, so
 * we don't have to teach Jackson about the framework-free domain
 * value objects (TopicId, ContentId, etc.).
 *
 * <p>{@code breakdown} is the ADMIN-only debug payload: per-component
 * popularity / cf / cb scores plus the blended value that drove the
 * rank. Students see {@code null} (omitted under
 * {@code @JsonInclude(NON_NULL)}) so the slim shape stays unchanged.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecommendationResponse(
        Long contentId,
        String title,
        String topic,
        String ctype,
        String difficulty,
        int estMinutes,
        double score,
        int rank,
        String reason,
        ScoreBreakdownDto breakdown) {

    /** Convenience for the slim (non-ADMIN) path — breakdown omitted. */
    public static RecommendationResponse slim(
            Long contentId,
            String title,
            String topic,
            String ctype,
            String difficulty,
            int estMinutes,
            double score,
            int rank,
            String reason) {
        return new RecommendationResponse(
                contentId, title, topic, ctype, difficulty, estMinutes,
                score, rank, reason, null);
    }

    /** ADMIN-only debug projection of {@code application.ScoreBreakdown}. */
    public record ScoreBreakdownDto(double popularity, double cf, double cb, double blended) {}
}
