package com.plrs.infrastructure.warehouse;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Lazy upserter for the {@code plrs_dw.dim_*} surrogate-key tables.
 * The ETL worker is the canonical loader of the dim tables, but for
 * Iter 4 the recommender's serve path also needs to be able to write
 * {@code fact_recommendation} rows immediately so the FR-36 KPI views
 * have data to read. Each {@code ensure*Sk} method does an
 * {@code INSERT ... ON CONFLICT (natural_key) DO UPDATE SET … = EXCLUDED.…
 * RETURNING surrogate_sk} so that:
 *
 * <ul>
 *   <li>RETURNING fires whether the row was inserted or already present
 *       (DO NOTHING would skip RETURNING on conflict);
 *   <li>the "update" sets the natural key to itself, so concurrent ETL
 *       writes that fill in optional columns are not stomped.
 * </ul>
 *
 * <p>{@link #dateSk(Instant)} formats the instant as {@code YYYYMMDD}
 * (UTC), matching the V16 dim_date seed.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}.
 *
 * <p>Traces to: §3.c.2 warehouse, FR-36.
 */
@Component
@ConditionalOnProperty(name = "spring.datasource.url")
public class DimensionResolver {

    private static final DateTimeFormatter DATE_SK_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;

    public DimensionResolver(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public Long ensureUserSk(UserId userId) {
        return jdbc.queryForObject(
                "INSERT INTO plrs_dw.dim_user (user_id) VALUES (?)"
                        + " ON CONFLICT (user_id) DO UPDATE SET user_id = EXCLUDED.user_id"
                        + " RETURNING user_sk",
                Long.class,
                userId.value());
    }

    public Long ensureContentSk(ContentId contentId, Long topicId, String title) {
        return jdbc.queryForObject(
                "INSERT INTO plrs_dw.dim_content (content_id, title, topic_id)"
                        + " VALUES (?, ?, ?)"
                        + " ON CONFLICT (content_id) DO UPDATE SET content_id = EXCLUDED.content_id"
                        + " RETURNING content_sk",
                Long.class,
                contentId.value(),
                title,
                topicId);
    }

    public Long ensureTopicSk(TopicId topicId, String topicName) {
        return jdbc.queryForObject(
                "INSERT INTO plrs_dw.dim_topic (topic_id, topic_name) VALUES (?, ?)"
                        + " ON CONFLICT (topic_id) DO UPDATE SET topic_id = EXCLUDED.topic_id"
                        + " RETURNING topic_sk",
                Long.class,
                topicId.value(),
                topicName);
    }

    public int dateSk(Instant instant) {
        return Integer.parseInt(DATE_SK_FMT.format(instant));
    }
}
