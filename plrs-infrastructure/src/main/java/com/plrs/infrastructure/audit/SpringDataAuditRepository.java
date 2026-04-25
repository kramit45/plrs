package com.plrs.infrastructure.audit;

import com.plrs.application.audit.AuditEvent;
import com.plrs.application.audit.AuditRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter implementing {@link AuditRepository} on top of
 * {@code plrs_ops.audit_log} (V13). Uses a native INSERT — no JPA entity
 * is needed because the table is append-only (TRG-4) and there are no
 * read paths, so the entity-manager indirection would only add weight.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}
 * to keep the bean out of the no-DB smoke test.
 */
@Repository
@ConditionalOnProperty(name = "spring.datasource.url")
public class SpringDataAuditRepository implements AuditRepository {

    @PersistenceContext private EntityManager em;

    /**
     * {@code Propagation.REQUIRED} so the append joins the audited
     * method's transaction when one exists (atomic with the business
     * write — TX-01 family) and opens its own otherwise. Without this,
     * audited use cases that aren't themselves {@code @Transactional}
     * (e.g. RegisterUserUseCase, LoginUseCase) hit Hibernate's
     * "Executing an update/delete query" guard.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void append(AuditEvent event) {
        // Use ::jsonb cast for detail_json so the column type is honoured
        // when the value is non-null. NULL values are typed implicitly via
        // the column.
        var query =
                em.createNativeQuery(
                        "INSERT INTO plrs_ops.audit_log"
                                + " (occurred_at, actor_user_id, action, entity_type,"
                                + "  entity_id, detail_json)"
                                + " VALUES (:occurredAt, :actorUserId, :action,"
                                + "         :entityType, :entityId, CAST(:detailJson AS jsonb))");
        query.setParameter("occurredAt", event.occurredAt());
        query.setParameter("actorUserId", event.actorUserId().map(id -> id.value()).orElse(null));
        query.setParameter("action", event.action());
        query.setParameter("entityType", event.entityType().orElse(null));
        query.setParameter("entityId", event.entityId().orElse(null));
        query.setParameter("detailJson", event.detailJson().orElse(null));
        query.executeUpdate();
    }
}
