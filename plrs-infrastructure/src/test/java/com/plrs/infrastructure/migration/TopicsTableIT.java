package com.plrs.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.infrastructure.testsupport.PostgresTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * Verifies that {@code V4__topics.sql} creates {@code plrs_ops.topics} with
 * the expected columns, the unique name constraint, the non-blank name
 * CHECK, the self-referential FK with {@code ON DELETE SET NULL}, and the
 * partial index on {@code parent_topic_id}. Also exercises the
 * orphan-promotion path functionally by inserting a parent/child pair and
 * deleting the parent.
 *
 * <p>Uses a nested minimal {@link TopicsITApp} to sidestep the plrs-web
 * reactor cycle (same rationale as {@code FlywayBaselineIT}).
 *
 * <p>Traces to: §3.c.1.3 (topics DDL), §4.a.1.1 (Flyway conventions),
 * FR-07 (topic hierarchy).
 */
@SpringBootTest(
        classes = TopicsTableIT.TopicsITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false"
        })
class TopicsTableIT extends PostgresTestBase {

    @Autowired private DataSource dataSource;

    @Test
    void tableExistsWithExpectedColumns() throws Exception {
        Map<String, ColumnSpec> columns = loadColumns();

        assertThat(columns)
                .containsOnlyKeys(
                        "topic_id",
                        "topic_name",
                        "description",
                        "parent_topic_id",
                        "created_at",
                        "updated_at",
                        "created_by");
        assertThat(columns.get("topic_id"))
                .isEqualTo(new ColumnSpec("bigint", "NO", null));
        assertThat(columns.get("topic_name"))
                .isEqualTo(new ColumnSpec("character varying", "NO", 120));
        assertThat(columns.get("description"))
                .isEqualTo(new ColumnSpec("character varying", "YES", 500));
        assertThat(columns.get("parent_topic_id"))
                .isEqualTo(new ColumnSpec("bigint", "YES", null));
        assertThat(columns.get("created_at"))
                .isEqualTo(new ColumnSpec("timestamp with time zone", "NO", null));
        assertThat(columns.get("updated_at"))
                .isEqualTo(new ColumnSpec("timestamp with time zone", "NO", null));
        assertThat(columns.get("created_by"))
                .isEqualTo(new ColumnSpec("character varying", "NO", 64));
    }

    @Test
    void uniqueConstraintRejectsDuplicateName() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            insertTopic(conn, "UniqueCheck", null, null);

            assertThatThrownBy(() -> insertTopic(conn, "UniqueCheck", null, null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("topics_name_uk");
        }
    }

    @Test
    void checkConstraintRejectsBlankName() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertThatThrownBy(() -> insertTopic(conn, "   ", null, null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("topics_name_nn");
            assertThatThrownBy(() -> insertTopic(conn, "", null, null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("topics_name_nn");
        }
    }

    @Test
    void selfForeignKeyUsesSetNullOnDelete() throws Exception {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT rc.delete_rule, kcu.column_name, ccu.table_name AS ref_table,"
                                + "       ccu.column_name AS ref_column"
                                + "  FROM information_schema.referential_constraints rc"
                                + "  JOIN information_schema.key_column_usage kcu"
                                + "    ON rc.constraint_name   = kcu.constraint_name"
                                + "   AND rc.constraint_schema = kcu.constraint_schema"
                                + "  JOIN information_schema.constraint_column_usage ccu"
                                + "    ON rc.unique_constraint_name   = ccu.constraint_name"
                                + "   AND rc.unique_constraint_schema = ccu.constraint_schema"
                                + " WHERE kcu.table_schema = 'plrs_ops'"
                                + "   AND kcu.table_name   = 'topics'")) {
            assertThat(rs.next())
                    .as("referential constraint row present for topics")
                    .isTrue();
            assertThat(rs.getString("delete_rule")).isEqualTo("SET NULL");
            assertThat(rs.getString("column_name")).isEqualTo("parent_topic_id");
            assertThat(rs.getString("ref_table")).isEqualTo("topics");
            assertThat(rs.getString("ref_column")).isEqualTo("topic_id");
        }
    }

    @Test
    void partialIndexOnParentTopicIdExists() throws Exception {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT indexdef FROM pg_indexes"
                                + " WHERE schemaname = 'plrs_ops'"
                                + "   AND tablename  = 'topics'"
                                + "   AND indexname  = 'idx_topics_parent'")) {
            assertThat(rs.next()).as("idx_topics_parent must exist").isTrue();
            String def = rs.getString("indexdef");
            assertThat(def)
                    .contains("parent_topic_id")
                    .containsIgnoringCase("where")
                    .contains("IS NOT NULL");
        }
    }

    @Test
    void deletingParentTopicSetsChildParentToNull() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long parentId = insertTopic(conn, "Parent", "root subject", null);
            long childId = insertTopic(conn, "Child", "nested subject", parentId);

            try (var ps = conn.prepareStatement(
                    "DELETE FROM plrs_ops.topics WHERE topic_id = ?")) {
                ps.setLong(1, parentId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            try (var ps = conn.prepareStatement(
                            "SELECT parent_topic_id FROM plrs_ops.topics WHERE topic_id = ?")) {
                ps.setLong(1, childId);
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    rs.getLong("parent_topic_id");
                    assertThat(rs.wasNull())
                            .as("child's parent_topic_id should be NULL after parent deletion")
                            .isTrue();
                }
            }
        }
    }

    private static long insertTopic(Connection conn, String name, String description, Long parentId)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.topics"
                        + " (topic_name, description, parent_topic_id, created_by)"
                        + " VALUES (?, ?, ?, 'test')"
                        + " RETURNING topic_id")) {
            ps.setString(1, name);
            ps.setString(2, description);
            if (parentId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, parentId);
            }
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("topic_id");
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
                                + "   AND table_name   = 'topics'"
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
    static class TopicsITApp {}
}
