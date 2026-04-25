package com.plrs.infrastructure.recommendation;

import com.plrs.application.recommendation.ArtifactPayload;
import com.plrs.application.recommendation.ArtifactRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter implementing {@link ArtifactRepository} on top of
 * {@code plrs_ops.model_artifacts} (V15). Native INSERT … ON CONFLICT
 * upsert — no JPA entity for an opaque BYTEA blob.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}.
 *
 * <p>Traces to: §3.c.1.5.
 */
@Repository
@ConditionalOnProperty(name = "spring.datasource.url")
public class SpringDataArtifactRepository implements ArtifactRepository {

    @PersistenceContext private EntityManager em;

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void upsert(ArtifactPayload payload) {
        em.createNativeQuery(
                        "INSERT INTO plrs_ops.model_artifacts"
                                + " (artifact_type, artifact_key, payload, version,"
                                + "  computed_at, size_bytes)"
                                + " VALUES (:type, :key, :payload, :version,"
                                + "         :computedAt, :sizeBytes)"
                                + " ON CONFLICT (artifact_type, artifact_key) DO UPDATE"
                                + " SET payload     = EXCLUDED.payload,"
                                + "     version     = EXCLUDED.version,"
                                + "     computed_at = EXCLUDED.computed_at,"
                                + "     size_bytes  = EXCLUDED.size_bytes")
                .setParameter("type", payload.artifactType())
                .setParameter("key", payload.artifactKey())
                .setParameter("payload", payload.payload())
                .setParameter("version", payload.version())
                .setParameter("computedAt", Timestamp.from(payload.computedAt()))
                .setParameter("sizeBytes", payload.payload().length)
                .executeUpdate();
    }

    @Override
    public Optional<ArtifactPayload> find(String artifactType, String artifactKey) {
        var rows =
                em.createNativeQuery(
                                "SELECT payload, version, computed_at"
                                        + " FROM plrs_ops.model_artifacts"
                                        + " WHERE artifact_type = :type"
                                        + "   AND artifact_key = :key")
                        .setParameter("type", artifactType)
                        .setParameter("key", artifactKey)
                        .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.get(0);
        byte[] payload = (byte[]) row[0];
        String version = (String) row[1];
        // Hibernate 6 returns TIMESTAMPTZ as Instant directly; older
        // dialects returned java.sql.Timestamp. Handle both.
        Instant computedAt;
        Object ts = row[2];
        if (ts instanceof Instant i) {
            computedAt = i;
        } else if (ts instanceof Timestamp t) {
            computedAt = t.toInstant();
        } else {
            throw new IllegalStateException(
                    "Unexpected computed_at type: " + ts.getClass().getName());
        }
        return Optional.of(
                new ArtifactPayload(
                        artifactType, artifactKey, payload, version, computedAt));
    }
}
