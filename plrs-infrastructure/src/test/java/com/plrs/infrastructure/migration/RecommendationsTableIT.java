package com.plrs.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.infrastructure.testsupport.PostgresTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies that {@code V14__recommendations.sql} creates
 * {@code plrs_ops.recommendations} with the expected columns, every
 * CHECK constraint, the three indexes (including the partial one for
 * clicked rows), and the {@code ON DELETE CASCADE} on both FKs.
 *
 * <p>Traces to: §3.c.1.4, FR-26/27/29.
 */
@SpringBootTest(
        classes = RecommendationsTableIT.RecsITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false"
        })
class RecommendationsTableIT extends PostgresTestBase {

    private static final String VALID_BCRYPT =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    @Autowired private DataSource dataSource;

    @Test
    void tableExistsWithExpectedColumns() throws Exception {
        Map<String, ColumnSpec> columns = loadColumns();

        assertThat(columns)
                .containsOnlyKeys(
                        "user_id",
                        "content_id",
                        "created_at",
                        "score",
                        "rank_position",
                        "reason_text",
                        "model_variant",
                        "clicked_at",
                        "completed_at");
        assertThat(columns.get("user_id")).isEqualTo(new ColumnSpec("uuid", "NO", null));
        assertThat(columns.get("content_id")).isEqualTo(new ColumnSpec("bigint", "NO", null));
        assertThat(columns.get("created_at"))
                .isEqualTo(new ColumnSpec("timestamp with time zone", "NO", null));
        assertThat(columns.get("score")).isEqualTo(new ColumnSpec("numeric", "NO", null));
        assertThat(columns.get("rank_position"))
                .isEqualTo(new ColumnSpec("smallint", "NO", null));
        assertThat(columns.get("reason_text"))
                .isEqualTo(new ColumnSpec("character varying", "NO", 200));
        assertThat(columns.get("model_variant"))
                .isEqualTo(new ColumnSpec("character varying", "NO", 30));
        assertThat(columns.get("clicked_at"))
                .isEqualTo(new ColumnSpec("timestamp with time zone", "YES", null));
        assertThat(columns.get("completed_at"))
                .isEqualTo(new ColumnSpec("timestamp with time zone", "YES", null));
    }

    @Test
    void allCheckConstraintsPresent() throws Exception {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT conname FROM pg_constraint c"
                                + "  JOIN pg_class t    ON t.oid = c.conrelid"
                                + "  JOIN pg_namespace n ON n.oid = t.relnamespace"
                                + " WHERE n.nspname = 'plrs_ops'"
                                + "   AND t.relname = 'recommendations'"
                                + "   AND c.contype = 'c'")) {
            Set<String> names = new HashSet<>();
            while (rs.next()) {
                names.add(rs.getString("conname"));
            }
            assertThat(names)
                    .contains(
                            "recs_score_bounded",
                            "recs_rank_bounded",
                            "recs_reason_len",
                            "recs_click_after_serve",
                            "recs_complete_implies_clicked",
                            "recs_complete_after_click");
        }
    }

    @Test
    void threeExpectedIndexesPresent() throws Exception {
        Map<String, String> defs = loadIndexDefs();

        assertThat(defs).containsKeys(
                "idx_recs_user_recent", "idx_recs_variant", "idx_recs_clicked");
        assertThat(defs.get("idx_recs_user_recent"))
                .contains("user_id")
                .contains("created_at");
        assertThat(defs.get("idx_recs_variant"))
                .contains("model_variant")
                .contains("created_at");
        // Partial index — should carry a WHERE clause on clicked_at.
        assertThat(defs.get("idx_recs_clicked"))
                .containsIgnoringCase("where")
                .contains("clicked_at IS NOT NULL");
    }

    @Test
    void scoreOutOfRangeRejected() throws Exception {
        Fixture f = seedFixture();
        try (Connection conn = dataSource.getConnection()) {
            assertThatThrownBy(
                            () ->
                                    insertRec(
                                            conn,
                                            f.userId,
                                            f.contentId,
                                            Instant.parse("2026-04-25T10:00:00Z"),
                                            "1.5",
                                            (short) 1,
                                            "ok",
                                            "popularity_v1",
                                            null,
                                            null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("recs_score_bounded");
        }
    }

    @Test
    void rankOutOfRangeRejected() throws Exception {
        Fixture f = seedFixture();
        try (Connection conn = dataSource.getConnection()) {
            assertThatThrownBy(
                            () ->
                                    insertRec(
                                            conn,
                                            f.userId,
                                            f.contentId,
                                            Instant.parse("2026-04-25T10:00:00Z"),
                                            "0.5",
                                            (short) 51,
                                            "ok",
                                            "popularity_v1",
                                            null,
                                            null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("recs_rank_bounded");
        }
    }

    @Test
    void clickedBeforeServedRejected() throws Exception {
        Fixture f = seedFixture();
        Instant served = Instant.parse("2026-04-25T10:00:00Z");
        try (Connection conn = dataSource.getConnection()) {
            assertThatThrownBy(
                            () ->
                                    insertRec(
                                            conn,
                                            f.userId,
                                            f.contentId,
                                            served,
                                            "0.5",
                                            (short) 1,
                                            "ok",
                                            "popularity_v1",
                                            served.minusSeconds(1),
                                            null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("recs_click_after_serve");
        }
    }

    @Test
    void completedWithoutClickedRejected() throws Exception {
        Fixture f = seedFixture();
        Instant served = Instant.parse("2026-04-25T10:00:00Z");
        try (Connection conn = dataSource.getConnection()) {
            assertThatThrownBy(
                            () ->
                                    insertRec(
                                            conn,
                                            f.userId,
                                            f.contentId,
                                            served,
                                            "0.5",
                                            (short) 1,
                                            "ok",
                                            "popularity_v1",
                                            null,
                                            served.plusSeconds(60)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("recs_complete_implies_clicked");
        }
    }

    @Test
    void cascadeDeleteFromUserDropsRecommendations() throws Exception {
        Fixture f = seedFixture();
        Instant served = Instant.parse("2026-04-25T10:00:00Z");

        try (Connection conn = dataSource.getConnection()) {
            insertRec(
                    conn,
                    f.userId,
                    f.contentId,
                    served,
                    "0.5",
                    (short) 1,
                    "ok",
                    "popularity_v1",
                    null,
                    null);

            try (var ps = conn.prepareStatement(
                    "DELETE FROM plrs_ops.users WHERE id = ?")) {
                ps.setObject(1, f.userId);
                ps.executeUpdate();
            }

            try (var ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM plrs_ops.recommendations WHERE user_id = ?")) {
                ps.setObject(1, f.userId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1))
                            .as("ON DELETE CASCADE should remove every recommendation")
                            .isZero();
                }
            }
        }
    }

    @Test
    void modelVariantDefaultsToPopularityV1() throws Exception {
        Fixture f = seedFixture();
        Instant served = Instant.parse("2026-04-25T10:30:00Z");
        try (Connection conn = dataSource.getConnection()) {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.recommendations"
                            + " (user_id, content_id, created_at, score,"
                            + "  rank_position, reason_text)"
                            + " VALUES (?, ?, ?, 0.5, 1, 'ok')")) {
                ps.setObject(1, f.userId);
                ps.setLong(2, f.contentId);
                ps.setTimestamp(3, Timestamp.from(served));
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement(
                    "SELECT model_variant FROM plrs_ops.recommendations"
                            + " WHERE user_id = ? AND content_id = ? AND created_at = ?")) {
                ps.setObject(1, f.userId);
                ps.setLong(2, f.contentId);
                ps.setTimestamp(3, Timestamp.from(served));
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getString("model_variant")).isEqualTo("popularity_v1");
                }
            }
        }
    }

    private Fixture seedFixture() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            UUID uid = UUID.randomUUID();
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.users"
                            + " (id, email, password_hash, created_at, updated_at, created_by)"
                            + " VALUES (?, ?, ?, NOW(), NOW(), 'rec-it')")) {
                ps.setObject(1, uid);
                ps.setString(2, "rec-" + uid + "@example.com");
                ps.setString(3, VALID_BCRYPT);
                ps.executeUpdate();
            }

            long topicId;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                            + " VALUES (?, 'rec-it') RETURNING topic_id")) {
                ps.setString(1, "rec-topic-" + UUID.randomUUID());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    topicId = rs.getLong("topic_id");
                }
            }

            long contentId;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.content"
                            + " (topic_id, title, ctype, difficulty, est_minutes, url)"
                            + " VALUES (?, ?, 'VIDEO', 'BEGINNER', 5, 'https://x.y')"
                            + " RETURNING content_id")) {
                ps.setLong(1, topicId);
                ps.setString(2, "rec-content-" + UUID.randomUUID());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    contentId = rs.getLong("content_id");
                }
            }
            return new Fixture(uid, contentId);
        }
    }

    private static void insertRec(
            Connection conn,
            UUID userId,
            long contentId,
            Instant createdAt,
            String score,
            short rankPosition,
            String reason,
            String modelVariant,
            Instant clickedAt,
            Instant completedAt)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.recommendations"
                        + " (user_id, content_id, created_at, score,"
                        + "  rank_position, reason_text, model_variant,"
                        + "  clicked_at, completed_at)"
                        + " VALUES (?, ?, ?, CAST(? AS NUMERIC(6,4)), ?, ?, ?, ?, ?)")) {
            ps.setObject(1, userId);
            ps.setLong(2, contentId);
            ps.setTimestamp(3, Timestamp.from(createdAt));
            ps.setString(4, score);
            ps.setShort(5, rankPosition);
            ps.setString(6, reason);
            ps.setString(7, modelVariant);
            if (clickedAt == null) {
                ps.setNull(8, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setTimestamp(8, Timestamp.from(clickedAt));
            }
            if (completedAt == null) {
                ps.setNull(9, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setTimestamp(9, Timestamp.from(completedAt));
            }
            ps.executeUpdate();
        }
    }

    private Map<String, ColumnSpec> loadColumns() throws Exception {
        Map<String, ColumnSpec> columns = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT column_name, data_type, is_nullable, character_maximum_length"
                                + "  FROM information_schema.columns"
                                + " WHERE table_schema = 'plrs_ops'"
                                + "   AND table_name   = 'recommendations'"
                                + " ORDER BY ordinal_position")) {
            while (rs.next()) {
                Integer maxLen = (Integer) rs.getObject("character_maximum_length");
                columns.put(
                        rs.getString("column_name"),
                        new ColumnSpec(
                                rs.getString("data_type"), rs.getString("is_nullable"), maxLen));
            }
        }
        return columns;
    }

    private Map<String, String> loadIndexDefs() throws Exception {
        Map<String, String> defs = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT indexname, indexdef FROM pg_indexes"
                                + " WHERE schemaname = 'plrs_ops'"
                                + "   AND tablename  = 'recommendations'")) {
            while (rs.next()) {
                defs.put(rs.getString("indexname"), rs.getString("indexdef"));
            }
        }
        return defs;
    }

    private record Fixture(UUID userId, long contentId) {}

    private record ColumnSpec(String dataType, String isNullable, Integer maxLength) {}

    @SpringBootApplication(
            exclude = {
                HibernateJpaAutoConfiguration.class,
                JpaRepositoriesAutoConfiguration.class,
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    static class RecsITApp {}
}
