package com.plrs.application.dashboard;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the dashboard's "recent quiz attempts" table. Same
 * "(deleted)" fallback as {@link RecentCompletion} when the underlying
 * quiz content has been removed.
 */
public record RecentAttempt(
        Long attemptId,
        Long quizContentId,
        String quizTitle,
        BigDecimal score,
        Instant attemptedAt) {}
