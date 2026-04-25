package com.plrs.infrastructure.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.infrastructure.testsupport.PostgresTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies V16__warehouse_min.sql migration:
 * <ol>
 *   <li>plrs_dw schema and tables exist.
 *   <li>dim_date is fully seeded (1095 days, 2025-01-01..2027-12-31).
 *   <li>fact_interaction's FKs reject orphan inserts.
 * </ol>
 */
@SpringBootTest(
        classes = WarehouseSchemaIT.WarehouseITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=plrs_ops"
        })
class WarehouseSchemaIT extends PostgresTestBase {

    @Autowired private DataSource dataSource;

    @Test
    void plrsDwSchemaAndCoreTablesExist() throws SQLException {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement()) {
            for (String table :
                    new String[] {
                        "dim_date", "dim_user", "dim_topic",
                        "dim_content", "fact_interaction"
                    }) {
                try (var rs =
                        stmt.executeQuery(
                                "SELECT 1 FROM information_schema.tables"
                                        + " WHERE table_schema = 'plrs_dw'"
                                        + " AND table_name = '"
                                        + table
                                        + "'")) {
                    assertThat(rs.next()).as("plrs_dw.%s exists", table).isTrue();
                }
            }
        }
    }

    @Test
    void dimDateIsFullySeededWith1095Days() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer rows =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM plrs_dw.dim_date", Integer.class);
        assertThat(rows)
                .as(
                        "dim_date covers every day in [2025-01-01, 2027-12-31]"
                                + " inclusive (3 × 365, no leap year in range)")
                .isEqualTo(1095);
    }

    @Test
    void factInteractionFkRejectsOrphanInserts() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // No matching dim_user / dim_content / dim_topic rows; insert
        // must fail on at least one FK.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO plrs_dw.fact_interaction"
                                                + " (date_sk, user_sk, content_sk,"
                                                + "  topic_sk, occurred_at, event_type)"
                                                + " VALUES (?, ?, ?, ?, NOW(), 'COMPLETE')",
                                        20260101,
                                        9_999_999L,
                                        9_999_999L,
                                        9_999_999L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @SpringBootApplication(
            exclude = {
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    static class WarehouseITApp {}
}
