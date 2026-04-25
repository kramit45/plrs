package com.plrs.infrastructure.recommendation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link RecommendationJpaEntity}.
 * Package-private API; application code depends on the
 * {@code com.plrs.domain.recommendation.RecommendationRepository} port,
 * which {@link SpringDataRecommendationRepository} implements in terms
 * of this interface.
 */
public interface RecommendationJpaRepository
        extends JpaRepository<RecommendationJpaEntity, RecommendationKey> {

    /** Most-recent-first lookup of recommendations served to one user. */
    List<RecommendationJpaEntity> findByUserIdOrderByCreatedAtDesc(
            @Param("userId") UUID userId, Pageable pageable);
}
