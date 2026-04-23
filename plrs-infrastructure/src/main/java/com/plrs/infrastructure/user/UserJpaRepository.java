package com.plrs.infrastructure.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link UserJpaEntity}. Package-private API;
 * application code depends on the domain-level
 * {@code com.plrs.domain.user.UserRepository} port, which
 * {@link SpringDataUserRepository} implements in terms of this interface.
 */
public interface UserJpaRepository extends JpaRepository<UserJpaEntity, UUID> {

    Optional<UserJpaEntity> findByEmail(String email);

    boolean existsByEmail(String email);
}
