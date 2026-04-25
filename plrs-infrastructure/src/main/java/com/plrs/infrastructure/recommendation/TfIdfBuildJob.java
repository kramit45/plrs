package com.plrs.infrastructure.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plrs.application.recommendation.ArtifactPayload;
import com.plrs.application.recommendation.ArtifactRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily job that recomputes TF-IDF vectors over each content row's
 * (title + description + tags) text and writes a single combined
 * matrix to Redis ({@code tfidf:matrix}, 24h TTL) and to
 * {@code model_artifacts} (TFIDF / "matrix") as the durable backup.
 *
 * <p>Algorithm (§3.c.5.2 A2):
 *
 * <ol>
 *   <li>Tokenise: lowercase, split on whitespace + punctuation, drop
 *       stopwords, drop tokens shorter than 2 chars.
 *   <li>Compute document frequency per term; vocabulary keeps terms
 *       with {@code 2 <= df <= 0.6 * |docs|}, capped at
 *       {@link #MAX_VOCAB} most-frequent.
 *   <li>Sublinear TF: {@code 1 + log(count)}.
 *   <li>IDF: {@code log((1 + |docs|) / (1 + df)) + 1}.
 *   <li>TF-IDF, then L2-normalise per row so dot products are cosines.
 *   <li>Serialise {@code {vocab: [...], rows: [...]}} as JSON; write
 *       to Redis and {@code model_artifacts}.
 * </ol>
 *
 * <p>Plain Java only — Iter 3 demo scale (~120 items) doesn't justify
 * Lucene. The Python service in step 126 replaces this.
 *
 * <p>Gated by {@code @ConditionalOnProperty} on
 * {@code spring.datasource.url} and {@code spring.data.redis.host}.
 */
@Component
@ConditionalOnProperty(name = {"spring.datasource.url", "spring.data.redis.host"})
public class TfIdfBuildJob {

    /** Max vocabulary size — keeps the JSON payload bounded. */
    public static final int MAX_VOCAB = 10_000;

    /** Drop terms with df above this fraction of |docs|. */
    public static final double MAX_DF_RATIO = 0.6;

    /** Drop terms with df below this absolute count. */
    public static final int MIN_DF = 2;

    static final String REDIS_KEY = TfIdfReader.REDIS_KEY;
    static final Duration TTL = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(TfIdfBuildJob.class);

    /** Small built-in English stopword list. ~50 entries. */
    static final Set<String> STOPWORDS =
            Set.of(
                    "a", "an", "and", "are", "as", "at", "be", "but", "by", "for",
                    "from", "has", "he", "her", "his", "i", "if", "in", "is", "it",
                    "its", "of", "on", "or", "over", "she", "that", "the", "their",
                    "them", "then", "there", "these", "they", "this", "to", "was",
                    "we", "were", "what", "when", "where", "which", "while", "who",
                    "with", "you", "your", "do", "does", "did", "not", "no");

    /** Token pattern: any unicode letter or digit, at least 2 chars. */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{Nd}]{2,}");

    private final DataSource dataSource;
    private final StringRedisTemplate redis;
    private final ArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;
    private final TfIdfReader tfIdfReader;
    private final Clock clock;

    public TfIdfBuildJob(
            DataSource dataSource,
            StringRedisTemplate redis,
            ArtifactRepository artifactRepository,
            ObjectMapper objectMapper,
            TfIdfReader tfIdfReader,
            Clock clock) {
        this.dataSource = dataSource;
        this.redis = redis;
        this.artifactRepository = artifactRepository;
        this.objectMapper = objectMapper;
        this.tfIdfReader = tfIdfReader;
        this.clock = clock;
    }

    @Scheduled(cron = "${plrs.recommender.tfidf.cron:0 0 3 * * *}")
    public void rebuild() {
        long start = System.currentTimeMillis();
        List<DocText> docs = loadDocs();
        if (docs.isEmpty()) {
            log.info("TfIdfBuildJob: no content rows, skipping");
            return;
        }

        // 1. Tokenise + per-doc term counts.
        List<Map<String, Integer>> docTermCounts = new ArrayList<>(docs.size());
        Map<String, Integer> df = new HashMap<>();
        for (DocText d : docs) {
            Map<String, Integer> counts = countTokens(d.text);
            docTermCounts.add(counts);
            for (String term : counts.keySet()) {
                df.merge(term, 1, Integer::sum);
            }
        }

        // 2. Build vocab: 2 <= df <= MAX_DF_RATIO * |docs|, capped at
        //    MAX_VOCAB by descending df.
        int maxDf = (int) Math.floor(MAX_DF_RATIO * docs.size());
        if (maxDf < MIN_DF) {
            maxDf = MIN_DF;
        }
        final int finalMaxDf = maxDf;
        List<String> vocabList =
                df.entrySet().stream()
                        .filter(e -> e.getValue() >= MIN_DF && e.getValue() <= finalMaxDf)
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .limit(MAX_VOCAB)
                        .map(Map.Entry::getKey)
                        .sorted()
                        .toList();
        Map<String, Integer> vocabIndex = new HashMap<>(vocabList.size());
        for (int i = 0; i < vocabList.size(); i++) {
            vocabIndex.put(vocabList.get(i), i);
        }

        // 3+4+5. TF (sublinear) + IDF + L2-normalise.
        int n = docs.size();
        List<Map<String, Object>> rowsJson = new ArrayList<>(n);
        for (int d = 0; d < n; d++) {
            Map<String, Integer> counts = docTermCounts.get(d);
            List<TermWeight> raw = new ArrayList<>();
            double sumSq = 0.0;
            for (var e : counts.entrySet()) {
                Integer idx = vocabIndex.get(e.getKey());
                if (idx == null) {
                    continue;
                }
                double tf = 1.0 + Math.log(e.getValue());
                int dfi = df.get(e.getKey());
                double idf = Math.log((1.0 + n) / (1.0 + dfi)) + 1.0;
                double w = tf * idf;
                raw.add(new TermWeight(idx, w));
                sumSq += w * w;
            }
            // L2-normalise.
            if (sumSq > 0.0) {
                double norm = Math.sqrt(sumSq);
                List<TermWeight> normalised = new ArrayList<>(raw.size());
                for (TermWeight tw : raw) {
                    normalised.add(new TermWeight(tw.idx(), tw.weight() / norm));
                }
                raw = normalised;
            }
            // Sort by index so TfIdfReader's dot-product can use the
            // two-pointer merge fast path.
            raw.sort(Comparator.comparingInt(TermWeight::idx));
            Map<String, Object> rowJson = new LinkedHashMap<>();
            rowJson.put("contentId", docs.get(d).contentId);
            List<Map<String, Object>> termsJson = new ArrayList<>(raw.size());
            for (TermWeight tw : raw) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("idx", tw.idx());
                t.put("weight", tw.weight());
                termsJson.add(t);
            }
            rowJson.put("terms", termsJson);
            rowsJson.add(rowJson);
        }

        // 6. Serialise + write to Redis + artifact.
        Instant computedAt = Instant.now(clock);
        String version = String.valueOf(computedAt.toEpochMilli());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("vocab", vocabList);
        root.put("rows", rowsJson);
        try {
            String json = objectMapper.writeValueAsString(root);
            try {
                redis.opsForValue().set(REDIS_KEY, json, TTL);
            } catch (Exception redisEx) {
                log.warn("TfIdfBuildJob: Redis write failed", redisEx);
            }
            try {
                artifactRepository.upsert(
                        ArtifactPayload.ofString(
                                "TFIDF", "matrix", json, version, computedAt));
            } catch (Exception artEx) {
                log.warn("TfIdfBuildJob: artifact write failed", artEx);
            }
            // Force the in-memory reader to refresh next read so the
            // CB / MMR stages don't see a stale snapshot.
            tfIdfReader.refresh();
        } catch (Exception e) {
            log.warn("TfIdfBuildJob: serialisation failed", e);
            return;
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info(
                "TfIdfBuildJob: {} docs, vocab {}, elapsed {}ms",
                n,
                vocabList.size(),
                elapsed);
    }

    /** Manual trigger for admin paths and tests. */
    public void rebuildNow() {
        rebuild();
    }

    static Map<String, Integer> countTokens(String text) {
        Map<String, Integer> out = new HashMap<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        var matcher = TOKEN_PATTERN.matcher(text.toLowerCase(java.util.Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (STOPWORDS.contains(token)) {
                continue;
            }
            out.merge(token, 1, Integer::sum);
        }
        return out;
    }

    private List<DocText> loadDocs() {
        List<DocText> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.setFetchSize(500);
            try (ResultSet rs =
                    stmt.executeQuery(
                            "SELECT c.content_id,"
                                    + "       c.title || ' ' ||"
                                    + "       coalesce(c.description, '') || ' ' ||"
                                    + "       coalesce(string_agg(t.tag, ' '), '') AS body"
                                    + " FROM plrs_ops.content c"
                                    + " LEFT JOIN plrs_ops.content_tags t"
                                    + "   ON t.content_id = c.content_id"
                                    + " GROUP BY c.content_id, c.title, c.description")) {
                while (rs.next()) {
                    long cid = rs.getLong(1);
                    String body = rs.getString(2);
                    out.add(new DocText(cid, body == null ? "" : body));
                }
            }
        } catch (SQLException e) {
            log.warn("TfIdfBuildJob: SQL error during document load", e);
            return List.of();
        }
        return out;
    }

    private record DocText(long contentId, String text) {}

    /** Helps the test detect which doc has which terms. */
    @SuppressWarnings("unused")
    static Set<String> stopwordsSnapshot() {
        return new HashSet<>(STOPWORDS);
    }
}
