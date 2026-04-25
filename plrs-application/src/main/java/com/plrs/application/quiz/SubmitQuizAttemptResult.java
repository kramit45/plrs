package com.plrs.application.quiz;

import com.plrs.domain.quiz.PerItemFeedback;
import java.math.BigDecimal;
import java.util.List;

/**
 * Outcome of a successful {@link SubmitQuizAttemptUseCase} call: persisted
 * attempt id, score, counts, per-item feedback, and the per-topic mastery
 * deltas produced by the EWMA update (step 90, §3.c.5.7). The web layer
 * renders this directly back to the student so they see how the attempt
 * moved their mastery vector.
 */
public record SubmitQuizAttemptResult(
        Long attemptId,
        BigDecimal score,
        int correctCount,
        int totalCount,
        List<PerItemFeedback> perItemFeedback,
        List<MasteryDelta> masteryDeltas) {}
