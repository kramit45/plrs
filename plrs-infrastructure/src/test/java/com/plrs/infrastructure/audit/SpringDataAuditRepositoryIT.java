package com.plrs.infrastructure.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.application.audit.AuditEvent;
import com.plrs.application.audit.AuditRepository;
import com.plrs.domain.user.UserId;
import com.plrs.infrastructure.testsupport.PostgresTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for {@link SpringDataAuditRepository}. Covers:
 *
 * <ul>
 *   <li>append + read-back of every column,
 *   <li>nullable columns staying NULL,
 *   <li>TRG-4 rejecting UPDATE on the table.
 * </ul>
 */
@SpringBootTest(
        classes = SpringDataAuditRepositoryIT.AuditRepoITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=plrs_ops"
        })
@Transactional
class SpringDataAuditRepositoryIT extends PostgresTestBase {

    private static final String VALID_BCRYPT =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    @Autowired private AuditRepository auditRepository;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager em;

    private UserId seedUser() throws SQLException {
        try (Connection conn = dataSource.getConnection();
                var ps = conn.prepareStatement(
                        "INSERT INTO plrs_ops.users"
                                + " (id, email, password_hash, created_at, updated_at, created_by)"
                                + " VALUES (?, ?, ?, NOW(), NOW(), 'test')")) {
            UUID uid = UUID.randomUUID();
            ps.setObject(1, uid);
            ps.setString(2, "audit-" + uid + "@example.com");
            ps.setString(3, VALID_BCRYPT);
            ps.executeUpdate();
            return UserId.of(uid);
        }
    }

    @Test
    void appendThenReadBackPersistsAllColumns() throws SQLException {
        UserId userId = seedUser();
        Instant t0 = Instant.parse("2026-04-25T10:00:00Z");

        auditRepository.append(
                new AuditEvent(
                        Optional.of(userId),
                        "USER_REGISTERED",
                        Optional.of("user"),
                        Optional.of(userId.value().toString()),
                        Optional.of("{\"k\":\"v\"}"),
                        t0));
        em.flush();

        Object[] row =
                (Object[])
                        em.createNativeQuery(
                                        "SELECT actor_user_id, action, entity_type, entity_id,"
                                                + "       CAST(detail_json AS text)"
                                                + " FROM plrs_ops.audit_log"
                                                + " WHERE actor_user_id = :u")
                                .setParameter("u", userId.value())
                                .getSingleResult();
        assertThat(row[0]).isEqualTo(userId.value());
        assertThat(row[1]).isEqualTo("USER_REGISTERED");
        assertThat(row[2]).isEqualTo("user");
        assertThat(row[3]).isEqualTo(userId.value().toString());
        assertThat(((String) row[4])).contains("\"k\"").contains("\"v\"");
    }

    @Test
    void appendWithEmptyOptionalsLeavesColumnsNull() throws SQLException {
        // Use an action keyed off this test so it doesn't collide with
        // other rows that may pre-exist in the same transactional view.
        Instant t0 = Instant.parse("2026-04-25T11:00:00Z");
        auditRepository.append(
                new AuditEvent(
                        Optional.empty(),
                        "ANON_PROBE_NULLS",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        t0));
        em.flush();

        Object[] row =
                (Object[])
                        em.createNativeQuery(
                                        "SELECT actor_user_id, entity_type, entity_id, detail_json"
                                                + " FROM plrs_ops.audit_log"
                                                + " WHERE action = 'ANON_PROBE_NULLS'")
                                .getSingleResult();
        assertThat(row[0]).isNull();
        assertThat(row[1]).isNull();
        assertThat(row[2]).isNull();
        assertThat(row[3]).isNull();
    }

    @Test
    void updateOnAuditLogIsRejectedByTrg4() throws SQLException {
        Instant t0 = Instant.parse("2026-04-25T12:00:00Z");
        auditRepository.append(
                new AuditEvent(
                        Optional.empty(),
                        "TRG_4_PROBE",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        t0));
        em.flush();

        assertThatThrownBy(
                        () -> {
                            em.createNativeQuery(
                                            "UPDATE plrs_ops.audit_log"
                                                    + " SET action = 'MUTATED'"
                                                    + " WHERE action = 'TRG_4_PROBE'")
                                    .executeUpdate();
                            em.flush();
                        })
                .hasMessageContaining("audit_log is append-only");
    }

    @SpringBootApplication(
            exclude = {
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    static class AuditRepoITApp {}
}
