package com.plrs.infrastructure.path;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link LearnerPathJpaEntity}.
 * Package-private — application code depends on the
 * {@code com.plrs.domain.path.LearnerPathRepository} port.
 */
public interface LearnerPathJpaRepository extends JpaRepository<LearnerPathJpaEntity, Long> {

    /**
     * Finds the at-most-one active row for a (user, target) pair. The
     * partial unique index guarantees at-most-one; the LIMIT-1 fetch
     * is just defensive.
     */
    Optional<LearnerPathJpaEntity> findFirstByUserIdAndTargetTopicIdAndStatusIn(
            UUID userId, Long targetTopicId, List<String> activeStatuses);

    List<LearnerPathJpaEntity> findByUserIdOrderByGeneratedAtDesc(UUID userId, Pageable pageable);
}
