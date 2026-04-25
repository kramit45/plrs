package com.plrs.infrastructure.mastery;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link UserSkillJpaEntity}.
 * Package-private API; application code depends on the
 * {@code com.plrs.domain.mastery.UserSkillRepository} port.
 */
public interface UserSkillJpaRepository
        extends JpaRepository<UserSkillJpaEntity, UserSkillKey> {

    List<UserSkillJpaEntity> findByUserId(UUID userId);
}
