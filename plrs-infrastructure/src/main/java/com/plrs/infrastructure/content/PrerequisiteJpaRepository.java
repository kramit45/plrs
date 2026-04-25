package com.plrs.infrastructure.content;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link PrerequisiteJpaEntity}. The
 * recursive-CTE cycle walk lives on the adapter
 * ({@link SpringDataPrerequisiteRepository}) using {@code JdbcTemplate}
 * because Spring Data's {@code @Query} has no first-class projection for
 * a Postgres {@code BIGINT[]} column.
 */
public interface PrerequisiteJpaRepository
        extends JpaRepository<PrerequisiteJpaEntity, PrerequisiteEdgeId> {

    List<PrerequisiteJpaEntity> findByContentId(Long contentId);

    List<PrerequisiteJpaEntity> findByPrereqContentId(Long prereqContentId);

    boolean existsByContentIdAndPrereqContentId(Long contentId, Long prereqContentId);
}
