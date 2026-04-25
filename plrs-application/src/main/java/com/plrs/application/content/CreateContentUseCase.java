package com.plrs.application.content;

import com.plrs.application.topic.TopicNotFoundException;
import com.plrs.domain.common.AuditFields;
import com.plrs.domain.content.ContentDraft;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.content.ContentRepository;
import com.plrs.domain.content.ContentType;
import com.plrs.domain.topic.TopicRepository;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a non-quiz content item under a topic. Validates topic
 * existence, refuses {@link ContentType#QUIZ} (quiz authoring routes
 * through {@code AuthorQuizUseCase} in step 81), pre-checks the
 * {@code (topic_id, title)} uniqueness constraint, and delegates to the
 * repository, which assigns the {@code BIGSERIAL} id.
 *
 * <p>Domain-level validation (URL scheme, est-minutes range, tag
 * length, etc.) fires inside {@link ContentDraft}'s compact constructor
 * and propagates as
 * {@link com.plrs.domain.common.DomainValidationException} /
 * {@link com.plrs.domain.common.DomainInvariantException}.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")} so
 * the bean is not created when {@code PlrsApplicationTests} runs its
 * no-DB smoke test.
 *
 * <p>Traces to: FR-08 (content authoring).
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class CreateContentUseCase {

    private final ContentRepository contentRepository;
    private final TopicRepository topicRepository;
    private final Clock clock;

    public CreateContentUseCase(
            ContentRepository contentRepository,
            TopicRepository topicRepository,
            Clock clock) {
        this.contentRepository = contentRepository;
        this.topicRepository = topicRepository;
        this.clock = clock;
    }

    @Transactional
    @com.plrs.application.audit.Auditable(action = "CONTENT_CREATED", entityType = "content")
    public ContentId handle(CreateContentCommand cmd) {
        if (topicRepository.findById(cmd.topicId()).isEmpty()) {
            throw new TopicNotFoundException(cmd.topicId());
        }
        if (cmd.ctype() == ContentType.QUIZ) {
            throw new IllegalArgumentException(
                    "Use AuthorQuizUseCase for QUIZ ctype (step 81)");
        }
        if (contentRepository.existsByTopicIdAndTitle(cmd.topicId(), cmd.title())) {
            throw new ContentTitleNotUniqueException(cmd.topicId(), cmd.title());
        }
        ContentDraft draft =
                new ContentDraft(
                        cmd.topicId(),
                        cmd.title(),
                        cmd.ctype(),
                        cmd.difficulty(),
                        cmd.estMinutes(),
                        cmd.url(),
                        cmd.description(),
                        cmd.tags(),
                        cmd.createdBy(),
                        AuditFields.initial(cmd.createdByName(), clock));
        return contentRepository.save(draft).id();
    }
}
