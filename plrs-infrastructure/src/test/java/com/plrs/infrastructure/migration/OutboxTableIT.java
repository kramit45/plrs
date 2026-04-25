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
 * Verifies that {@code V9__outbox.sql} creates {@code plrs_ops.outbox_event}
 * with the expected columns, three CHECK constraints, and the partial
 * index on undelivered rows used by the drain job.
 *
 * <p>Traces to: §3.c.1.5 (outbox_event DDL), §2.e.3.6, FR-18.
 */
@SpringBootTest(
        classes = OutboxTableIT.OutboxITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false"
        })
class OutboxTableIT extends PostgresTestBase {

    @Autowired private DataSource dataSource;

    @Test
    void tableExistsWithExpectedColumns() throws Exception {
        Map<String, ColumnSpec> columns = loadColumns();

        assertThat(columns)
                .containsOnlyKeys(
                        "outbox_id",
                        "aggregate_type",
                        "aggregate_id",
                        "payload_json",
                        "created_at",
                        "delivered_at",
                        "attempts",
                        "last_error");
        assertThat(columns.get("outbox_id")).isEqualTo(new ColumnSpec("bigint", "NO", null));
        assertThat(columns.get("aggregate_type"))
                .isEqualTo(new ColumnSpec("character varying", "NO", 40));
        assertThat(columns.get("aggregate_id"))
                .isEqualTo(new ColumnSpec("character varying", "NO", 60));
        assertThat(columns.get("payload_json")).isEqualTo(new ColumnSpec("jsonb", "NO", null));
        assertThat(columns.get("created_at"))
                .isEqualTo(new ColumnSpec("timestamp with time zone", "NO", null));
        assertThat(columns.get("delivered_at"))
                .isEqualTo(new ColumnSpec("timestamp with time zone", "YES", null));
        assertThat(columns.get("attempts")).isEqualTo(new ColumnSpec("smallint", "NO", null));
        assertThat(columns.get("last_error"))
                .isEqualTo(new ColumnSpec("character varying", "YES", 500));
    }

    @Test
    void allThreeCheckConstraintsPresent() throws Exception {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT conname FROM pg_constraint c"
                                + "  JOIN pg_class t    ON t.oid = c.conrelid"
                                + "  JOIN pg_namespace n ON n.oid = t.relnamespace"
                                + " WHERE n.nspname = 'plrs_ops'"
                                + "   AND t.relname = 'outbox_event'"
                                + "   AND c.contype = 'c'")) {
            java.util.Set<String> names = new java.util.HashSet<>();
            while (rs.next()) {
                names.add(rs.getString("conname"));
            }
            assertThat(names)
                    .contains(
                            "outbox_agg_nn",
                            "outbox_aggregate_id_nn",
                            "outbox_attempts_bounded");
        }
    }

    @Test
    void partialIndexOnUndeliveredHasCorrectWhereClause() throws Exception {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT indexdef FROM pg_indexes"
                                + " WHERE schemaname = 'plrs_ops'"
                                + "   AND tablename  = 'outbox_event'"
                                + "   AND indexname  = 'idx_outbox_undelivered'")) {
            assertThat(rs.next()).as("idx_outbox_undelivered must exist").isTrue();
            String def = rs.getString("indexdef");
            assertThat(def)
                    .contains("created_at")
                    .containsIgnoringCase("where")
                    .contains("delivered_at IS NULL");
        }
    }

    @Test
    void blankAggregateTypeFails() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertThatThrownBy(
                            () ->
                                    insertEvent(
                                            conn, "   ", "agg-1", "{}", null, (short) 0, null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("outbox_agg_nn");
        }
    }

    @Test
    void attemptsAboveBoundFails() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertThatThrownBy(
                            () ->
                                    insertEvent(
                                            conn,
                                            "QUIZ",
                                            "agg-1",
                                            "{}",
                                            null,
                                            (short) 25,
                                            null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("outbox_attempts_bounded");
        }
    }

    @Test
    void undeliveredQueryReturnsOnlyUndelivered() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            insertEvent(conn, "QUIZ", "a-1", "{\"v\":1}", null, (short) 0, null);
            long delivered = insertEvent(conn, "QUIZ", "a-2", "{\"v\":2}", null, (short) 0, null);
            insertEvent(conn, "QUIZ", "a-3", "{\"v\":3}", null, (short) 0, null);

            try (var ps = conn.prepareStatement(
                    "UPDATE plrs_ops.outbox_event SET delivered_at = ? WHERE outbox_id = ?")) {
                ps.setTimestamp(1, Timestamp.from(Instant.now()));
                ps.setLong(2, delivered);
                ps.executeUpdate();
            }

            try (var ps = conn.prepareStatement(
                            "SELECT COUNT(*) FROM plrs_ops.outbox_event WHERE delivered_at IS NULL"
                                    + "  AND aggregate_type = 'QUIZ'");
                    var rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }
    }

    @Test
    void payloadJsonRoundTripsViaJsonbOperator() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long id = insertEvent(conn, "QUIZ", "a-rt", "{\"x\":\"hello\"}", null, (short) 0, null);

            try (var ps = conn.prepareStatement(
                            "SELECT payload_json->>'x' AS x FROM plrs_ops.outbox_event"
                                    + " WHERE outbox_id = ?")) {
                ps.setLong(1, id);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getString("x")).isEqualTo("hello");
                }
            }
        }
    }

    private static long insertEvent(
            Connection conn,
            String aggregateType,
            String aggregateId,
            String payloadJson,
            Instant deliveredAt,
            short attempts,
            String lastError)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.outbox_event"
                        + " (aggregate_type, aggregate_id, payload_json, delivered_at,"
                        + "  attempts, last_error)"
                        + " VALUES (?, ?, CAST(? AS JSONB), ?, ?, ?)"
                        + " RETURNING outbox_id")) {
            ps.setString(1, aggregateType);
            ps.setString(2, aggregateId);
            ps.setString(3, payloadJson);
            if (deliveredAt == null) {
                ps.setNull(4, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setTimestamp(4, Timestamp.from(deliveredAt));
            }
            ps.setShort(5, attempts);
            if (lastError == null) {
                ps.setNull(6, java.sql.Types.VARCHAR);
            } else {
                ps.setString(6, lastError);
            }
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("outbox_id");
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
                                + "   AND table_name   = 'outbox_event'"
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
    static class OutboxITApp {}
}
