package com.plrs.application.recommendation;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * One row of {@code plrs_ops.model_artifacts} as the application
 * layer sees it: opaque {@code byte[]} payload plus its producer
 * metadata. Consumers (CfScorer, CbScorer) reach for
 * {@link #asString()} when the payload is JSON.
 */
public record ArtifactPayload(
        String artifactType,
        String artifactKey,
        byte[] payload,
        String version,
        Instant computedAt) {

    /** UTF-8 string view of the payload — convenient for JSON consumers. */
    public String asString() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    /** Builds a payload from a UTF-8 string. */
    public static ArtifactPayload ofString(
            String artifactType,
            String artifactKey,
            String body,
            String version,
            Instant computedAt) {
        return new ArtifactPayload(
                artifactType,
                artifactKey,
                body.getBytes(StandardCharsets.UTF_8),
                version,
                computedAt);
    }
}
