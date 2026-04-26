package com.plrs.infrastructure.path;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Package-private JPA repo for the step child rows. */
public interface LearnerPathStepJpaRepository
        extends JpaRepository<LearnerPathStepJpaEntity, PathStepKey> {

    /** Steps for one path, ordered by {@code step_order}. */
    List<LearnerPathStepJpaEntity> findByPathIdOrderByStepOrderAsc(Long pathId);

    /** Bulk delete used by the update path before re-inserting the current step set. */
    void deleteByPathId(Long pathId);
}
