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
 * Verifies that {@code V10__quiz.sql} creates {@code quiz_items} and
 * {@code quiz_item_options} with the expected structure and that the
 * three triggers fire as designed:
 *
 * <ul>
 *   <li>TRG-1 — quiz_items.content_id must reference content with ctype=QUIZ.
 *   <li>Mirror — content.ctype cannot move away from QUIZ while items exist.
 *   <li>TRG-2 — exactly one is_correct=TRUE per item, DEFERRABLE INITIALLY
 *       DEFERRED so two-step swaps within a transaction commit cleanly.
 * </ul>
 *
 * <p>Traces to: §3.c.1.3, §3.b.5.1, §3.b.5.2, FR-19.
 */
@SpringBootTest(
        classes = QuizTablesIT.QuizITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false"
        })
class QuizTablesIT extends PostgresTestBase {

    @Autowired private DataSource dataSource;

    @Test
    void quizItemsTableExistsWithExpectedColumnsAndPk() throws Exception {
        Map<String, ColumnSpec> columns = loadColumns("quiz_items");

        assertThat(columns)
                .containsOnlyKeys(
                        "content_id", "item_order", "topic_id", "stem", "explanation");
        assertPrimaryKeyColumns("quiz_items", "content_id", "item_order");
    }

    @Test
    void quizItemOptionsTableExistsWithCompositePkAndFkCascade() throws Exception {
        Map<String, ColumnSpec> columns = loadColumns("quiz_item_options");

        assertThat(columns)
                .containsOnlyKeys(
                        "content_id", "item_order", "option_order", "option_text", "is_correct");
        assertPrimaryKeyColumns("quiz_item_options", "content_id", "item_order", "option_order");

        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT delete_rule FROM information_schema.referential_constraints rc"
                                + "  JOIN information_schema.key_column_usage kcu"
                                + "    ON rc.constraint_name = kcu.constraint_name"
                                + " WHERE kcu.table_schema = 'plrs_ops'"
                                + "   AND kcu.table_name = 'quiz_item_options'"
                                + " LIMIT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("delete_rule")).isEqualTo("CASCADE");
        }
    }

    @Test
    void trg1RejectsQuizItemUnderNonQuizContent() throws Exception {
        Fixtures fx = seed();

        try (Connection conn = dataSource.getConnection()) {
            assertThatThrownBy(() -> insertQuizItem(conn, fx.videoContentId, 1, fx.topicId, "stem"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("must reference content with ctype=QUIZ");
        }
    }

    @Test
    void trg1AcceptsQuizItemUnderQuizContent() throws Exception {
        Fixtures fx = seed();

        try (Connection conn = dataSource.getConnection()) {
            insertQuizItem(conn, fx.quizContentId, 1, fx.topicId, "stem-1");
        }
    }

    @Test
    void mirrorTriggerRejectsCtypeChangeWhenItemsExist() throws Exception {
        Fixtures fx = seed();
        try (Connection conn = dataSource.getConnection()) {
            insertQuizItem(conn, fx.quizContentId, 1, fx.topicId, "stem-1");

            assertThatThrownBy(
                            () -> {
                                try (var ps = conn.prepareStatement(
                                        "UPDATE plrs_ops.content"
                                                + " SET ctype = 'VIDEO'"
                                                + " WHERE content_id = ?")) {
                                    ps.setLong(1, fx.quizContentId);
                                    ps.executeUpdate();
                                }
                            })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("cannot change ctype away from QUIZ");
        }
    }

    @Test
    void mirrorTriggerAcceptsCtypeChangeWhenNoItemsExist() throws Exception {
        Fixtures fx = seed();
        // No quiz items inserted under fx.quizContentId; ctype change OK at DB level.
        try (Connection conn = dataSource.getConnection();
                var ps = conn.prepareStatement(
                        "UPDATE plrs_ops.content SET ctype = 'VIDEO' WHERE content_id = ?")) {
            ps.setLong(1, fx.quizContentId);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    @Test
    void trg2DeferredAllowsSwapWithinTransaction() throws Exception {
        Fixtures fx = seed();
        try (Connection setup = dataSource.getConnection()) {
            insertQuizItem(setup, fx.quizContentId, 1, fx.topicId, "stem");
            insertOption(setup, fx.quizContentId, 1, 1, "A", true);
            insertOption(setup, fx.quizContentId, 1, 2, "B", false);
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Without DEFERRABLE, the first UPDATE would leave 0 correct → fail.
                try (var ps = conn.prepareStatement(
                        "UPDATE plrs_ops.quiz_item_options SET is_correct = FALSE"
                                + " WHERE content_id = ? AND item_order = ? AND option_order = 1")) {
                    ps.setLong(1, fx.quizContentId);
                    ps.setInt(2, 1);
                    ps.executeUpdate();
                }
                try (var ps = conn.prepareStatement(
                        "UPDATE plrs_ops.quiz_item_options SET is_correct = TRUE"
                                + " WHERE content_id = ? AND item_order = ? AND option_order = 2")) {
                    ps.setLong(1, fx.quizContentId);
                    ps.setInt(2, 1);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    @Test
    void trg2FiresAtCommitWhenAllOptionsBecomeIncorrect() throws Exception {
        Fixtures fx = seed();
        try (Connection setup = dataSource.getConnection()) {
            insertQuizItem(setup, fx.quizContentId, 2, fx.topicId, "stem-2");
            insertOption(setup, fx.quizContentId, 2, 1, "A", true);
            insertOption(setup, fx.quizContentId, 2, 2, "B", false);
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (var ps = conn.prepareStatement(
                    "UPDATE plrs_ops.quiz_item_options SET is_correct = FALSE"
                            + " WHERE content_id = ? AND item_order = ?")) {
                ps.setLong(1, fx.quizContentId);
                ps.setInt(2, 2);
                ps.executeUpdate();
            }

            assertThatThrownBy(conn::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("exactly one correct option");
            // Connection state may need rollback after a failed commit.
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // commit failure may have already aborted the tx.
            }
        }
    }

    @Test
    void trg2FiresOnCommitWithTwoCorrectOptions() throws Exception {
        Fixtures fx = seed();
        try (Connection setup = dataSource.getConnection()) {
            insertQuizItem(setup, fx.quizContentId, 3, fx.topicId, "stem-3");
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            insertOption(conn, fx.quizContentId, 3, 1, "A", true);
            insertOption(conn, fx.quizContentId, 3, 2, "B", true);

            assertThatThrownBy(conn::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("exactly one correct option");
            try {
                conn.rollback();
            } catch (SQLException ignored) {
            }
        }
    }

    @Test
    void cascadeDeleteOnContentRemovesItemsAndOptions() throws Exception {
        Fixtures fx = seed();
        try (Connection conn = dataSource.getConnection()) {
            insertQuizItem(conn, fx.quizContentId, 4, fx.topicId, "stem-4");
            insertOption(conn, fx.quizContentId, 4, 1, "A", true);
            insertOption(conn, fx.quizContentId, 4, 2, "B", false);

            try (var ps = conn.prepareStatement(
                    "DELETE FROM plrs_ops.content WHERE content_id = ?")) {
                ps.setLong(1, fx.quizContentId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            try (var ps = conn.prepareStatement(
                            "SELECT COUNT(*) FROM plrs_ops.quiz_items WHERE content_id = ?");
                    var ignored = ps) {
                ps.setLong(1, fx.quizContentId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
            }
            try (var ps = conn.prepareStatement(
                            "SELECT COUNT(*) FROM plrs_ops.quiz_item_options"
                                    + " WHERE content_id = ?")) {
                ps.setLong(1, fx.quizContentId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
            }
        }
    }

    private record Fixtures(long topicId, long videoContentId, long quizContentId) {}

    private Fixtures seed() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            long topicId;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                            + " VALUES (?, 'test') RETURNING topic_id")) {
                ps.setString(1, "quiz-topic-" + UUID.randomUUID());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    topicId = rs.getLong("topic_id");
                }
            }
            long videoContentId = insertContent(conn, topicId, "VIDEO");
            long quizContentId = insertContent(conn, topicId, "QUIZ");
            return new Fixtures(topicId, videoContentId, quizContentId);
        }
    }

    private static long insertContent(Connection conn, long topicId, String ctype)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.content"
                        + " (topic_id, title, ctype, difficulty, est_minutes, url)"
                        + " VALUES (?, ?, ?, 'BEGINNER', 10, 'https://x.y')"
                        + " RETURNING content_id")) {
            ps.setLong(1, topicId);
            ps.setString(2, "quiz-content-" + UUID.randomUUID());
            ps.setString(3, ctype);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("content_id");
            }
        }
    }

    private static void insertQuizItem(
            Connection conn, long contentId, int itemOrder, long topicId, String stem)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.quiz_items"
                        + " (content_id, item_order, topic_id, stem)"
                        + " VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, contentId);
            ps.setInt(2, itemOrder);
            ps.setLong(3, topicId);
            ps.setString(4, stem);
            ps.executeUpdate();
        }
    }

    private static void insertOption(
            Connection conn,
            long contentId,
            int itemOrder,
            int optionOrder,
            String text,
            boolean isCorrect)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.quiz_item_options"
                        + " (content_id, item_order, option_order, option_text, is_correct)"
                        + " VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, contentId);
            ps.setInt(2, itemOrder);
            ps.setInt(3, optionOrder);
            ps.setString(4, text);
            ps.setBoolean(5, isCorrect);
            ps.executeUpdate();
        }
    }

    private void assertPrimaryKeyColumns(String tableName, String... expectedInOrder)
            throws SQLException {
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
                                + "   AND c.relname = '"
                                + tableName
                                + "'"
                                + "   AND i.indisprimary"
                                + " ORDER BY array_position(i.indkey, a.attnum)")) {
            for (String expected : expectedInOrder) {
                assertThat(rs.next()).as("PK row %s", expected).isTrue();
                assertThat(rs.getString("column_name")).isEqualTo(expected);
            }
            assertThat(rs.next()).as("no extra PK columns").isFalse();
        }
    }

    private Map<String, ColumnSpec> loadColumns(String table) throws SQLException {
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
    static class QuizITApp {}
}
