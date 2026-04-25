package com.plrs.web.recommendation;

/**
 * Wire shape of one recommendation in the
 * {@code GET /api/recommendations} response. Flattens the
 * {@link com.plrs.domain.recommendation.Recommendation} aggregate
 * plus the looked-up {@link com.plrs.domain.content.Content} and its
 * topic into a single Jackson-friendly record — primitives only, so
 * we don't have to teach Jackson about the framework-free domain
 * value objects (TopicId, ContentId, etc.).
 */
public record RecommendationResponse(
        Long contentId,
        String title,
        String topic,
        String ctype,
        String difficulty,
        int estMinutes,
        double score,
        int rank,
        String reason) {}
