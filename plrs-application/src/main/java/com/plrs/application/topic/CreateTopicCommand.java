package com.plrs.application.topic;

import com.plrs.domain.topic.TopicId;
import java.util.Optional;

/**
 * Command for {@link CreateTopicUseCase}: create a topic with the given
 * name and optional parent. {@code createdBy} is the audit actor label
 * (e.g. {@code "admin-ui"}, {@code "system"}); the use case stamps it
 * onto the audit trio at save time.
 */
public record CreateTopicCommand(
        String name, String description, Optional<TopicId> parentTopicId, String createdBy) {}
