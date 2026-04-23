package com.plrs.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.plrs.infrastructure.testsupport.PostgresTestBase;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies that Flyway V1 runs against a real PostgreSQL at application
 * boot, creating the {@code plrs_ops} and {@code plrs_dw} schemas and
 * recording the migration in {@code flyway_schema_history}.
 *
 * <p>Why a local {@link FlywayITApp} rather than {@code PlrsApplication.class}:
 * {@code PlrsApplication} lives in {@code plrs-web}, which depends on
 * {@code plrs-infrastructure}; test-scoping it here would introduce a Maven
 * reactor cycle. Instead this IT boots a minimal Spring Boot application
 * scoped to the infra module and sets {@code spring.flyway.*} via
 * {@link SpringBootTest#properties()} using the same values that ship in
 * {@code plrs-web/application.yml}, so the behavior under test matches
 * production wiring.
 *
 * <p>Traces to: §3.c (two-schema layout) · §4.a (Flyway conventions).
 */
@SpringBootTest(
        classes = FlywayBaselineIT.FlywayITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false"
        })
class FlywayBaselineIT extends PostgresTestBase {

    @Autowired private DataSource dataSource;

    @Test
    void v1IsAppliedAndBothSchemasExist() throws Exception {
        try (var conn = dataSource.getConnection();
                var stmt = conn.createStatement()) {
            try (var rs =
                    stmt.executeQuery(
                            "SELECT success FROM plrs_ops.flyway_schema_history"
                                    + " WHERE version = '1'")) {
                assertThat(rs.next()).as("V1 row present in flyway_schema_history").isTrue();
                assertThat(rs.getBoolean("success")).as("V1 recorded as successful").isTrue();
            }

            Set<String> schemas = new HashSet<>();
            try (var rs =
                    stmt.executeQuery("SELECT schema_name FROM information_schema.schemata")) {
                while (rs.next()) {
                    schemas.add(rs.getString(1));
                }
            }
            assertThat(schemas).contains("plrs_ops", "plrs_dw");
        }
    }

    @SpringBootApplication(
            exclude = {
                HibernateJpaAutoConfiguration.class,
                JpaRepositoriesAutoConfiguration.class,
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    static class FlywayITApp {}
}
