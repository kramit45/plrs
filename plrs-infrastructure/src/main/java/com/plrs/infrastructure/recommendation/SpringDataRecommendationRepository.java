package com.plrs.infrastructure.recommendation;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.recommendation.Recommendation;
import com.plrs.domain.recommendation.RecommendationRepository;
import com.plrs.domain.user.UserId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

/**
 * Adapter implementing {@link RecommendationRepository} on top of
 * Spring Data JPA.
 *
 * <p>{@code saveAll} uses {@link EntityManager#persist} per row (not
 * {@code jpa.saveAll}) because Spring Data's save calls {@code merge}
 * for composite-PK entities — that would silently update an existing
 * row instead of raising the primary-key violation a re-served slate
 * with a clashing {@code created_at} should produce.
 *
 * <p>{@code recordClick} is a targeted native UPDATE rather than
 * load + save so concurrent clicks on different rows for the same user
 * don't collide on optimistic locking, and the WHERE-clause guard
 * preserves the aggregate's "earliest click wins" idempotency without
 * a read-modify-write round-trip.
 *
 * <p>Not declared {@code final}; gated by
 * {@code @ConditionalOnProperty("spring.datasource.url")}. Same pattern
 * as the other Spring Data adapters.
 *
 * <p>Traces to: §3.a, §3.c.1.4, FR-26/27/29.
 */
@Repository
@ConditionalOnProperty(name = "spring.datasource.url")
public class SpringDataRecommendationRepository implements RecommendationRepository {

    private final RecommendationJpaRepository jpa;
    private final RecommendationMapper mapper;

    @PersistenceContext private EntityManager em;

    public SpringDataRecommendationRepository(
            RecommendationJpaRepository jpa, RecommendationMapper mapper) {
        this.jpa = jpa;
        this.mapper = mapper;
    }

    @Override
    public void saveAll(List<Recommendation> recs) {
        for (Recommendation r : recs) {
            em.persist(mapper.toEntity(r));
        }
    }

    @Override
    public Optional<Recommendation> find(
            UserId userId, ContentId contentId, Instant createdAt) {
        return jpa.findById(new RecommendationKey(userId.value(), contentId.value(), createdAt))
                .map(mapper::toDomain);
    }

    @Override
    public void recordClick(
            UserId userId, ContentId contentId, Instant createdAt, Instant clickedAt) {
        // The WHERE-clause guard ensures we only stamp the click on
        // rows that haven't been clicked yet — earliest click wins,
        // matching Recommendation.recordClick's idempotency.
        em.createNativeQuery(
                        "UPDATE plrs_ops.recommendations"
                                + " SET clicked_at = :clickedAt"
                                + " WHERE user_id = :userId"
                                + "   AND content_id = :contentId"
                                + "   AND created_at = :createdAt"
                                + "   AND clicked_at IS NULL")
                .setParameter("clickedAt", clickedAt)
                .setParameter("userId", userId.value())
                .setParameter("contentId", contentId.value())
                .setParameter("createdAt", createdAt)
                .executeUpdate();
    }

    @Override
    public List<Recommendation> findRecent(UserId userId, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0, got " + limit);
        }
        if (limit == 0) {
            return List.of();
        }
        return jpa.findByUserIdOrderByCreatedAtDesc(userId.value(), PageRequest.of(0, limit))
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}
