package com.plrs.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.infrastructure.testsupport.PostgresTestBase;
import java.sql.Connection;
import java.sql.SQLException;
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
 * Verifies that {@code V18__learner_paths.sql} creates both tables with
 * their full column / constraint / index surface, and exercises the
 * partial unique index that enforces §3.b.4.3 — one active path per
 * (user, target_topic).
 *
 * <p>Traces to: §3.c.1.4, §3.b.4.3, FR-31.
 */
@SpringBootTest(
        classes = LearnerPathsTableIT.PathsITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false"
        })
class LearnerPathsTableIT extends PostgresTestBase {

    private static final String VALID_BCRYPT =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    @Autowired private DataSource dataSource;

    @Test
    void learnerPathsColumnsPresent() throws Exception {
        Map<String, ColumnSpec> columns = loadColumns("learner_paths");

        assertThat(columns)
                .containsOnlyKeys(
                        "path_id",
                        "user_id",
                        "target_topic_id",
                        "status",
                        "generated_at",
                        "started_at",
                        "paused_at",
                        "completed_at",
                        "abandoned_at",
                        "superseded_at",
                        "superseded_by",
                        "mastery_start_snapshot",
                        "mastery_end_snapshot");
        assertThat(columns.get("user_id").dataType()).isEqualTo("uuid");
        assertThat(columns.get("status").dataType()).isEqualTo("character varying");
        assertThat(columns.get("status").maxLength()).isEqualTo(20);
        assertThat(columns.get("mastery_start_snapshot").dataType()).isEqualTo("jsonb");
        assertThat(columns.get("mastery_start_snapshot").isNullable()).isEqualTo("NO");
        assertThat(columns.get("mastery_end_snapshot").isNullable()).isEqualTo("YES");
    }

    @Test
    void learnerPathStepsColumnsPresent() throws Exception {
        Map<String, ColumnSpec> columns = loadColumns("learner_path_steps");

        assertThat(columns)
                .containsOnlyKeys(
                        "path_id",
                        "step_order",
                        "content_id",
                        "step_status",
                        "added_as_review",
                        "reason_in_path",
                        "started_at",
                        "completed_at");
        assertThat(columns.get("reason_in_path").maxLength()).isEqualTo(200);
        assertThat(columns.get("added_as_review").dataType()).isEqualTo("boolean");
    }

    @Test
    void statusEnumCheckPresent() throws Exception {
        assertThat(constraintNames("learner_paths"))
                .contains("learner_paths_status_enum");
        assertThat(constraintNames("learner_path_steps"))
                .contains("steps_status_enum", "steps_order_pos", "steps_reason_len");
    }

    @Test
    void partialUniquePreventsTwoActivePathsForSameUserTarget() throws Exception {
        Fixture f = seedFixture();

        try (Connection conn = dataSource.getConnection()) {
            insertPath(conn, f.userId, f.topicId, "NOT_STARTED");

            assertThatThrownBy(
                            () -> insertPath(conn, f.userId, f.topicId, "IN_PROGRESS"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("learner_paths_one_active");
        }
    }

    @Test
    void terminalAndActiveCoexistForSameUserTarget() throws Exception {
        Fixture f = seedFixture();

        try (Connection conn = dataSource.getConnection()) {
            insertPath(conn, f.userId, f.topicId, "COMPLETED");
            // A subsequent active row is fine because COMPLETED falls outside the
            // partial-unique window.
            insertPath(conn, f.userId, f.topicId, "NOT_STARTED");

            try (var stmt = conn.createStatement();
                    var rs = stmt.executeQuery(
                            "SELECT COUNT(*) FROM plrs_ops.learner_paths"
                                    + " WHERE user_id = '" + f.userId + "'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }
    }

    @Test
    void cascadeDeleteOnPathDropsSteps() throws Exception {
        Fixture f = seedFixture();
        try (Connection conn = dataSource.getConnection()) {
            long pathId = insertPath(conn, f.userId, f.topicId, "NOT_STARTED");
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.learner_path_steps"
                            + " (path_id, step_order, content_id, reason_in_path)"
                            + " VALUES (?, 1, ?, 'r')")) {
                ps.setLong(1, pathId);
                ps.setLong(2, f.contentId);
                ps.executeUpdate();
            }

            try (var ps = conn.prepareStatement(
                    "DELETE FROM plrs_ops.learner_paths WHERE path_id = ?")) {
                ps.setLong(1, pathId);
                ps.executeUpdate();
            }

            try (var ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM plrs_ops.learner_path_steps WHERE path_id = ?")) {
                ps.setLong(1, pathId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
            }
        }
    }

    private long insertPath(Connection conn, UUID userId, long topicId, String status)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.learner_paths"
                        + " (user_id, target_topic_id, status, mastery_start_snapshot)"
                        + " VALUES (?, ?, ?, '{}'::jsonb) RETURNING path_id")) {
            ps.setObject(1, userId);
            ps.setLong(2, topicId);
            ps.setString(3, status);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("path_id");
            }
        }
    }

    private Fixture seedFixture() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            UUID uid = UUID.randomUUID();
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.users"
                            + " (id, email, password_hash, created_at, updated_at, created_by)"
                            + " VALUES (?, ?, ?, NOW(), NOW(), 'paths-it')")) {
                ps.setObject(1, uid);
                ps.setString(2, "paths-" + uid + "@example.com");
                ps.setString(3, VALID_BCRYPT);
                ps.executeUpdate();
            }
            long topicId;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                            + " VALUES (?, 'paths-it') RETURNING topic_id")) {
                ps.setString(1, "paths-topic-" + UUID.randomUUID());
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
                ps.setString(2, "paths-content-" + UUID.randomUUID());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    contentId = rs.getLong("content_id");
                }
            }
            return new Fixture(uid, topicId, contentId);
        }
    }

    private Set<String> constraintNames(String table) throws Exception {
        Set<String> names = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT conname FROM pg_constraint c"
                                + "  JOIN pg_class t    ON t.oid = c.conrelid"
                                + "  JOIN pg_namespace n ON n.oid = t.relnamespace"
                                + " WHERE n.nspname = 'plrs_ops'"
                                + "   AND t.relname = '" + table + "'"
                                + "   AND c.contype = 'c'")) {
            while (rs.next()) {
                names.add(rs.getString("conname"));
            }
        }
        return names;
    }

    private Map<String, ColumnSpec> loadColumns(String table) throws Exception {
        Map<String, ColumnSpec> columns = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT column_name, data_type, is_nullable, character_maximum_length"
                                + "  FROM information_schema.columns"
                                + " WHERE table_schema = 'plrs_ops'"
                                + "   AND table_name   = '" + table + "'"
                                + " ORDER BY ordinal_position")) {
            while (rs.next()) {
                Integer maxLen = (Integer) rs.getObject("character_maximum_length");
                columns.put(
                        rs.getString("column_name"),
                        new ColumnSpec(
                                rs.getString("data_type"),
                                rs.getString("is_nullable"),
                                maxLen));
            }
        }
        return columns;
    }

    private record Fixture(UUID userId, long topicId, long contentId) {}

    private record ColumnSpec(String dataType, String isNullable, Integer maxLength) {}

    @SpringBootApplication(
            exclude = {
                HibernateJpaAutoConfiguration.class,
                JpaRepositoriesAutoConfiguration.class,
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    static class PathsITApp {}
}
