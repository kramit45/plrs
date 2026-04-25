package com.plrs.application.topic;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicDraft;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a new topic. Validates uniqueness of the name and existence of
 * the optional parent before constructing a {@link TopicDraft} and
 * delegating to the repository, which assigns the {@code BIGSERIAL} id.
 *
 * <p>Both checks run before draft construction so the caller gets a
 * specific exception ({@link TopicAlreadyExistsException} /
 * {@link TopicNotFoundException}) rather than a generic constraint
 * violation. Domain-level validation (name length, blank-name rejection)
 * still fires inside {@link TopicDraft}'s compact constructor and
 * propagates as
 * {@link com.plrs.domain.common.DomainValidationException}.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")} so
 * the bean is not created when {@code PlrsApplicationTests} runs its
 * no-DB smoke test.
 *
 * <p>Traces to: FR-07 (topic hierarchy authoring).
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class CreateTopicUseCase {

    private final TopicRepository topicRepository;
    private final Clock clock;

    public CreateTopicUseCase(TopicRepository topicRepository, Clock clock) {
        this.topicRepository = topicRepository;
        this.clock = clock;
    }

    @Transactional
    public TopicId handle(CreateTopicCommand cmd) {
        if (topicRepository.existsByName(cmd.name())) {
            throw new TopicAlreadyExistsException(cmd.name());
        }
        if (cmd.parentTopicId().isPresent()
                && topicRepository.findById(cmd.parentTopicId().get()).isEmpty()) {
            throw new TopicNotFoundException(cmd.parentTopicId().get());
        }
        TopicDraft draft =
                new TopicDraft(
                        cmd.name(),
                        cmd.description(),
                        cmd.parentTopicId(),
                        AuditFields.initial(cmd.createdBy(), clock));
        Topic saved = topicRepository.save(draft);
        return saved.id();
    }
}
