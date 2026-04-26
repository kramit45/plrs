package com.plrs.web.path;

import java.time.Instant;

/** Per-step REST projection. */
public record LearnerPathStepView(
        int stepOrder,
        Long contentId,
        String title,
        String status,
        boolean addedAsReview,
        String reasonInPath,
        Instant startedAt,
        Instant completedAt) {}
