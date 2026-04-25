package com.plrs.infrastructure.quiz;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link QuizAttemptJpaEntity}.
 * Package-private API; application code depends on the domain
 * {@code com.plrs.domain.quiz.QuizAttemptRepository} port, which
 * {@link SpringDataQuizAttemptRepository} implements in terms of this
 * interface.
 */
public interface QuizAttemptJpaRepository extends JpaRepository<QuizAttemptJpaEntity, Long> {

    List<QuizAttemptJpaEntity> findByUserIdOrderByAttemptedAtDesc(UUID userId, Pageable pageable);

    List<QuizAttemptJpaEntity> findByUserIdAndContentIdOrderByAttemptedAtAsc(
            UUID userId, Long contentId);

    boolean existsByUserIdAndContentId(UUID userId, Long contentId);
}
