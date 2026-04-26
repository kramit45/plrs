package com.plrs.infrastructure.integrity;

import static org.assertj.core.api.Assertions.assertThat;

import com.plrs.infrastructure.testsupport.PostgresTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * IT for {@link IntegrityChecksJob}. Confirms each of the four checks
 * writes an OK row on a clean DB, and that an injected cycle in the
 * prereq table flips DAG_ACYCLIC to FAIL with detail.
 *
 * <p>Traces to: §3.b.5.5, §3.b.8.3.
 */
@SpringBootTest(
        classes = IntegrityChecksJobIT.IntegrityITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false",
            "plrs.integrity.cron=0 0 3 1 1 *"
        })
class IntegrityChecksJobIT extends PostgresTestBase {

    private static final String VALID_BCRYPT =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    @Autowired private DataSource dataSource;
    @Autowired private IntegrityChecksJob job;

    @BeforeEach
    void cleanIntegrityTable() throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement()) {
            s.execute("TRUNCATE plrs_ops.integrity_checks");
            s.execute("TRUNCATE plrs_ops.prerequisites");
        }
    }

    @Test
    void cleanDbProducesAllOkRows() {
        job.checkAll();

        List<Map<String, Object>> rows =
                new JdbcTemplate(dataSource)
                        .queryForList(
                                "SELECT check_name, status FROM plrs_ops.integrity_checks"
                                        + " ORDER BY check_name");
        assertThat(rows).hasSize(4);
        assertThat(rows).allSatisfy(r -> assertThat(r).containsEntry("status", "OK"));
        assertThat(
                        rows.stream()
                                .map(r -> (String) r.get("check_name"))
                                .toList())
                .containsExactlyInAnyOrder(
                        IntegrityChecksJob.CHECK_DAG_ACYCLIC,
                        IntegrityChecksJob.CHECK_NO_ORPHAN_INTERACTIONS,
                        IntegrityChecksJob.CHECK_MASTERY_BOUNDS,
                        IntegrityChecksJob.CHECK_USER_SKILLS_VERSION);
    }

    @Test
    void injectedCycleFlipsDagCheckToFailWithDetail() throws SQLException {
        // Inject a 2-cycle directly — bypasses the @Transactional cycle
        // check the application path uses.
        long topicId;
        long aId;
        long bId;
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement()) {
            try (var rs =
                    s.executeQuery(
                            "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                                    + " VALUES ('icheck "
                                    + UUID.randomUUID()
                                    + "', 'icheck')"
                                    + " RETURNING topic_id")) {
                rs.next();
                topicId = rs.getLong("topic_id");
            }
            try (var rs =
                    s.executeQuery(
                            "INSERT INTO plrs_ops.content"
                                    + " (topic_id, title, ctype, difficulty, est_minutes, url)"
                                    + " VALUES ("
                                    + topicId
                                    + ", 'A "
                                    + UUID.randomUUID()
                                    + "',"
                                    + " 'VIDEO', 'BEGINNER', 5, 'https://x.y/a')"
                                    + " RETURNING content_id")) {
                rs.next();
                aId = rs.getLong("content_id");
            }
            try (var rs =
                    s.executeQuery(
                            "INSERT INTO plrs_ops.content"
                                    + " (topic_id, title, ctype, difficulty, est_minutes, url)"
                                    + " VALUES ("
                                    + topicId
                                    + ", 'B "
                                    + UUID.randomUUID()
                                    + "',"
                                    + " 'VIDEO', 'BEGINNER', 5, 'https://x.y/b')"
                                    + " RETURNING content_id")) {
                rs.next();
                bId = rs.getLong("content_id");
            }
            // a depends on b; b depends on a → cycle.
            s.execute(
                    "INSERT INTO plrs_ops.prerequisites (content_id, prereq_content_id)"
                            + " VALUES ("
                            + aId
                            + ", "
                            + bId
                            + "), ("
                            + bId
                            + ", "
                            + aId
                            + ")");
        }

        job.checkAll();

        Map<String, Object> dagRow =
                new JdbcTemplate(dataSource)
                        .queryForMap(
                                "SELECT status, detail_json::text AS detail FROM plrs_ops.integrity_checks"
                                        + " WHERE check_name = ? ORDER BY run_at DESC LIMIT 1",
                                IntegrityChecksJob.CHECK_DAG_ACYCLIC);
        assertThat(dagRow).containsEntry("status", "FAIL");
        assertThat((String) dagRow.get("detail")).contains("cycles");
    }

    @Test
    void multipleRunsAccumulateRowsRatherThanReplacing() {
        job.checkAll();
        job.checkAll();

        Long total =
                new JdbcTemplate(dataSource)
                        .queryForObject(
                                "SELECT COUNT(*) FROM plrs_ops.integrity_checks", Long.class);
        // Two runs × four checks each.
        assertThat(total).isEqualTo(8L);
    }

    @SpringBootApplication(
            scanBasePackages = "com.plrs.infrastructure.integrity",
            exclude = {
                HibernateJpaAutoConfiguration.class,
                JpaRepositoriesAutoConfiguration.class,
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    static class IntegrityITApp {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
