package com.plrs.web.catalog;

import com.plrs.domain.content.Content;
import java.util.Set;

/** Lightweight read model for one content item inside a search-page response. */
public record ContentSummary(
        Long contentId,
        Long topicId,
        String title,
        String ctype,
        String difficulty,
        int estMinutes,
        Set<String> tags) {

    public static ContentSummary from(Content c) {
        return new ContentSummary(
                c.id().value(),
                c.topicId().value(),
                c.title(),
                c.ctype().name(),
                c.difficulty().name(),
                c.estMinutes(),
                c.tags());
    }
}
