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
 * Verifies that {@code V5__content.sql} creates {@code plrs_ops.content_tags}
 * with the expected composite PK, the non-blank CHECK on {@code tag}, and
 * the ON DELETE CASCADE FK to {@code content(content_id)} that keeps the
 * join table consistent with content deletion.
 *
 * <p>Traces to: §3.c.1.3 (content_tags), FR-08 (content catalogue).
 */
@SpringBootTest(
        classes = ContentTagsTableIT.ContentTagsITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false"
        })
class ContentTagsTableIT extends PostgresTestBase {

    @Autowired private DataSource dataSource;

    @Test
    void tableExistsWithExpectedColumns() throws Exception {
        Map<String, ColumnSpec> columns = loadColumns();

        assertThat(columns).containsOnlyKeys("content_id", "tag");
        assertThat(columns.get("content_id"))
                .isEqualTo(new ColumnSpec("bigint", "NO", null));
        assertThat(columns.get("tag"))
                .isEqualTo(new ColumnSpec("character varying", "NO", 60));
    }

    @Test
    void tagCheckRejectsBlank() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long contentId = insertContentFixture(conn);

            assertThatThrownBy(() -> insertTag(conn, contentId, "   "))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("tag_nn");
            assertThatThrownBy(() -> insertTag(conn, contentId, ""))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("tag_nn");
        }
    }

    @Test
    void primaryKeyRejectsDuplicate() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long contentId = insertContentFixture(conn);
            insertTag(conn, contentId, "algebra");

            assertThatThrownBy(() -> insertTag(conn, contentId, "algebra"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(
                            ex ->
                                    assertThat(ex.getMessage())
                                            .containsIgnoringCase("duplicate key"));
        }
    }

    @Test
    void cascadeDeleteRemovesTags() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long contentId = insertContentFixture(conn);
            insertTag(conn, contentId, "algebra");
            insertTag(conn, contentId, "warmup");

            assertThat(countTags(conn, contentId)).isEqualTo(2);

            try (var ps = conn.prepareStatement(
                    "DELETE FROM plrs_ops.content WHERE content_id = ?")) {
                ps.setLong(1, contentId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            assertThat(countTags(conn, contentId)).isZero();
        }
    }

    private static long insertContentFixture(Connection conn) throws SQLException {
        long topicId;
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                        + " VALUES (?, 'test') RETURNING topic_id")) {
            ps.setString(1, "tags-" + UUID.randomUUID());
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
            ps.setString(2, "tagfix-" + UUID.randomUUID());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("content_id");
            }
        }
    }

    private static void insertTag(Connection conn, long contentId, String tag) throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.content_tags (content_id, tag) VALUES (?, ?)")) {
            ps.setLong(1, contentId);
            ps.setString(2, tag);
            ps.executeUpdate();
        }
    }

    private static int countTags(Connection conn, long contentId) throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM plrs_ops.content_tags WHERE content_id = ?")) {
            ps.setLong(1, contentId);
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
                                + "   AND table_name   = 'content_tags'"
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
    static class ContentTagsITApp {}
}
