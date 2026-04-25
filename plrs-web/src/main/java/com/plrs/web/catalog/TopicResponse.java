package com.plrs.web.catalog;

import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicId;

/** Read model for a single topic. */
public record TopicResponse(Long id, String name, String description, Long parentTopicId) {
    public static TopicResponse from(Topic t) {
        return new TopicResponse(
                t.id().value(),
                t.name(),
                t.description().orElse(null),
                t.parentTopicId().map(TopicId::value).orElse(null));
    }
}
