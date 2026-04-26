package com.plrs.domain.path;

/**
 * Per-step lifecycle inside a {@link LearnerPath}. Distinct from the
 * path-level {@link LearnerPathStatus}: a path may be {@code IN_PROGRESS}
 * while its individual steps move through this smaller enum.
 *
 * <p>{@link #SKIPPED} is for review or backfill paths the planner may
 * insert that the learner explicitly bypasses — distinct from
 * {@link #DONE} so completion-rate analytics stay honest.
 *
 * <p>Traces to: §3.c.1.4 (learner_path_steps.step_status enum).
 */
public enum StepStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
    SKIPPED
}
