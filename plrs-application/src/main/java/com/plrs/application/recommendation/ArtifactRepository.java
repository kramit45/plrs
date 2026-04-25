package com.plrs.application.recommendation;

import java.util.Optional;

/**
 * Application port for the {@code model_artifacts} (V15) durable
 * backup of ML artifacts. The hot read path is Redis; this is the
 * cold-start rehydration source if Redis is wiped.
 *
 * <p>{@link #upsert} replaces the existing row keyed on
 * {@code (artifactType, artifactKey)} so the producer can re-publish
 * fresh artifacts without juggling delete-then-insert.
 *
 * <p>Traces to: §3.c.1.5, §3.c.5.3.
 */
public interface ArtifactRepository {

    /** Inserts or replaces the artifact row. */
    void upsert(ArtifactPayload payload);

    /** Loads an artifact by its composite key. */
    Optional<ArtifactPayload> find(String artifactType, String artifactKey);
}
