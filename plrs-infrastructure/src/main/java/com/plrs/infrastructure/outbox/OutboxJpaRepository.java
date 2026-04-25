package com.plrs.infrastructure.outbox;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA repository for {@link OutboxJpaEntity}.
 * Package-private API; application code depends on the
 * {@code com.plrs.application.outbox.OutboxRepository} port, which
 * {@link SpringDataOutboxRepository} implements in terms of this
 * interface.
 */
public interface OutboxJpaRepository extends JpaRepository<OutboxJpaEntity, Long> {

    @Query(
            "SELECT o FROM OutboxJpaEntity o"
                    + " WHERE o.deliveredAt IS NULL"
                    + " ORDER BY o.createdAt ASC")
    List<OutboxJpaEntity> findUndeliveredTop(Pageable pageable);
}
