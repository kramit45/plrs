package com.plrs.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.infrastructure.testsupport.PostgresTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * Verifies that {@code V8__interactions.sql} creates
 * {@code plrs_ops.interactions} with the expected columns, the composite
 * PK, all three CHECK constraints, both ON DELETE CASCADE FKs, and the
 * three purpose-specific indexes.
 *
 * <p>Traces to: §3.c.1.4 (interactions DDL + three CHECK constraints),
 * FR-15 / FR-16 / FR-17.
 */
@SpringBootTest(
        classes = InteractionsTableIT.InteractionsITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false"
        })
class InteractionsTableIT extends PostgresTestBase {

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
                        "occurred_at",
                        "event_type",
                        "dwell_sec",
                        "rating",
                        "client_info");
        assertThat(columns.get("user_id")).isEqualTo(new ColumnSpec("uuid", "NO", null));
        assertThat(columns.get("content_id"))
                .isEqualTo(new ColumnSpec("bigint", "NO", null));
        assertThat(columns.get("occurred_at"))
                .isEqualTo(new ColumnSpec("timestamp with time zone", "NO", null));
        assertThat(columns.get("event_type"))
                .isEqualTo(new ColumnSpec("character varying", "NO", 20));
        assertThat(columns.get("dwell_sec")).isEqualTo(new ColumnSpec("integer", "YES", null));
        assertThat(columns.get("rating")).isEqualTo(new ColumnSpec("integer", "YES", null));
        assertThat(columns.get("client_info"))
                .isEqualTo(new ColumnSpec("character varying", "YES", 200));
    }

    @Test
    void compositePrimaryKeyIsUserContentOccurred() throws Exception {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT a.attname AS column_name"
                                + "  FROM pg_index i"
                                + "  JOIN pg_class c    ON c.oid = i.indrelid"
                                + "  JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + "  JOIN pg_attribute a"
                                + "    ON a.attrelid = c.oid"
                                + "   AND a.attnum   = ANY(i.indkey)"
                                + " WHERE n.nspname = 'plrs_ops'"
                                + "   AND c.relname = 'interactions'"
                                + "   AND i.indisprimary"
                                + " ORDER BY array_position(i.indkey, a.attnum)")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("column_name")).isEqualTo("user_id");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("column_name")).isEqualTo("content_id");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("column_name")).isEqualTo("occurred_at");
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void allThreeCheckConstraintsPresentByName() throws Exception {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT conname FROM pg_constraint c"
                                + "  JOIN pg_class t ON t.oid = c.conrelid"
                                + "  JOIN pg_namespace n ON n.oid = t.relnamespace"
                                + " WHERE n.nspname = 'plrs_ops'"
                                + "   AND t.relname = 'interactions'"
                                + "   AND c.contype = 'c'")) {
            java.util.Set<String> names = new java.util.HashSet<>();
            while (rs.next()) {
                names.add(rs.getString("conname"));
            }
            assertThat(names)
                    .contains(
                            "interactions_type_enum",
                            "interactions_rating_iff_rate",
                            "interactions_dwell_only_vc");
        }
    }

    @Test
    void allThreeIndexesExist() throws Exception {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT indexname FROM pg_indexes"
                                + " WHERE schemaname = 'plrs_ops'"
                                + "   AND tablename  = 'interactions'")) {
            java.util.Set<String> names = new java.util.HashSet<>();
            while (rs.next()) {
                names.add(rs.getString("indexname"));
            }
            assertThat(names)
                    .contains(
                            "idx_interactions_user_recent",
                            "idx_interactions_content",
                            "idx_interactions_occurred");
        }
    }

    @Test
    void insertValidViewWithDwellSucceeds() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            long contentId = insertContent(conn);

            insertEvent(conn, userId, contentId, Instant.now(), "VIEW", 30, null);
        }
    }

    @Test
    void insertViewWithNegativeDwellFails() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            long contentId = insertContent(conn);

            assertThatThrownBy(
                            () ->
                                    insertEvent(
                                            conn, userId, contentId, Instant.now(), "VIEW", -1, null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("interactions_dwell_only_vc");
        }
    }

    @Test
    void insertBookmarkWithDwellFails() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            long contentId = insertContent(conn);

            assertThatThrownBy(
                            () ->
                                    insertEvent(
                                            conn,
                                            userId,
                                            contentId,
                                            Instant.now(),
                                            "BOOKMARK",
                                            10,
                                            null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("interactions_dwell_only_vc");
        }
    }

    @Test
    void insertRateWithValidRatingSucceeds() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            long contentId = insertContent(conn);

            insertEvent(conn, userId, contentId, Instant.now(), "RATE", null, 3);
        }
    }

    @Test
    void insertRateWithoutRatingFails() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            long contentId = insertContent(conn);

            assertThatThrownBy(
                            () ->
                                    insertEvent(
                                            conn, userId, contentId, Instant.now(), "RATE", null, null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("interactions_rating_iff_rate");
        }
    }

    @Test
    void insertRateWithRatingOutOfRangeFails() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            long contentId = insertContent(conn);

            assertThatThrownBy(
                            () ->
                                    insertEvent(
                                            conn, userId, contentId, Instant.now(), "RATE", null, 6))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("interactions_rating_iff_rate");
        }
    }

    @Test
    void insertNonRateWithRatingFails() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            long contentId = insertContent(conn);

            assertThatThrownBy(
                            () ->
                                    insertEvent(
                                            conn, userId, contentId, Instant.now(), "VIEW", null, 3))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("interactions_rating_iff_rate");
        }
    }

    @Test
    void insertUnknownEventTypeFails() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            long contentId = insertContent(conn);

            assertThatThrownBy(
                            () ->
                                    insertEvent(
                                            conn,
                                            userId,
                                            contentId,
                                            Instant.now(),
                                            "SHARE",
                                            null,
                                            null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("interactions_type_enum");
        }
    }

    @Test
    void cascadeDeleteOnUserRemovesEvents() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            long contentId = insertContent(conn);
            Instant t0 = Instant.parse("2026-04-25T10:00:00Z");
            insertEvent(conn, userId, contentId, t0, "VIEW", 5, null);
            insertEvent(conn, userId, contentId, t0.plusSeconds(1), "BOOKMARK", null, null);

            assertThat(countEventsForUser(conn, userId)).isEqualTo(2);

            try (var ps = conn.prepareStatement("DELETE FROM plrs_ops.users WHERE id = ?")) {
                ps.setObject(1, userId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            assertThat(countEventsForUser(conn, userId)).isZero();
        }
    }

    @Test
    void cascadeDeleteOnContentRemovesEvents() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            long contentId = insertContent(conn);
            Instant t0 = Instant.parse("2026-04-25T11:00:00Z");
            insertEvent(conn, userId, contentId, t0, "VIEW", 5, null);

            try (var ps = conn.prepareStatement(
                    "DELETE FROM plrs_ops.content WHERE content_id = ?")) {
                ps.setLong(1, contentId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            assertThat(countEventsForUser(conn, userId)).isZero();
        }
    }

    @Test
    void compositePrimaryKeyRejectsDuplicate() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            long contentId = insertContent(conn);
            Instant t0 = Instant.parse("2026-04-25T12:00:00Z");
            insertEvent(conn, userId, contentId, t0, "VIEW", 5, null);

            assertThatThrownBy(() -> insertEvent(conn, userId, contentId, t0, "VIEW", 10, null))
                    .isInstanceOf(SQLException.class)
                    .satisfies(
                            e ->
                                    assertThat(e.getMessage())
                                            .containsIgnoringCase("duplicate key"));
        }
    }

    private static UUID insertUser(Connection conn) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.users"
                        + " (id, email, password_hash, created_at, updated_at, created_by)"
                        + " VALUES (?, ?, ?, NOW(), NOW(), 'test')")) {
            ps.setObject(1, id);
            ps.setString(2, "interaction-" + id + "@example.com");
            ps.setString(3, VALID_BCRYPT);
            ps.executeUpdate();
        }
        return id;
    }

    private static long insertContent(Connection conn) throws SQLException {
        long topicId;
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                        + " VALUES (?, 'test') RETURNING topic_id")) {
            ps.setString(1, "interactions-topic-" + UUID.randomUUID());
            try (var rs = ps.executeQuery()) {
                rs.next();
                topicId = rs.getLong("topic_id");
            }
        }
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.content"
                        + " (topic_id, title, ctype, difficulty, est_minutes, url)"
                        + " VALUES (?, ?, 'VIDEO', 'BEGINNER', 10, 'https://x.y')"
                        + " RETURNING content_id")) {
            ps.setLong(1, topicId);
            ps.setString(2, "interactions-content-" + UUID.randomUUID());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("content_id");
            }
        }
    }

    private static void insertEvent(
            Connection conn,
            UUID userId,
            long contentId,
            Instant occurredAt,
            String eventType,
            Integer dwellSec,
            Integer rating)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.interactions"
                        + " (user_id, content_id, occurred_at, event_type, dwell_sec, rating)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, userId);
            ps.setLong(2, contentId);
            ps.setTimestamp(3, Timestamp.from(occurredAt));
            ps.setString(4, eventType);
            if (dwellSec == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, dwellSec);
            }
            if (rating == null) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setInt(6, rating);
            }
            ps.executeUpdate();
        }
    }

    private static int countEventsForUser(Connection conn, UUID userId) throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM plrs_ops.interactions WHERE user_id = ?")) {
            ps.setObject(1, userId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
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
                                + "   AND table_name   = 'interactions'"
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

    private record ColumnSpec(String dataType, String isNullable, Integer maxLength) {}

    @SpringBootApplication(
            exclude = {
                HibernateJpaAutoConfiguration.class,
                JpaRepositoriesAutoConfiguration.class,
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    static class InteractionsITApp {}
}
