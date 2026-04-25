package com.plrs.infrastructure.mastery;

import com.plrs.domain.mastery.MasteryScore;
import com.plrs.domain.mastery.UserSkill;
import com.plrs.domain.mastery.UserSkillRepository;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Adapter implementing {@link UserSkillRepository} on top of Spring
 * Data JPA. {@code upsert} delegates to {@code jpa.save} which acts as
 * INSERT-OR-UPDATE keyed on the composite PK.
 *
 * <p>Not declared {@code final}; gated by
 * {@code @ConditionalOnProperty("spring.datasource.url")}. Same
 * pattern as the other Spring Data adapters.
 *
 * <p>Traces to: §3.c.1.4, §3.c.5.7, FR-21.
 */
@Repository
@ConditionalOnProperty(name = "spring.datasource.url")
public class SpringDataUserSkillRepository implements UserSkillRepository {

    private final UserSkillJpaRepository jpa;

    public SpringDataUserSkillRepository(UserSkillJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<UserSkill> find(UserId userId, TopicId topicId) {
        return jpa.findById(new UserSkillKey(userId.value(), topicId.value()))
                .map(SpringDataUserSkillRepository::toDomain);
    }

    @Override
    public UserSkill upsert(UserSkill skill) {
        UserSkillJpaEntity saved =
                jpa.save(
                        new UserSkillJpaEntity(
                                skill.userId().value(),
                                skill.topicId().value(),
                                java.math.BigDecimal.valueOf(skill.mastery().value())
                                        .setScale(3, java.math.RoundingMode.HALF_UP),
                                skill.confidence(),
                                skill.updatedAt()));
        return toDomain(saved);
    }

    @Override
    public List<UserSkill> findByUser(UserId userId) {
        return jpa.findByUserId(userId.value()).stream()
                .map(SpringDataUserSkillRepository::toDomain)
                .toList();
    }

    private static UserSkill toDomain(UserSkillJpaEntity entity) {
        return UserSkill.rehydrate(
                UserId.of(entity.getUserId()),
                TopicId.of(entity.getTopicId()),
                MasteryScore.of(entity.getMasteryScore().doubleValue()),
                entity.getConfidence(),
                entity.getUpdatedAt());
    }
}
