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
 * Verifies that {@code V7__prerequisites.sql} creates
 * {@code plrs_ops.prerequisites} with the expected columns, the
 * composite PK, the no-self-edge CHECK, both ON DELETE CASCADE FKs to
 * {@code content}, the SET NULL FK to {@code users}, and the secondary
 * indexes on {@code prereq_content_id} and {@code added_at}.
 *
 * <p>No trigger is installed on this table by design — cycle detection
 * is application-level (§2.e.2.5). This IT therefore does not assert any
 * trigger behaviour.
 *
 * <p>Traces to: §3.c.1.3 (prerequisites DDL), FR-09 (prerequisite tracking).
 */
@SpringBootTest(
        classes = PrerequisitesTableIT.PrereqITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false"
        })
class PrerequisitesTableIT extends PostgresTestBase {

    private static final String VALID_BCRYPT =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    @Autowired private DataSource dataSource;

    @Test
    void tableExistsWithExpectedColumns() throws Exception {
        Map<String, ColumnSpec> columns = loadColumns();

        assertThat(columns)
                .containsOnlyKeys("content_id", "prereq_content_id", "added_at", "added_by");
        assertThat(columns.get("content_id"))
                .isEqualTo(new ColumnSpec("bigint", "NO", null));
        assertThat(columns.get("prereq_content_id"))
                .isEqualTo(new ColumnSpec("bigint", "NO", null));
        assertThat(columns.get("added_at"))
                .isEqualTo(new ColumnSpec("timestamp with time zone", "NO", null));
        assertThat(columns.get("added_by")).isEqualTo(new ColumnSpec("uuid", "YES", null));
    }

    @Test
    void primaryKeyIsCompositeOnContentAndPrereq() throws Exception {
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
                                + "   AND c.relname = 'prerequisites'"
                                + "   AND i.indisprimary"
                                + " ORDER BY array_position(i.indkey, a.attnum)")) {
            assertThat(rs.next()).as("PK row 1").isTrue();
            assertThat(rs.getString("column_name")).isEqualTo("content_id");
            assertThat(rs.next()).as("PK row 2").isTrue();
            assertThat(rs.getString("column_name")).isEqualTo("prereq_content_id");
            assertThat(rs.next()).as("PK has only two columns").isFalse();
        }
    }

    @Test
    void selfEdgeCheckRejectsSameId() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long contentId = insertContentFixture(conn);

            assertThatThrownBy(() -> insertEdge(conn, contentId, contentId, null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("prereq_no_self");
        }
    }

    @Test
    void duplicateEdgeRejectedByPrimaryKey() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long c1 = insertContentFixture(conn);
            long c2 = insertContentFixture(conn);
            insertEdge(conn, c1, c2, null);

            assertThatThrownBy(() -> insertEdge(conn, c1, c2, null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("duplicate key");
        }
    }

    @Test
    void deletingContentCascadesEdgeRemoval() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long c1 = insertContentFixture(conn);
            long c2 = insertContentFixture(conn);
            insertEdge(conn, c1, c2, null);

            assertThat(countEdgesFor(conn, c1)).isEqualTo(1);

            try (var ps = conn.prepareStatement(
                    "DELETE FROM plrs_ops.content WHERE content_id = ?")) {
                ps.setLong(1, c1);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            assertThat(countEdgesFor(conn, c1)).isZero();
        }
    }

    @Test
    void deletingPrereqContentCascadesEdgeRemoval() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long c1 = insertContentFixture(conn);
            long c2 = insertContentFixture(conn);
            insertEdge(conn, c1, c2, null);

            assertThat(countEdgesFor(conn, c1)).isEqualTo(1);

            try (var ps = conn.prepareStatement(
                    "DELETE FROM plrs_ops.content WHERE content_id = ?")) {
                ps.setLong(1, c2);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            assertThat(countEdgesFor(conn, c1)).isZero();
        }
    }

    @Test
    void deletingUserSetsAddedByToNull() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long c1 = insertContentFixture(conn);
            long c2 = insertContentFixture(conn);
            UUID userId = insertUser(conn);
            insertEdge(conn, c1, c2, userId);

            try (var ps = conn.prepareStatement("DELETE FROM plrs_ops.users WHERE id = ?")) {
                ps.setObject(1, userId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            try (var ps = conn.prepareStatement(
                            "SELECT added_by FROM plrs_ops.prerequisites"
                                    + " WHERE content_id = ? AND prereq_content_id = ?")) {
                ps.setLong(1, c1);
                ps.setLong(2, c2);
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    rs.getObject("added_by");
                    assertThat(rs.wasNull()).isTrue();
                }
            }
        }
    }

    @Test
    void prereqIndexExists() throws Exception {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT indexdef FROM pg_indexes"
                                + " WHERE schemaname = 'plrs_ops'"
                                + "   AND tablename  = 'prerequisites'"
                                + "   AND indexname  = 'idx_prereq_of'")) {
            assertThat(rs.next()).as("idx_prereq_of must exist").isTrue();
            assertThat(rs.getString("indexdef")).contains("prereq_content_id");
        }
    }

    private static long insertContentFixture(Connection conn) throws SQLException {
        long topicId;
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                        + " VALUES (?, 'test') RETURNING topic_id")) {
            ps.setString(1, "prereq-" + UUID.randomUUID());
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
            ps.setString(2, "preq-" + UUID.randomUUID());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("content_id");
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
            ps.setString(2, "prereq-" + id + "@example.com");
            ps.setString(3, VALID_BCRYPT);
            ps.executeUpdate();
        }
        return id;
    }

    private static void insertEdge(Connection conn, long contentId, long prereqId, UUID addedBy)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.prerequisites (content_id, prereq_content_id, added_by)"
                        + " VALUES (?, ?, ?)")) {
            ps.setLong(1, contentId);
            ps.setLong(2, prereqId);
            if (addedBy == null) {
                ps.setNull(3, java.sql.Types.OTHER);
            } else {
                ps.setObject(3, addedBy);
            }
            ps.executeUpdate();
        }
    }

    private static int countEdgesFor(Connection conn, long contentId) throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM plrs_ops.prerequisites"
                        + " WHERE content_id = ? OR prereq_content_id = ?")) {
            ps.setLong(1, contentId);
            ps.setLong(2, contentId);
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
                                + "   AND table_name   = 'prerequisites'"
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
    static class PrereqITApp {}
}
