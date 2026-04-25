package com.plrs.web.catalog;

import com.plrs.domain.content.Content;
import com.plrs.domain.content.PrerequisiteEdge;
import com.plrs.domain.user.UserId;
import java.util.List;
import java.util.Set;

/** Detail read model for a single content item including its direct prereq graph. */
public record ContentDetailResponse(
        Long id,
        Long topicId,
        String title,
        String ctype,
        String difficulty,
        int estMinutes,
        String url,
        String description,
        Set<String> tags,
        java.util.UUID createdBy,
        List<PrerequisiteSummary> prerequisites,
        List<PrerequisiteSummary> dependents) {

    public static ContentDetailResponse from(
            Content c, List<PrerequisiteEdge> prereqs, List<PrerequisiteEdge> dependents) {
        return new ContentDetailResponse(
                c.id().value(),
                c.topicId().value(),
                c.title(),
                c.ctype().name(),
                c.difficulty().name(),
                c.estMinutes(),
                c.url(),
                c.description().orElse(null),
                c.tags(),
                c.createdBy().map(UserId::value).orElse(null),
                prereqs.stream().map(PrerequisiteSummary::from).toList(),
                dependents.stream().map(PrerequisiteSummary::from).toList());
    }
}
