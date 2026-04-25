package com.plrs.application.content;

import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.Difficulty;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.util.Optional;
import java.util.Set;

/**
 * Command for {@link CreateContentUseCase}: create a content item under
 * the given topic. {@code createdBy} (Optional UserId) is the authoring
 * user — if absent, the row is system-seeded; {@code createdByName} is
 * the audit actor label that lands on the row's {@code AuditFields}.
 */
public record CreateContentCommand(
        TopicId topicId,
        String title,
        ContentType ctype,
        Difficulty difficulty,
        int estMinutes,
        String url,
        Optional<String> description,
        Set<String> tags,
        Optional<UserId> createdBy,
        String createdByName) {}
