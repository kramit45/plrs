package com.plrs.infrastructure.lock;

import com.plrs.application.quiz.AdvisoryLockService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Postgres-backed {@link AdvisoryLockService} using
 * {@code pg_advisory_xact_lock(hashtext(:k))}. The lock is
 * transaction-scoped — it releases automatically on commit or
 * rollback, so callers don't need an explicit {@code unlock}.
 *
 * <p>{@code hashtext} hashes the supplied key into the 64-bit space
 * Postgres advisory locks use, sidestepping the manual key-allocation
 * boilerplate.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}
 * so the bean is not created for the no-DB smoke test.
 *
 * <p>Traces to: §3.b.7.2.
 */
@Component
@ConditionalOnProperty(name = "spring.datasource.url")
public class PostgresAdvisoryLockService implements AdvisoryLockService {

    @PersistenceContext private EntityManager em;

    @Override
    public void acquireLock(String key) {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:k))")
                .setParameter("k", key)
                .getSingleResult();
    }
}
