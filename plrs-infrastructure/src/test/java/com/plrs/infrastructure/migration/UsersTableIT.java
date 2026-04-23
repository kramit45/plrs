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
 * Verifies that {@code V2__users.sql} creates {@code plrs_ops.users} with
 * the expected columns, constraints, and index, and that the unique
 * constraint on {@code email} is enforced at the engine level.
 *
 * <p>Uses a nested minimal {@link UsersITApp} rather than
 * {@code PlrsApplication.class} for the same reactor-cycle reason as
 * {@code FlywayBaselineIT}: plrs-web depends on plrs-infrastructure, so
 * test-scoping the Boot entrypoint here would form a cycle.
 *
 * <p>Traces to: §3.c (users table), §4.a (Flyway conventions).
 */
@SpringBootTest(
        classes = UsersTableIT.UsersITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false"
        })
class UsersTableIT extends PostgresTestBase {

    private static final String VALID_BCRYPT =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    @Autowired private DataSource dataSource;

    @Test
    void tableExistsWithExpectedColumns() throws Exception {
        Map<String, ColumnSpec> columns = loadColumns();

        assertThat(columns).containsOnlyKeys(
                "id", "email", "password_hash", "created_at", "updated_at", "created_by");
        assertThat(columns.get("id"))
                .isEqualTo(new ColumnSpec("uuid", "NO", null));
        assertThat(columns.get("email"))
                .isEqualTo(new ColumnSpec("character varying", "NO", 254));
        assertThat(columns.get("password_hash"))
                .isEqualTo(new ColumnSpec("character varying", "NO", 60));
        assertThat(columns.get("created_at"))
                .isEqualTo(new ColumnSpec("timestamp with time zone", "NO", null));
        assertThat(columns.get("updated_at"))
                .isEqualTo(new ColumnSpec("timestamp with time zone", "NO", null));
        assertThat(columns.get("created_by"))
                .isEqualTo(new ColumnSpec("character varying", "NO", 64));
    }

    @Test
    void emailUniqueConstraintIsDeclared() throws Exception {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT tc.constraint_type, ccu.column_name"
                                + "  FROM information_schema.table_constraints tc"
                                + "  JOIN information_schema.constraint_column_usage ccu"
                                + "    ON tc.constraint_name = ccu.constraint_name"
                                + "   AND tc.constraint_schema = ccu.constraint_schema"
                                + " WHERE tc.table_schema = 'plrs_ops'"
                                + "   AND tc.table_name   = 'users'"
                                + "   AND tc.constraint_name = 'users_email_key'")) {
            assertThat(rs.next())
                    .as("users_email_key constraint row present")
                    .isTrue();
            assertThat(rs.getString("constraint_type")).isEqualTo("UNIQUE");
            assertThat(rs.getString("column_name")).isEqualTo("email");
        }
    }

    @Test
    void updatedAfterCreatedCheckConstraintIsDeclared() throws Exception {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT constraint_type FROM information_schema.table_constraints"
                                + " WHERE table_schema = 'plrs_ops'"
                                + "   AND table_name   = 'users'"
                                + "   AND constraint_name = 'users_updated_after_created'")) {
            assertThat(rs.next())
                    .as("users_updated_after_created constraint row present")
                    .isTrue();
            assertThat(rs.getString("constraint_type")).isEqualTo("CHECK");
        }
    }

    @Test
    void createdAtIndexIsDeclared() throws Exception {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(
                        "SELECT indexname FROM pg_indexes"
                                + " WHERE schemaname = 'plrs_ops'"
                                + "   AND tablename  = 'users'"
                                + "   AND indexname  = 'idx_users_created_at'")) {
            assertThat(rs.next()).as("idx_users_created_at index present").isTrue();
        }
    }

    @Test
    void duplicateEmailInsertIsRejected() throws Exception {
        String email = "dup-" + UUID.randomUUID() + "@example.com";

        try (Connection conn = dataSource.getConnection()) {
            insertUser(conn, UUID.randomUUID(), email);

            UUID secondId = UUID.randomUUID();
            assertThatThrownBy(() -> insertUser(conn, secondId, email))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("users_email_key");
        }
    }

    private static void insertUser(Connection conn, UUID id, String email) throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.users"
                        + " (id, email, password_hash, created_at, updated_at, created_by)"
                        + " VALUES (?, ?, ?, NOW(), NOW(), 'test')")) {
            ps.setObject(1, id);
            ps.setString(2, email);
            ps.setString(3, VALID_BCRYPT);
            ps.executeUpdate();
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
                                + "   AND table_name   = 'users'"
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
    static class UsersITApp {}
}
