package com.plrs.infrastructure.quiz;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.quiz.QuizAttempt;
import com.plrs.domain.quiz.QuizAttemptRepository;
import com.plrs.domain.user.UserId;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

/**
 * Adapter implementing {@link QuizAttemptRepository} on top of Spring
 * Data JPA. Delegates CRUD and the recent-attempts query to
 * {@link QuizAttemptJpaRepository}; the JSONB {@code per_item_json}
 * round-trip is handled by {@link QuizAttemptMapper}.
 *
 * <p>Not declared {@code final}: Spring Boot's observation /
 * AbstractAdvisingBeanPostProcessor CGLIB-subclasses every
 * {@code @Component}. Same constraint as the other Spring Data adapters.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")} so
 * the bean is not created for the no-DB smoke test.
 *
 * <p>Traces to: §3.c.1.4 (quiz_attempts persistence), FR-20.
 */
@Repository
@ConditionalOnProperty(name = "spring.datasource.url")
public class SpringDataQuizAttemptRepository implements QuizAttemptRepository {

    private final QuizAttemptJpaRepository jpa;
    private final QuizAttemptMapper mapper;

    public SpringDataQuizAttemptRepository(
            QuizAttemptJpaRepository jpa, QuizAttemptMapper mapper) {
        this.jpa = jpa;
        this.mapper = mapper;
    }

    @Override
    public QuizAttempt save(QuizAttempt attempt) {
        QuizAttemptJpaEntity saved = jpa.save(mapper.toEntity(attempt));
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<QuizAttempt> findById(Long attemptId) {
        return jpa.findById(attemptId).map(mapper::toDomain);
    }

    @Override
    public List<QuizAttempt> findRecentByUser(UserId userId, int limit) {
        return jpa
                .findByUserIdOrderByAttemptedAtDesc(
                        userId.value(), PageRequest.of(0, limit))
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public List<QuizAttempt> findByUserAndContent(UserId userId, ContentId contentId) {
        return jpa
                .findByUserIdAndContentIdOrderByAttemptedAtAsc(
                        userId.value(), contentId.value())
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public boolean existsByUserAndContent(UserId userId, ContentId contentId) {
        return jpa.existsByUserIdAndContentId(userId.value(), contentId.value());
    }
}
