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
 * Verifies that {@code V3__user_roles.sql} creates {@code plrs_ops.user_roles}
 * with the expected columns, the composite primary key, the FK to
 * {@code users(id)} with {@code ON DELETE CASCADE}, and the role CHECK
 * constraint that pins the three values in lockstep with the domain
 * {@code Role} enum (step 20).
 *
 * <p>Uses a nested minimal {@link UserRolesITApp} to sidestep the plrs-web
 * reactor cycle (same rationale as {@code FlywayBaselineIT}).
 *
 * <p>Traces to: §3.c (user_roles), §7 (additive role model).
 */
@SpringBootTest(
        classes = UserRolesTableIT.UserRolesITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false"
        })
class UserRolesTableIT extends PostgresTestBase {

    private static final String VALID_BCRYPT =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    @Autowired private DataSource dataSource;

    @Test
    void tableExistsWithExpectedColumns() throws Exception {
        Map<String, ColumnSpec> columns = loadColumns();

        assertThat(columns).containsOnlyKeys("user_id", "role", "assigned_at");
        assertThat(columns.get("user_id"))
                .isEqualTo(new ColumnSpec("uuid", "NO", null));
        assertThat(columns.get("role"))
                .isEqualTo(new ColumnSpec("character varying", "NO", 16));
        assertThat(columns.get("assigned_at"))
                .isEqualTo(new ColumnSpec("timestamp with time zone", "NO", null));
    }

    @Test
    void foreignKeyToUsersCascadesOnDelete() throws Exception {
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
                                + "   AND kcu.table_name   = 'user_roles'")) {
            assertThat(rs.next())
                    .as("referential constraint row present for user_roles")
                    .isTrue();
            assertThat(rs.getString("delete_rule")).isEqualTo("CASCADE");
            assertThat(rs.getString("column_name")).isEqualTo("user_id");
            assertThat(rs.getString("ref_table")).isEqualTo("users");
            assertThat(rs.getString("ref_column")).isEqualTo("id");
        }
    }

    @Test
    void checkConstraintRejectsUnknownRoleValue() throws Exception {
        UUID userId = UUID.randomUUID();
        try (Connection conn = dataSource.getConnection()) {
            insertUser(conn, userId);

            assertThatThrownBy(() -> insertRole(conn, userId, "UNKNOWN"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("user_roles_value");
        }
    }

    @Test
    void primaryKeyRejectsDuplicateUserAndRole() throws Exception {
        UUID userId = UUID.randomUUID();
        try (Connection conn = dataSource.getConnection()) {
            insertUser(conn, userId);
            insertRole(conn, userId, "STUDENT");

            assertThatThrownBy(() -> insertRole(conn, userId, "STUDENT"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("user_roles_pk");
        }
    }

    @Test
    void deletingUserCascadesToUserRoles() throws Exception {
        UUID userId = UUID.randomUUID();
        try (Connection conn = dataSource.getConnection()) {
            insertUser(conn, userId);
            insertRole(conn, userId, "STUDENT");
            insertRole(conn, userId, "INSTRUCTOR");

            assertThat(countRoles(conn, userId)).isEqualTo(2);

            try (var ps = conn.prepareStatement("DELETE FROM plrs_ops.users WHERE id = ?")) {
                ps.setObject(1, userId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            assertThat(countRoles(conn, userId)).isZero();
        }
    }

    private static void insertUser(Connection conn, UUID id) throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.users"
                        + " (id, email, password_hash, created_at, updated_at, created_by)"
                        + " VALUES (?, ?, ?, NOW(), NOW(), 'test')")) {
            ps.setObject(1, id);
            ps.setString(2, "roles-" + id + "@example.com");
            ps.setString(3, VALID_BCRYPT);
            ps.executeUpdate();
        }
    }

    private static void insertRole(Connection conn, UUID userId, String role) throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO plrs_ops.user_roles (user_id, role, assigned_at)"
                        + " VALUES (?, ?, NOW())")) {
            ps.setObject(1, userId);
            ps.setString(2, role);
            ps.executeUpdate();
        }
    }

    private static int countRoles(Connection conn, UUID userId) throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM plrs_ops.user_roles WHERE user_id = ?")) {
            ps.setObject(1, userId);
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
                                + "   AND table_name   = 'user_roles'"
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
    static class UserRolesITApp {}
}
