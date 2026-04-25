package com.plrs.infrastructure.interaction;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.interaction.EventType;
import com.plrs.domain.interaction.InteractionEvent;
import com.plrs.domain.interaction.InteractionRepository;
import com.plrs.domain.interaction.Rating;
import com.plrs.domain.user.UserId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

/**
 * Adapter implementing {@link InteractionRepository} on top of Spring
 * Data JPA. {@link #save(InteractionEvent)} uses
 * {@link EntityManager#persist} (not {@code jpa.save()}) because Spring
 * Data's {@code save()} calls {@code merge()} for composite-PK entities
 * — that would silently update an existing row instead of raising the
 * primary-key constraint violation the application expects on a
 * concurrent retry. Persist always inserts; the composite PK fires on
 * duplicates and the {@code @Repository} advice translates the resulting
 * JPA exception into {@link org.springframework.dao.DataIntegrityViolationException}.
 *
 * <p>Not declared {@code final}: Spring Boot's observation / metrics
 * {@code AbstractAdvisingBeanPostProcessor} CGLIB-subclasses every
 * {@code @Component} bean. Same constraint as the other Spring Data
 * adapters in this module.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")} so
 * the bean is not created when {@code PlrsApplicationTests} runs its
 * no-DB smoke test.
 *
 * <p>Traces to: §3.a (adapter implements domain port), §3.c.1.4
 * (interactions schema), FR-15 (VIEW debounce read).
 */
@Repository
@ConditionalOnProperty(name = "spring.datasource.url")
public class SpringDataInteractionRepository implements InteractionRepository {

    private final InteractionJpaRepository jpa;

    @PersistenceContext private EntityManager em;

    public SpringDataInteractionRepository(InteractionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(InteractionEvent event) {
        InteractionJpaEntity entity =
                new InteractionJpaEntity(
                        event.userId().value(),
                        event.contentId().value(),
                        event.occurredAt(),
                        event.eventType(),
                        event.dwellSec().orElse(null),
                        event.rating().map(Rating::value).orElse(null),
                        event.clientInfo().orElse(null));
        em.persist(entity);
    }

    @Override
    public boolean existsViewSince(UserId userId, ContentId contentId, Instant since) {
        return jpa.existsRecentView(userId.value(), contentId.value(), since);
    }

    @Override
    public List<InteractionEvent> findRecentCompletes(UserId userId, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0, got " + limit);
        }
        if (limit == 0) {
            return List.of();
        }
        return jpa.findRecentCompletes(userId.value(), PageRequest.of(0, limit)).stream()
                .map(SpringDataInteractionRepository::toDomain)
                .toList();
    }

    @Override
    public Map<String, Integer> countByIsoWeekSince(UserId userId, Instant since) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Object[] row : jpa.countByIsoWeekSince(userId.value(), since)) {
            String week = (String) row[0];
            Number n = (Number) row[1];
            out.put(week, n.intValue());
        }
        return out;
    }

    @Override
    public Map<ContentId, Long> countByContentSince(
            Collection<ContentId> candidates, Instant since) {
        if (candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = new ArrayList<>(candidates.size());
        for (ContentId c : candidates) {
            ids.add(c.value());
        }
        Map<ContentId, Long> out = new LinkedHashMap<>();
        for (Object[] row : jpa.countByContentSince(ids, since)) {
            Long contentId = ((Number) row[0]).longValue();
            Long count = ((Number) row[1]).longValue();
            out.put(ContentId.of(contentId), count);
        }
        return out;
    }

    private static InteractionEvent toDomain(InteractionJpaEntity e) {
        if (e.getEventType() != EventType.COMPLETE) {
            throw new IllegalStateException(
                    "findRecentCompletes returned non-COMPLETE event: " + e.getEventType());
        }
        return InteractionEvent.complete(
                UserId.of(e.getUserId()),
                ContentId.of(e.getContentId()),
                e.getOccurredAt(),
                Optional.ofNullable(e.getDwellSec()),
                Optional.ofNullable(e.getClientInfo()));
    }
}
