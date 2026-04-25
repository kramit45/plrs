package com.plrs.application.content;

import com.plrs.domain.topic.TopicId;

/**
 * Thrown when a {@link CreateContentUseCase} command would violate the
 * {@code content_topic_title_uk} UNIQUE (topic_id, title) constraint
 * (§3.c.1.3). Surfaced by an explicit pre-check so the caller gets a
 * specific error rather than a generic constraint violation.
 *
 * <p>Web layer translates this to HTTP 409 in step 65+.
 */
public class ContentTitleNotUniqueException extends RuntimeException {

    private final TopicId topicId;
    private final String title;

    public ContentTitleNotUniqueException(TopicId topicId, String title) {
        super(
                "Content title not unique within topic "
                        + (topicId == null ? "null" : topicId.value())
                        + ": "
                        + title);
        this.topicId = topicId;
        this.title = title;
    }

    public TopicId topicId() {
        return topicId;
    }

    public String title() {
        return title;
    }
}
