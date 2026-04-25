package com.plrs.web.quiz;

import com.plrs.application.quiz.SubmitQuizAttemptResult;
import com.plrs.domain.quiz.PerItemFeedback;
import java.math.BigDecimal;
import java.util.List;

/** 201-response body for {@code POST /api/quiz-attempts}. */
public record SubmitQuizAttemptResponse(
        Long attemptId,
        BigDecimal score,
        int correctCount,
        int totalCount,
        List<PerItemFeedback> perItemFeedback) {

    public static SubmitQuizAttemptResponse from(SubmitQuizAttemptResult r) {
        return new SubmitQuizAttemptResponse(
                r.attemptId(),
                r.score(),
                r.correctCount(),
                r.totalCount(),
                r.perItemFeedback());
    }
}
