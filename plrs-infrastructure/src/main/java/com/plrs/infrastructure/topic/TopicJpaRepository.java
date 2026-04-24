package com.plrs.infrastructure.topic;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link TopicJpaEntity}. Package-private
 * API; application code depends on the domain-level
 * {@code com.plrs.domain.topic.TopicRepository} port, which
 * {@link SpringDataTopicRepository} implements in terms of this interface.
 */
public interface TopicJpaRepository extends JpaRepository<TopicJpaEntity, Long> {

    Optional<TopicJpaEntity> findByTopicName(String topicName);

    boolean existsByTopicName(String topicName);

    List<TopicJpaEntity> findByParentTopicId(Long parentTopicId);

    List<TopicJpaEntity> findByParentTopicIdIsNull();
}
