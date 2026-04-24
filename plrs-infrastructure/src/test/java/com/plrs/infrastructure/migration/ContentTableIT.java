package com.plrs.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.infrastructure.testsupport.PostgresTestBase;
import java.sql.Connection;
import java.sql.SQLException;
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
 * Verifies that {@code V5__content.sql} creates {@code plrs_ops.content}
 * with the expected columns, CHECK constraints, indexes (including the
 * GIN full-text index), and foreign keys to {@code topics} (RESTRICT) and
 * {@code users} (SET NULL). Also exercises the constraint-violation paths
 * functionally so that a future schema edit that drops a CHECK fails
 * loudly here.
 *
 * <p>Uses a nested minimal {@link ContentITApp} to sidestep the plrs-web
 * reactor cycle (same rationale as {@code FlywayBaselineIT}).
 *
 * <p>Traces to: §3.c.1.3 (content DDL), §4.a.1.1 (Flyway conventions),
 * FR-08 (content catalogue), FR-13 (keyword search).
 */
@SpringBootTest(
        classes = ContentTableIT.ContentITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false"
        })
class ContentTableIT extends PostgresTestBase {

    private static final String VALID_BCRYPT =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    @Autowired private DataSource dataSource;

    @Test
    void tableExistsWithExpectedColumns() throws Exception {
        Map<String, ColumnSpec> columns = loadColumns("content");

        assertThat(columns)
                .containsOnlyKeys(
                        "content_id",
                        "topic_id",
                        "title",
                        "ctype",
                        "difficulty",
                        "est_minutes",
                        "url",
                        "description",
                        "created_at",
                        "created_by",
                        "updated_at");
        assertThat(columns.get("content_id"))
                .isEqualTo(new ColumnSpec("bigint", "NO", null));
        assertThat(columns.get("topic_id"))
                .isEqualTo(new ColumnSpec("bigint", "NO", null));
        assertThat(columns.get("title"))
                .isEqualTo(new ColumnSpec("character varying", "NO", 200));
        assertThat(columns.get("ctype"))
                .isEqualTo(new ColumnSpec("character varying", "NO", 15));
        assertThat(columns.get("difficulty"))
                .isEqualTo(new ColumnSpec("character varying", "NO", 15));
        assertThat(columns.get("est_minutes"))
                .isEqualTo(new ColumnSpec("integer", "NO", null));
        assertThat(columns.get("url")).isEqualTo(new ColumnSpec("text", "NO", null));
        assertThat(columns.get("description")).isEqualTo(new ColumnSpec("text", "YES", null));
        assertThat(columns.get("created_by")).isEqualTo(new ColumnSpec("uuid", "YES", null));
        assertThat(columns.get("created_at"))
                .isEqualTo(new ColumnSpec("timestamp with time zone", "NO", null));
        assertThat(columns.get("updated_at"))
                .isEqualTo(new ColumnSpec("timestamp with time zone", "NO", null));
    }

    @Test
    void ctypeCheckRejectsUnknownValue() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long topicId = insertTopic(conn, "ctype-unknown-" + UUID.randomUUID());

            assertThatThrownBy(
                            () -> insertContent(
                                    conn,
                                    topicId,
                                    "title-" + UUID.randomUUID(),
                                    "PODCAST",
                                    "BEGINNER",
                                    10,
                                    "https://x.y",
                                    null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("content_ctype_enum");
        }
    }

    @Test
    void difficultyCheckRejectsUnknownValue() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long topicId = insertTopic(conn, "diff-unknown-" + UUID.randomUUID());

            assertThatThrownBy(
                            () -> insertContent(
                                    conn,
                                    topicId,
                                    "title-" + UUID.randomUUID(),
                                    "VIDEO",
                                    "EXPERT",
                                    10,
                                    "https://x.y",
                                    null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("content_difficulty_enum");
        }
    }

    @Test
    void estMinutesCheckRejectsOutOfRange() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long topicId = insertTopic(conn, "est-" + UUID.randomUUID());

            assertThatThrownBy(
                            () -> insertContent(
                                    conn,
                                    topicId,
                                    "title-" + UUID.randomUUID(),
                                    "VIDEO",
                                    "BEGINNER",
                                    0,
                                    "https://x.y",
                                    null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("content_est_bounded");
            assertThatThrownBy(
                            () -> insertContent(
                                    conn,
                                    topicId,
                                    "title-" + UUID.randomUUID(),
                                    "VIDEO",
                                    "BEGINNER",
                                    601,
                                    "https://x.y",
                                    null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("content_est_bounded");
        }
    }

    @Test
    void titleCheckRejectsBlank() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long topicId = insertTopic(conn, "title-" + UUID.randomUUID());

            assertThatThrownBy(
                            () -> insertContent(
                                    conn, topicId, "   ", "VIDEO", "BEGINNER", 10, "https://x.y", null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("content_title_nn");
        }
    }

    @Test
    void urlSchemeCheckRejectsNonHttp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long topicId = insertTopic(conn, "url-" + UUID.randomUUID());

            assertThatThrownBy(
                            () -> insertContent(
                                    conn,
                                    topicId,
                                    "title-" + UUID.randomUUID(),
                                    "VIDEO",
                                    "BEGINNER",
                                    10,
                                    "ftp://x.com",
                                    null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("content_url_scheme");
        }
    }

    @Test
    void uniqueTopicAndTitleRejectsDuplicate() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long topicId = insertTopic(conn, "dup-" + UUID.randomUUID());
            String title = "shared-title-" + UUID.randomUUID();
            insertContent(conn, topicId, title, "VIDEO", "BEGINNER", 10, "https://x.y", null);

            assertThatThrownBy(
                            () -> insertContent(
                                    conn, topicId, title, "ARTICLE", "BEGINNER", 5, "https://x.z", null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("content_topic_title_uk");
        }
    }

    @Test
    void topicForeignKeyRestrictsDeleteWhenContentExists() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long topicId = insertTopic(conn, "restrict-" + UUID.randomUUID());
            insertContent(
                    conn,
                    topicId,
                    "title-" + UUID.randomUUID(),
                    "VIDEO",
                    "BEGINNER",
                    10,
                    "https://x.y",
                    null);

            assertThatThrownBy(
                            () -> {
                                try (var ps = conn.prepareStatement(
                                        "DELETE FROM plrs_ops.topics WHERE topic_id = ?")) {
                                    ps.setLong(1, topicId);
                                    ps.executeUpdate();
                                }
                            })
                    .isInstanceOf(SQLException.class)
                    .satisfies(
                            ex ->
                                    assertThat(ex.getMessage())
                                            .containsIgnoringCase("foreign key")
                                            .containsIgnoringCase("content"));
        }
    }

    @Test
    void createdByForeignKeyNullsOutOnUserDelete() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long topicId = insertTopic(conn, "setnull-" + UUID.randomUUID());
            UUID userId = insertUser(conn);
            long contentId =
                    insertContent(
                            conn,
                            topicId,
                            "title-" + UUID.randomUUID(),
                            "VIDEO",
                            "BEGINNER",
                            10,
                            "https://x.y",
                            userId);

            try (var ps = conn.prepareStatement("DELETE FROM plrs_ops.users WHERE id = ?")) {
                ps.setObject(1, userId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            try (var ps = conn.prepareStatement(
                            "SELECT created_by FROM plrs_ops.content WHERE content_id = ?")) {
                ps.setLong(1, contentId);
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    rs.getObject("created_by");
                    assertThat(rs.wasNull()).isTrue();
                }
            }
        }
    }

    @Test
    void ginSearchIndexExists() throws Exception {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT am.amname, pi.indexdef"
                                + "  FROM pg_indexes pi"
                                + "  JOIN pg_class c ON c.relname = pi.indexname"
                                + "  JOIN pg_am am   ON am.oid    = c.relam"
                                + " WHERE pi.schemaname = 'plrs_ops'"
                                + "   AND pi.tablename  = 'content'"
                                + "   AND pi.indexname  = 'idx_content_search'")) {
            assertThat(rs.next()).as("idx_content_search must exist").isTrue();
            assertThat(rs.getString("amname")).isEqualTo("gin");
            assertThat(rs.getString("indexdef"))
                    .contains("to_tsvector")
                    .contains("english")
                    .containsIgnoringCase("gin");
        }
    }

    private static long insertTopic(Connection conn, String name) throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                        + " VALUES (?, 'test') RETURNING topic_id")) {
            ps.setString(1, name);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("topic_id");
            }
        }
    }

    private static UUID insertUser(Connection conn) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.users"
                        + " (id, email, password_hash, created_at, updated_at, created_by)"
                        + " VALUES (?, ?, ?, NOW(), NOW(), 'test')")) {
            ps.setObject(1, id);
            ps.setString(2, "content-" + id + "@example.com");
            ps.setString(3, VALID_BCRYPT);
            ps.executeUpdate();
        }
        return id;
    }

    private static long insertContent(
            Connection conn,
            long topicId,
            String title,
            String ctype,
            String difficulty,
            int estMinutes,
            String url,
            UUID createdBy)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.content"
                        + " (topic_id, title, ctype, difficulty, est_minutes, url, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING content_id")) {
            ps.setLong(1, topicId);
            ps.setString(2, title);
            ps.setString(3, ctype);
            ps.setString(4, difficulty);
            ps.setInt(5, estMinutes);
            ps.setString(6, url);
            if (createdBy == null) {
                ps.setNull(7, java.sql.Types.OTHER);
            } else {
                ps.setObject(7, createdBy);
            }
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("content_id");
            }
        }
    }

    private Map<String, ColumnSpec> loadColumns(String table) throws Exception {
        Map<String, ColumnSpec> columns = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT column_name, data_type, is_nullable, character_maximum_length"
                                + "  FROM information_schema.columns"
                                + " WHERE table_schema = 'plrs_ops'"
                                + "   AND table_name   = '"
                                + table
                                + "'"
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
    static class ContentITApp {}
}
