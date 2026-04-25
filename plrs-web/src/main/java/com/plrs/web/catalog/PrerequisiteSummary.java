package com.plrs.web.catalog;

import com.plrs.domain.content.PrerequisiteEdge;
import com.plrs.domain.user.UserId;
import java.time.Instant;

/** Read-model summary of one prerequisite edge inside a content detail response. */
public record PrerequisiteSummary(
        Long contentId, Long prereqContentId, Instant addedAt, java.util.UUID addedBy) {
    public static PrerequisiteSummary from(PrerequisiteEdge edge) {
        return new PrerequisiteSummary(
                edge.contentId().value(),
                edge.prereqContentId().value(),
                edge.addedAt(),
                edge.addedBy().map(UserId::value).orElse(null));
    }
}
