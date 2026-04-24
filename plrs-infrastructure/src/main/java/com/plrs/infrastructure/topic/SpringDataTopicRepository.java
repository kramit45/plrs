package com.plrs.infrastructure.topic;

import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicDraft;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.topic.TopicRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Adapter implementing the domain-owned
 * {@link com.plrs.domain.topic.TopicRepository} port on top of Spring Data
 * JPA. Delegates every method to {@link TopicJpaRepository} and converts
 * between the domain aggregate and the JPA entity via {@link TopicMapper}.
 *
 * <p>Not declared {@code final}: Spring Boot's observation / metrics
 * {@code AbstractAdvisingBeanPostProcessor} tries to CGLIB-subclass every
 * {@code @Component} bean and blows up on a final class. Same constraint
 * as {@code SpringDataUserRepository}.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")} so the
 * bean is not created when {@code PlrsApplicationTests} runs its no-DB
 * smoke test (it sets the property to {@code false} and excludes JPA
 * auto-configuration). Mirrors the gate on {@code SpringDataUserRepository}.
 *
 * <p>Database uniqueness violations (duplicate topic name races past the
 * application-layer existence check) surface here as
 * {@link org.springframework.dao.DataIntegrityViolationException}. Those
 * are deliberately left unwrapped at this layer; application-side use
 * cases (Theme P) translate them into domain-level outcomes.
 *
 * <p>Traces to: §3.a (adapter implements domain port), §3.c.1.3 (topics
 * persistence).
 */
@Repository
@ConditionalOnProperty(name = "spring.datasource.url")
public class SpringDataTopicRepository implements TopicRepository {

    private final TopicJpaRepository jpa;
    private final TopicMapper mapper;

    public SpringDataTopicRepository(TopicJpaRepository jpa, TopicMapper mapper) {
        this.jpa = jpa;
        this.mapper = mapper;
    }

    @Override
    public Topic save(TopicDraft draft) {
        TopicJpaEntity saved = jpa.save(mapper.toEntity(draft));
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<Topic> findById(TopicId id) {
        return jpa.findById(id.value()).map(mapper::toDomain);
    }

    @Override
    public Optional<Topic> findByName(String name) {
        return jpa.findByTopicName(name).map(mapper::toDomain);
    }

    @Override
    public boolean existsByName(String name) {
        return jpa.existsByTopicName(name);
    }

    @Override
    public List<Topic> findChildrenOf(TopicId parentId) {
        return jpa.findByParentTopicId(parentId.value()).stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<Topic> findRootTopics() {
        return jpa.findByParentTopicIdIsNull().stream().map(mapper::toDomain).toList();
    }
}
