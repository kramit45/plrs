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

    /**
     * Counts interactions for {@code userId} since {@code since}, grouped
     * by ISO year-week. Native query: Postgres' {@code IYYY} is the ISO
     * 8601 year (week-based) and {@code IW} is the ISO week number
     * (01..53). The pair forms a stable, sortable key the application
     * layer can compare directly to its own zero-fill sequence.
     */
    @Query(
            value =
                    "SELECT to_char(occurred_at, 'IYYY-IW') AS iso_week,"
                            + " COUNT(*) AS n"
                            + " FROM plrs_ops.interactions"
                            + " WHERE user_id = :userId"
                            + "   AND occurred_at >= :since"
                            + " GROUP BY iso_week"
                            + " ORDER BY iso_week ASC",
            nativeQuery = true)
    List<Object[]> countByIsoWeekSince(
            @Param("userId") UUID userId, @Param("since") Instant since);

    /**
     * Counts COMPLETE + LIKE events per content scoped to a candidate
     * set and a since-instant. Returns only content that had at least
     * one matching event; callers zero-fill missing candidates so an
     * empty content set still produces a defensible 0 popularity.
     */
    @Query(
            value =
                    "SELECT content_id, COUNT(*)"
                            + " FROM plrs_ops.interactions"
                            + " WHERE event_type IN ('COMPLETE','LIKE')"
                            + "   AND content_id IN (:contentIds)"
                            + "   AND occurred_at >= :since"
                            + " GROUP BY content_id",
            nativeQuery = true)
    List<Object[]> countByContentSince(
            @Param("contentIds") java.util.Collection<Long> contentIds,
            @Param("since") Instant since);
}
