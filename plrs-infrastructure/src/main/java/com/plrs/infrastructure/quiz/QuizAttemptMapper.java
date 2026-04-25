package com.plrs.infrastructure.quiz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.quiz.PerItemFeedback;
import com.plrs.domain.quiz.QuizAttempt;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Bridge between {@link QuizAttempt} (domain) and
 * {@link QuizAttemptJpaEntity} (infra). The {@code per_item_json}
 * column carries both the per-item feedback list and the per-topic
 * weights inside one JSON object so a single column mapping covers
 * both (matches the §3.c.1.4 schema where only {@code per_item_json}
 * exists).
 *
 * <p>Traces to: §3.c.1.4 (quiz_attempts schema), FR-20.
 */
@Component
public class QuizAttemptMapper {

    private static final String DEFAULT_POLICY_VERSION = "v1";

    private final ObjectMapper objectMapper;

    public QuizAttemptMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public QuizAttemptJpaEntity toEntity(QuizAttempt attempt) {
        if (attempt == null) {
            return null;
        }
        return new QuizAttemptJpaEntity(
                null,
                attempt.userId().value(),
                attempt.quizContentId().value(),
                attempt.score(),
                serialisePayload(attempt),
                DEFAULT_POLICY_VERSION,
                attempt.attemptedAt());
    }

    public QuizAttempt toDomain(QuizAttemptJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        Payload p = deserialisePayload(entity.getPerItemJson());
        List<PerItemFeedback> feedback =
                p.perItem.stream().map(QuizAttemptMapper::toFeedback).toList();
        Map<TopicId, BigDecimal> weights = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : p.topicWeights.entrySet()) {
            weights.put(TopicId.of(Long.valueOf(e.getKey())), e.getValue());
        }
        return new QuizAttempt(
                UserId.of(entity.getUserId()),
                ContentId.of(entity.getContentId()),
                entity.getScore(),
                (int) feedback.stream().filter(PerItemFeedback::isCorrect).count(),
                feedback.size(),
                feedback,
                weights,
                entity.getAttemptedAt());
    }

    private String serialisePayload(QuizAttempt attempt) {
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        for (Map.Entry<TopicId, BigDecimal> e : attempt.topicWeights().entrySet()) {
            weights.put(String.valueOf(e.getKey().value()), e.getValue());
        }
        Payload payload = new Payload();
        payload.perItem =
                attempt.perItemFeedback().stream()
                        .map(QuizAttemptMapper::toDto)
                        .toList();
        payload.topicWeights = weights;
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise QuizAttempt payload", e);
        }
    }

    private Payload deserialisePayload(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Payload>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialise QuizAttempt payload", e);
        }
    }

    private static FeedbackDto toDto(PerItemFeedback fb) {
        FeedbackDto dto = new FeedbackDto();
        dto.itemOrder = fb.itemOrder();
        dto.selectedOptionOrder = fb.selectedOptionOrder();
        dto.correctOptionOrder = fb.correctOptionOrder();
        dto.isCorrect = fb.isCorrect();
        dto.topicId = fb.topicId().value();
        return dto;
    }

    private static PerItemFeedback toFeedback(FeedbackDto dto) {
        return new PerItemFeedback(
                dto.itemOrder,
                dto.selectedOptionOrder,
                dto.correctOptionOrder,
                dto.isCorrect,
                TopicId.of(dto.topicId));
    }

    /** JSON-bound payload for the {@code per_item_json} column. */
    public static class Payload {
        public List<FeedbackDto> perItem;
        public Map<String, BigDecimal> topicWeights;

        @com.fasterxml.jackson.annotation.JsonProperty("per_item")
        public List<FeedbackDto> getPerItem() {
            return perItem;
        }

        @com.fasterxml.jackson.annotation.JsonProperty("per_item")
        public void setPerItem(List<FeedbackDto> perItem) {
            this.perItem = perItem;
        }

        @com.fasterxml.jackson.annotation.JsonProperty("topic_weights")
        public Map<String, BigDecimal> getTopicWeights() {
            return topicWeights;
        }

        @com.fasterxml.jackson.annotation.JsonProperty("topic_weights")
        public void setTopicWeights(Map<String, BigDecimal> topicWeights) {
            this.topicWeights = topicWeights;
        }
    }

    /** JSON shape for a single per-item feedback entry. */
    public static class FeedbackDto {
        public int itemOrder;
        public int selectedOptionOrder;
        public int correctOptionOrder;
        public boolean isCorrect;
        public Long topicId;
    }
}
