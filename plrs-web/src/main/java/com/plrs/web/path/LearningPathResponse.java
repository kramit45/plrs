package com.plrs.web.path;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.path.LearnerPath;
import com.plrs.domain.path.LearnerPathDraft;
import com.plrs.domain.path.LearnerPathStep;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST projection of a {@link LearnerPath} (or a planner preview
 * {@link LearnerPathDraft}). Carries the resolved content titles
 * alongside ids so callers can render without a second fetch.
 */
public record LearningPathResponse(
        Long pathId,
        Long targetTopicId,
        String status,
        Instant generatedAt,
        Instant startedAt,
        List<LearnerPathStepView> steps) {

    public static LearningPathResponse from(LearnerPath path, Map<ContentId, String> titles) {
        return new LearningPathResponse(
                path.id().map(p -> p.value()).orElse(null),
                path.targetTopicId().value(),
                path.status().name(),
                path.generatedAt(),
                path.startedAt().orElse(null),
                stepsOf(path.steps(), titles));
    }

    public static LearningPathResponse preview(
            LearnerPathDraft draft, Map<ContentId, String> titles) {
        return new LearningPathResponse(
                null,
                draft.targetTopicId().value(),
                "PREVIEW",
                null,
                null,
                stepsOf(draft.steps(), titles));
    }

    private static List<LearnerPathStepView> stepsOf(
            List<LearnerPathStep> steps, Map<ContentId, String> titles) {
        return steps.stream()
                .map(
                        s ->
                                new LearnerPathStepView(
                                        s.stepOrder(),
                                        s.contentId().value(),
                                        titles.getOrDefault(s.contentId(), "(unknown)"),
                                        s.status().name(),
                                        s.addedAsReview(),
                                        s.reasonInPath(),
                                        s.startedAt().orElse(null),
                                        s.completedAt().orElse(null)))
                .toList();
    }
}
