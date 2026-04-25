package com.plrs.application.topic;

import com.plrs.domain.topic.TopicId;

/**
 * Thrown when a use case looked up a topic by id and the repository
 * returned {@link java.util.Optional#empty()}. Used both by
 * {@link CreateTopicUseCase} (parent-id validation) and by
 * content-side use cases that resolve a topic before saving.
 *
 * <p>Web layer translates this to HTTP 404 in step 65+.
 */
public class TopicNotFoundException extends RuntimeException {

    private final TopicId topicId;

    public TopicNotFoundException(TopicId topicId) {
        super("Topic not found: " + (topicId == null ? "null" : topicId.value()));
        this.topicId = topicId;
    }

    public TopicId topicId() {
        return topicId;
    }
}
