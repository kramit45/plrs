package com.plrs.web.quiz;

import com.plrs.application.quiz.SubmitQuizAttemptResult;
import com.plrs.domain.quiz.PerItemFeedback;
import java.math.BigDecimal;
import java.util.List;

/**
 * 201-response body for {@code POST /api/quiz-attempts}. Flattens the
 * domain {@link PerItemFeedback} (whose {@code topicId} is a
 * {@link com.plrs.domain.topic.TopicId} value object that Jackson can't
 * serialize without polluting the framework-free domain) into a wire
 * record with primitive fields.
 */
public record SubmitQuizAttemptResponse(
        Long attemptId,
        BigDecimal score,
        int correctCount,
        int totalCount,
        List<PerItemFeedbackDto> perItemFeedback) {

    /** Flat per-item feedback DTO — only types Jackson can serialise. */
    public record PerItemFeedbackDto(
            int itemOrder,
            int selectedOptionOrder,
            int correctOptionOrder,
            boolean isCorrect,
            Long topicId) {

        public static PerItemFeedbackDto from(PerItemFeedback f) {
            return new PerItemFeedbackDto(
                    f.itemOrder(),
                    f.selectedOptionOrder(),
                    f.correctOptionOrder(),
                    f.isCorrect(),
                    f.topicId().value());
        }
    }

    public static SubmitQuizAttemptResponse from(SubmitQuizAttemptResult r) {
        return new SubmitQuizAttemptResponse(
                r.attemptId(),
                r.score(),
                r.correctCount(),
                r.totalCount(),
                r.perItemFeedback().stream().map(PerItemFeedbackDto::from).toList());
    }
}
