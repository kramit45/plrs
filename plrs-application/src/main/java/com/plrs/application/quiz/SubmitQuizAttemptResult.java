package com.plrs.application.quiz;

import com.plrs.domain.quiz.PerItemFeedback;
import java.math.BigDecimal;
import java.util.List;

/**
 * Outcome of a successful {@link SubmitQuizAttemptUseCase} call:
 * persisted attempt id, score, counts, and per-item feedback. The web
 * layer renders this directly back to the student.
 */
public record SubmitQuizAttemptResult(
        Long attemptId,
        BigDecimal score,
        int correctCount,
        int totalCount,
        List<PerItemFeedback> perItemFeedback) {}
