package com.plrs.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.infrastructure.testsupport.PostgresTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
 * Verifies that {@code V11__quiz_attempts.sql} creates
 * {@code plrs_ops.quiz_attempts} with the expected columns, the score
 * CHECK, and the cascade FKs to users and content.
 *
 * <p>Traces to: §3.c.1.4 (quiz_attempts), FR-20.
 */
@SpringBootTest(
        classes = QuizAttemptsTableIT.QuizAttemptsITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false"
        })
class QuizAttemptsTableIT extends PostgresTestBase {

    private static final String VALID_BCRYPT =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    @Autowired private DataSource dataSource;

    @Test
    void scoreCheckAcceptsBoundariesAndRejectsOutOfRange() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            long contentId = insertQuizContent(conn);

            insertAttempt(conn, userId, contentId, "0.00");
            insertAttempt(conn, userId, contentId, "100.00");

            assertThatThrownBy(() -> insertAttempt(conn, userId, contentId, "100.01"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("quiz_attempts_score_bounded");
            assertThatThrownBy(() -> insertAttempt(conn, userId, contentId, "-0.01"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("quiz_attempts_score_bounded");
        }
    }

    @Test
    void cascadeDeleteOnUser() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            long contentId = insertQuizContent(conn);
            insertAttempt(conn, userId, contentId, "75.00");

            try (var ps = conn.prepareStatement("DELETE FROM plrs_ops.users WHERE id = ?")) {
                ps.setObject(1, userId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            assertThat(countAttemptsFor(conn, userId)).isZero();
        }
    }

    @Test
    void cascadeDeleteOnContent() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            long contentId = insertQuizContent(conn);
            insertAttempt(conn, userId, contentId, "75.00");

            try (var ps = conn.prepareStatement(
                    "DELETE FROM plrs_ops.content WHERE content_id = ?")) {
                ps.setLong(1, contentId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            assertThat(countAttemptsFor(conn, userId)).isZero();
        }
    }

    @Test
    void perItemJsonRoundTripsViaJsonbOperator() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = insertUser(conn);
            long contentId = insertQuizContent(conn);
            String payload = "{\"per_item\": [], \"topic_weights\": {\"5\": 1.000}}";
            long attemptId = insertAttemptWithJson(conn, userId, contentId, "100.00", payload);

            try (var ps = conn.prepareStatement(
                            "SELECT per_item_json->'topic_weights'->>'5' AS w"
                                    + " FROM plrs_ops.quiz_attempts WHERE attempt_id = ?")) {
                ps.setLong(1, attemptId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getString("w")).isEqualTo("1.000");
                }
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
            ps.setString(2, "qa-" + id + "@example.com");
            ps.setString(3, VALID_BCRYPT);
            ps.executeUpdate();
        }
        return id;
    }

    private static long insertQuizContent(Connection conn) throws SQLException {
        long topicId;
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                        + " VALUES (?, 'test') RETURNING topic_id")) {
            ps.setString(1, "qa-topic-" + UUID.randomUUID());
            try (var rs = ps.executeQuery()) {
                rs.next();
                topicId = rs.getLong("topic_id");
            }
        }
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.content"
                        + " (topic_id, title, ctype, difficulty, est_minutes, url)"
                        + " VALUES (?, ?, 'QUIZ', 'BEGINNER', 10, 'https://x.y')"
                        + " RETURNING content_id")) {
            ps.setLong(1, topicId);
            ps.setString(2, "qa-content-" + UUID.randomUUID());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("content_id");
            }
        }
    }

    private static void insertAttempt(
            Connection conn, UUID userId, long contentId, String score) throws SQLException {
        insertAttemptWithJson(conn, userId, contentId, score, "{\"per_item\":[],\"topic_weights\":{}}");
    }

    private static long insertAttemptWithJson(
            Connection conn, UUID userId, long contentId, String score, String json)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.quiz_attempts"
                        + " (user_id, content_id, score, per_item_json, attempted_at)"
                        + " VALUES (?, ?, CAST(? AS NUMERIC(5,2)),"
                        + "         CAST(? AS JSONB), ?)"
                        + " RETURNING attempt_id")) {
            ps.setObject(1, userId);
            ps.setLong(2, contentId);
            ps.setString(3, score);
            ps.setString(4, json);
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("attempt_id");
            }
        }
    }

    private static int countAttemptsFor(Connection conn, UUID userId) throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM plrs_ops.quiz_attempts WHERE user_id = ?")) {
            ps.setObject(1, userId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @SpringBootApplication(
            exclude = {
                HibernateJpaAutoConfiguration.class,
                JpaRepositoriesAutoConfiguration.class,
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    static class QuizAttemptsITApp {}
}
