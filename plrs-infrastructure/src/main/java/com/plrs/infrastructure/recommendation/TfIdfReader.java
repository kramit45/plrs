package com.plrs.infrastructure.recommendation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plrs.application.recommendation.ArtifactPayload;
import com.plrs.application.recommendation.ArtifactRepository;
import com.plrs.domain.content.ContentId;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the TF-IDF matrix produced by {@link TfIdfBuildJob} (key
 * {@code tfidf:matrix} in Redis, {@code TFIDF/"matrix"} in
 * {@code model_artifacts}) and exposes per-row lookups and pairwise
 * cosine to the recommender's CB / MMR stages (steps 118 and 120).
 *
 * <p>Snapshot model: the reader keeps the latest decoded matrix in an
 * {@link AtomicReference}. The first call after JVM start reaches Redis;
 * subsequent calls are pure in-memory. {@link #refresh()} forces a
 * re-fetch (admin path, or when the build job runs).
 *
 * <p>Best-effort: any Redis or artifact failure logs WARN and falls
 * through to an empty snapshot — callers receive {@link List#of()} for
 * unknown rows and {@code 0.0} for cosine.
 *
 * <p>Gated by {@code @ConditionalOnProperty(spring.data.redis.host)}.
 */
@Component
@ConditionalOnProperty(name = {"spring.datasource.url", "spring.data.redis.host"})
public class TfIdfReader {

    static final String REDIS_KEY = "tfidf:matrix";

    private static final Logger log = LoggerFactory.getLogger(TfIdfReader.class);
    private static final TypeReference<Map<String, Object>> ROOT_TYPE =
            new TypeReference<>() {};

    private final StringRedisTemplate redis;
    private final ArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;
    private final AtomicReference<Snapshot> snapshotRef = new AtomicReference<>(Snapshot.EMPTY);

    public TfIdfReader(
            StringRedisTemplate redis,
            ArtifactRepository artifactRepository,
            ObjectMapper objectMapper) {
        this.redis = redis;
        this.artifactRepository = artifactRepository;
        this.objectMapper = objectMapper;
    }

    /** Returns the sparse row for {@code contentId}, or empty if unknown. */
    public List<TermWeight> getRow(ContentId contentId) {
        Snapshot s = ensureLoaded();
        List<TermWeight> row = s.rows.get(contentId.value());
        return row == null ? List.of() : row;
    }

    /**
     * Cosine similarity between two content rows. Returns 0 when
     * either row is unknown.
     */
    public double cosine(ContentId a, ContentId b) {
        if (a.equals(b)) {
            // Self-similarity is always 1 for a non-empty row.
            return getRow(a).isEmpty() ? 0.0 : 1.0;
        }
        Snapshot s = ensureLoaded();
        List<TermWeight> rowA = s.rows.get(a.value());
        List<TermWeight> rowB = s.rows.get(b.value());
        if (rowA == null || rowB == null) {
            return 0.0;
        }
        // Both rows are L2-normalised by the build job, so the dot
        // product IS the cosine.
        return dot(rowA, rowB);
    }

    /** Forces a refresh from Redis on next access. */
    public void refresh() {
        snapshotRef.set(Snapshot.EMPTY);
    }

    private Snapshot ensureLoaded() {
        Snapshot current = snapshotRef.get();
        if (current.loaded) {
            return current;
        }
        Snapshot fresh = loadFromBackingStore();
        // CAS so concurrent first-readers don't double-load.
        snapshotRef.compareAndSet(current, fresh);
        return snapshotRef.get();
    }

    @SuppressWarnings("unchecked")
    private Snapshot loadFromBackingStore() {
        try {
            String json = redis.opsForValue().get(REDIS_KEY);
            if (json == null) {
                Optional<ArtifactPayload> backup =
                        artifactRepository.find("TFIDF", "matrix");
                if (backup.isEmpty()) {
                    return new Snapshot(true, Map.of());
                }
                json = backup.get().asString();
                // Warm Redis so the next read is hot.
                try {
                    redis.opsForValue().set(REDIS_KEY, json, Duration.ofHours(24));
                } catch (Exception warmEx) {
                    log.warn("TfIdfReader: failed to warm Redis", warmEx);
                }
            }
            Map<String, Object> root = objectMapper.readValue(json, ROOT_TYPE);
            List<Map<String, Object>> rawRows =
                    (List<Map<String, Object>>) root.getOrDefault("rows", List.of());
            Map<Long, List<TermWeight>> rows = new HashMap<>(rawRows.size());
            for (Map<String, Object> r : rawRows) {
                long contentId = ((Number) r.get("contentId")).longValue();
                List<Map<String, Object>> rawTerms =
                        (List<Map<String, Object>>) r.getOrDefault("terms", List.of());
                List<TermWeight> terms = new java.util.ArrayList<>(rawTerms.size());
                for (Map<String, Object> tw : rawTerms) {
                    int idx = ((Number) tw.get("idx")).intValue();
                    double weight = ((Number) tw.get("weight")).doubleValue();
                    terms.add(new TermWeight(idx, weight));
                }
                rows.put(contentId, terms);
            }
            return new Snapshot(true, rows);
        } catch (Exception e) {
            log.warn("TfIdfReader: load failed", e);
            return new Snapshot(true, Map.of());
        }
    }

    /** Dot product of two sparse vectors expressed as sorted (idx, weight) lists. */
    static double dot(List<TermWeight> a, List<TermWeight> b) {
        double sum = 0.0;
        int i = 0, j = 0;
        while (i < a.size() && j < b.size()) {
            TermWeight ai = a.get(i);
            TermWeight bj = b.get(j);
            if (ai.idx() == bj.idx()) {
                sum += ai.weight() * bj.weight();
                i++;
                j++;
            } else if (ai.idx() < bj.idx()) {
                i++;
            } else {
                j++;
            }
        }
        return sum;
    }

    private record Snapshot(boolean loaded, Map<Long, List<TermWeight>> rows) {
        static final Snapshot EMPTY = new Snapshot(false, Map.of());
    }
}
