package com.plrs.infrastructure.recommendation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly @Scheduled job that recomputes item-item cosine similarity
 * over the last 180 days of interactions and writes per-item top-50
 * neighbour slabs to Redis under {@code sim:item:{contentId}} with a
 * 24h TTL. The Python service in step 127 replaces this — for now it's
 * plain Java with a sparse {@link CooccurrenceMatrix}.
 *
 * <p>Algorithm (§3.c.5.3 A3):
 *
 * <ol>
 *   <li>Pull (user, content, event_type, dwell_sec, rating) rows from
 *       {@code interactions} where {@code occurred_at >= now - 180d}.
 *   <li>Build a sparse user-item weight matrix; weight per event:
 *       VIEW = clamp(dwell/300, 0, 1), COMPLETE = 0.8, BOOKMARK = 0.4,
 *       LIKE = 0.6, RATE = rating/5. Per-cell max (not sum) so a
 *       single repeated event doesn't dominate.
 *   <li>L2-normalise columns. Item-item cosine is then the dot of
 *       two normalised columns.
 *   <li>For each item, take top-50 neighbours with sim &gt; 0.01.
 *   <li>Write neighbour list as JSON to Redis with 24h TTL.
 * </ol>
 *
 * <p>Production-scale guard: if the catalogue exceeds {@link #MAX_ITEMS}
 * the job logs WARN and skips — a real deployment would switch to an
 * approximate-NN library, deferred to Iter 4.
 *
 * <p>Gated by {@code @ConditionalOnProperty(spring.datasource.url)}
 * so the bean only loads when the DataSource is configured (otherwise
 * the no-DB smoke test in {@code plrs-web} can't boot). Tests that
 * want to suppress the auto-trigger override
 * {@code plrs.recommender.itemsim.cron} to a never-firing expression
 * (e.g. {@code 0 0 2 1 1 *}, daily 02:00 on Jan 1 only) and drive
 * {@link #recomputeNow()} explicitly.
 *
 * <p>Traces to: §3.c.5.3 (A3), FR-25.
 */
@Component
@ConditionalOnProperty(name = {"spring.datasource.url", "spring.data.redis.host"})
public class ItemSimilarityJob {

    /** Look-back window for the cosine input. */
    public static final Duration WINDOW = Duration.ofDays(180);

    /** Per-item cap on emitted neighbours. */
    public static final int TOP_N_NEIGHBOURS = 50;

    /** Skip threshold — sims this small aren't worth storing. */
    public static final double MIN_SIMILARITY = 0.01;

    /** Catalogue ceiling; above this we skip and log WARN. */
    public static final int MAX_ITEMS = 500;

    static final String SIM_KEY_PREFIX = "sim:item:";
    static final Duration TTL = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(ItemSimilarityJob.class);

    private final DataSource dataSource;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ItemSimilarityJob(
            DataSource dataSource,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            Clock clock) {
        this.dataSource = dataSource;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Scheduled(cron = "${plrs.recommender.itemsim.cron:0 30 2 * * *}")
    public void recompute() {
        long startMillis = System.currentTimeMillis();
        Instant since = Instant.now(clock).minus(WINDOW);
        CooccurrenceMatrix.Builder builder = CooccurrenceMatrix.builder();

        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.setFetchSize(1000);
            try (ResultSet rs =
                    stmt.executeQuery(
                            "SELECT user_id, content_id, event_type,"
                                    + "       dwell_sec, rating"
                                    + " FROM plrs_ops.interactions"
                                    + " WHERE occurred_at >= '"
                                    + since
                                    + "'")) {
                while (rs.next()) {
                    java.util.UUID uid = (java.util.UUID) rs.getObject("user_id");
                    long cid = rs.getLong("content_id");
                    String type = rs.getString("event_type");
                    Integer dwellSec = (Integer) rs.getObject("dwell_sec");
                    Integer rating = (Integer) rs.getObject("rating");
                    double weight = weightFor(type, dwellSec, rating);
                    if (weight > 0.0) {
                        builder.add(uid, cid, weight);
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("ItemSimilarityJob: SQL error during interaction load", e);
            return;
        }

        CooccurrenceMatrix matrix = builder.build();
        int nItems = matrix.numItems();
        if (nItems == 0) {
            log.info("ItemSimilarityJob: no interactions in window, skipping");
            return;
        }
        if (nItems > MAX_ITEMS) {
            log.warn(
                    "ItemSimilarityJob: catalogue size {} exceeds MAX_ITEMS {} — skipping;"
                            + " production needs an approximate-NN library (deferred Iter 4).",
                    nItems,
                    MAX_ITEMS);
            return;
        }

        matrix.l2NormaliseColumns();
        double[][] sim = matrix.itemItemCosine();
        List<Long> ids = matrix.contentIds();

        int totalNeighboursWritten = 0;
        for (int i = 0; i < nItems; i++) {
            List<SimNeighbour> neighbours = topNeighbours(i, sim, ids);
            try {
                String json = objectMapper.writeValueAsString(neighbours);
                redis.opsForValue().set(SIM_KEY_PREFIX + ids.get(i), json, TTL);
                totalNeighboursWritten += neighbours.size();
            } catch (JsonProcessingException e) {
                log.warn(
                        "ItemSimilarityJob: failed to serialise sim slab for item {}",
                        ids.get(i),
                        e);
            } catch (Exception e) {
                log.warn(
                        "ItemSimilarityJob: Redis write failed for item {}", ids.get(i), e);
            }
        }

        long elapsed = System.currentTimeMillis() - startMillis;
        log.info(
                "ItemSimilarityJob: processed {} items, wrote {} neighbours, elapsed {}ms",
                nItems,
                totalNeighboursWritten,
                elapsed);
    }

    /** Manual trigger for admin paths and tests. */
    public void recomputeNow() {
        recompute();
    }

    private static double weightFor(String eventType, Integer dwellSec, Integer rating) {
        switch (eventType) {
            case "VIEW":
                if (dwellSec == null || dwellSec <= 0) {
                    return 0.0;
                }
                return Math.min(1.0, dwellSec.doubleValue() / 300.0);
            case "COMPLETE":
                return CooccurrenceMatrix.WEIGHT_COMPLETE;
            case "BOOKMARK":
                return CooccurrenceMatrix.WEIGHT_BOOKMARK;
            case "LIKE":
                return CooccurrenceMatrix.WEIGHT_LIKE;
            case "RATE":
                if (rating == null) {
                    return 0.0;
                }
                return Math.max(0.0, Math.min(1.0, rating.doubleValue() / 5.0));
            default:
                return 0.0;
        }
    }

    private static List<SimNeighbour> topNeighbours(int itemIdx, double[][] sim, List<Long> ids) {
        List<SimNeighbour> all = new ArrayList<>();
        for (int j = 0; j < ids.size(); j++) {
            if (j == itemIdx) {
                continue;
            }
            double s = sim[itemIdx][j];
            if (s > MIN_SIMILARITY) {
                all.add(new SimNeighbour(ids.get(j), s));
            }
        }
        all.sort(Comparator.comparingDouble(SimNeighbour::similarity).reversed());
        if (all.size() > TOP_N_NEIGHBOURS) {
            return all.subList(0, TOP_N_NEIGHBOURS);
        }
        return all;
    }
}
