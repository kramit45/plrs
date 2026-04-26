package com.plrs.web.path;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.path.LearnerPath;
import com.plrs.domain.path.LearnerPathStep;
import com.plrs.domain.path.StepStatus;
import java.util.List;
import java.util.Map;

/** Compact projection used by the dashboard card. */
public record LearningPathSummary(
        Long pathId,
        Long targetTopicId,
        String status,
        int totalSteps,
        int completedSteps,
        String nextStepTitle) {

    public static LearningPathSummary from(LearnerPath path, Map<ContentId, String> titles) {
        List<LearnerPathStep> steps = path.steps();
        int total = steps.size();
        int done = 0;
        String nextTitle = null;
        for (LearnerPathStep s : steps) {
            if (s.status() == StepStatus.DONE || s.status() == StepStatus.SKIPPED) {
                done++;
            } else if (nextTitle == null) {
                nextTitle = titles.getOrDefault(s.contentId(), "(unknown)");
            }
        }
        return new LearningPathSummary(
                path.id().map(p -> p.value()).orElse(null),
                path.targetTopicId().value(),
                path.status().name(),
                total,
                done,
                nextTitle);
    }
}
