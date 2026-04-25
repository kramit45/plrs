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
 * Verifies that {@code V12__user_skills.sql} creates user_skills with
 * its bounds CHECKs, adds {@code users.user_skills_version}, and
 * installs the TRG-3 monotonic trigger.
 *
 * <p>Traces to: §3.c.1.4, §3.b.5.3, FR-21.
 */
@SpringBootTest(
        classes = UserSkillsTableIT.UserSkillsITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false"
        })
class UserSkillsTableIT extends PostgresTestBase {

    private static final String VALID_BCRYPT =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    @Autowired private DataSource dataSource;

    @Test
    void tableExistsWithExpectedColumns() throws Exception {
        Map<String, ColumnSpec> columns = loadColumns("user_skills");

        assertThat(columns)
                .containsOnlyKeys(
                        "user_id", "topic_id", "mastery_score", "confidence", "updated_at");
        assertThat(columns.get("user_id")).isEqualTo(new ColumnSpec("uuid", "NO", null));
        assertThat(columns.get("topic_id")).isEqualTo(new ColumnSpec("bigint", "NO", null));
        assertThat(columns.get("mastery_score")).isEqualTo(new ColumnSpec("numeric", "NO", null));
        assertThat(columns.get("confidence")).isEqualTo(new ColumnSpec("numeric", "NO", null));
        assertThat(columns.get("updated_at"))
                .isEqualTo(new ColumnSpec("timestamp with time zone", "NO", null));
    }

    @Test
    void masteryScoreOutOfRangeRejected() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            long topicId = insertTopic(conn);

            assertThatThrownBy(() -> insertSkill(conn, userId, topicId, "1.500", "0.500"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("user_skills_mastery_bounded");
        }
    }

    @Test
    void confidenceOutOfRangeRejected() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            long topicId = insertTopic(conn);

            assertThatThrownBy(() -> insertSkill(conn, userId, topicId, "0.500", "-0.100"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("user_skills_confidence_bounded");
        }
    }

    @Test
    void cascadeDeleteOnUser() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            long topicId = insertTopic(conn);
            insertSkill(conn, userId, topicId, "0.500", "0.100");

            try (var ps = conn.prepareStatement("DELETE FROM plrs_ops.users WHERE id = ?")) {
                ps.setObject(1, userId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            assertThat(countSkillsFor(conn, userId)).isZero();
        }
    }

    @Test
    void usersUserSkillsVersionExistsWithDefaultZero() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);

            try (var ps = conn.prepareStatement(
                            "SELECT user_skills_version FROM plrs_ops.users WHERE id = ?")) {
                ps.setObject(1, userId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getLong("user_skills_version")).isZero();
                }
            }
        }
    }

    @Test
    void updateUserSkillsVersionForwardSucceeds() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);

            try (var ps = conn.prepareStatement(
                            "UPDATE plrs_ops.users SET user_skills_version = 5 WHERE id = ?")) {
                ps.setObject(1, userId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
        }
    }

    @Test
    void trg3RejectsBackwardUpdate() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            try (var ps = conn.prepareStatement(
                    "UPDATE plrs_ops.users SET user_skills_version = 5 WHERE id = ?")) {
                ps.setObject(1, userId);
                ps.executeUpdate();
            }

            assertThatThrownBy(
                            () -> {
                                try (var ps = conn.prepareStatement(
                                        "UPDATE plrs_ops.users SET user_skills_version = 4"
                                                + " WHERE id = ?")) {
                                    ps.setObject(1, userId);
                                    ps.executeUpdate();
                                }
                            })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("monotonic");
        }
    }

    @Test
    void trg3AllowsSameValueUpdate() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            try (var ps = conn.prepareStatement(
                    "UPDATE plrs_ops.users SET user_skills_version = 5 WHERE id = ?")) {
                ps.setObject(1, userId);
                ps.executeUpdate();
            }
            // Same value — NOT < OLD — must be allowed.
            try (var ps = conn.prepareStatement(
                    "UPDATE plrs_ops.users SET user_skills_version = 5 WHERE id = ?")) {
                ps.setObject(1, userId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
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
            ps.setString(2, "skill-" + id + "@example.com");
            ps.setString(3, VALID_BCRYPT);
            ps.executeUpdate();
        }
        return id;
    }

    private static long insertTopic(Connection conn) throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                        + " VALUES (?, 'test') RETURNING topic_id")) {
            ps.setString(1, "skill-topic-" + UUID.randomUUID());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("topic_id");
            }
        }
    }

    private static void insertSkill(
            Connection conn, UUID userId, long topicId, String mastery, String confidence)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.user_skills"
                        + " (user_id, topic_id, mastery_score, confidence)"
                        + " VALUES (?, ?, CAST(? AS NUMERIC(4,3)), CAST(? AS NUMERIC(4,3)))")) {
            ps.setObject(1, userId);
            ps.setLong(2, topicId);
            ps.setString(3, mastery);
            ps.setString(4, confidence);
            ps.executeUpdate();
        }
    }

    private static int countSkillsFor(Connection conn, UUID userId) throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM plrs_ops.user_skills WHERE user_id = ?")) {
            ps.setObject(1, userId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
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
    static class UserSkillsITApp {}
}
