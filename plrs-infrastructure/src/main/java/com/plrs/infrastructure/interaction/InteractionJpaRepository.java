package com.plrs.infrastructure.interaction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link InteractionJpaEntity}.
 * Package-private API; application code depends on the domain-level
 * {@code com.plrs.domain.interaction.InteractionRepository} port, which
 * {@link SpringDataInteractionRepository} implements in terms of this
 * interface.
 */
public interface InteractionJpaRepository
        extends JpaRepository<InteractionJpaEntity, InteractionKey> {

    @Query(
            "SELECT COUNT(i) > 0 FROM InteractionJpaEntity i"
                    + " WHERE i.userId = :userId"
                    + "   AND i.contentId = :contentId"
                    + "   AND i.eventType = com.plrs.domain.interaction.EventType.VIEW"
                    + "   AND i.occurredAt > :since")
    boolean existsRecentView(
            @Param("userId") UUID userId,
            @Param("contentId") Long contentId,
            @Param("since") Instant since);

    @Query(
            "SELECT i FROM InteractionJpaEntity i"
                    + " WHERE i.userId = :userId"
                    + "   AND i.eventType = com.plrs.domain.interaction.EventType.COMPLETE"
                    + " ORDER BY i.occurredAt DESC")
    List<InteractionJpaEntity> findRecentCompletes(
            @Param("userId") UUID userId, Pageable pageable);
}
