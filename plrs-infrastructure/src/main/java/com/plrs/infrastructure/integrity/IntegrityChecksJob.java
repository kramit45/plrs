package com.plrs.infrastructure.integrity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * §3.b.5.5 / §3.b.8.3 nightly integrity checks. Each check runs an
 * idempotent SQL probe and writes one row to
 * {@code plrs_ops.integrity_checks} with status OK / WARN / FAIL.
 * Defence-in-depth against any future race that bypasses the
 * SERIALIZABLE-isolation cycle check on the prereq write path, plus
 * three sibling invariants (orphan rows, mastery bounds, version
 * drift).
 *
 * <p>Default schedule: 04:00 daily, configurable via
 * {@code plrs.integrity.cron}. Tests pin a never-firing expression
 * and call {@link #checkAll()} directly.
 *
 * <p>Per-check exceptions are caught + logged WARN and produce a
 * FAIL row with the exception message; one bad probe doesn't
 * starve the others.
 *
 * <p>Traces to: §3.b.5.5, §3.b.8.3.
 */
@Component
@ConditionalOnProperty(name = "plrs.integrity.enabled", havingValue = "true", matchIfMissing = true)
public class IntegrityChecksJob {

    static final String CHECK_DAG_ACYCLIC = "DAG_ACYCLIC";
    static final String CHECK_NO_ORPHAN_INTERACTIONS = "NO_ORPHAN_INTERACTIONS";
    static final String CHECK_MASTERY_BOUNDS = "MASTERY_BOUNDS";
    static final String CHECK_USER_SKILLS_VERSION = "USER_SKILLS_VERSION_NON_NEGATIVE";

    private static final Logger log = LoggerFactory.getLogger(IntegrityChecksJob.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public IntegrityChecksJob(DataSource dataSource, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.clock = clock;
        this.objectMapper = new ObjectMapper();
    }

    @Scheduled(cron = "${plrs.integrity.cron:0 0 4 * * *}")
    public void scheduledRun() {
        checkAll();
    }

    /** Synchronous entry point used by tests. */
    public void checkAll() {
        runDagAcyclic();
        runOrphanInteractionsCheck();
        runMasteryBoundsCheck();
        runUserSkillsVersionCheck();
    }

    private void runDagAcyclic() {
        try {
            // Walk the prereq chain from each content_id; a cycle is
            // detected when the walk reaches its own starting node.
            // Hard depth cap prevents infinite recursion when a cycle
            // does exist (UNION ALL doesn't dedupe, so depth-bound is
            // the only termination guarantee).
            List<Map<String, Object>> cycles =
                    jdbc.queryForList(
                            "WITH RECURSIVE walk AS ("
                                    + "  SELECT content_id AS root,"
                                    + "         prereq_content_id AS reached,"
                                    + "         1 AS depth"
                                    + "    FROM plrs_ops.prerequisites"
                                    + "  UNION ALL"
                                    + "  SELECT w.root, p.prereq_content_id, w.depth + 1"
                                    + "    FROM walk w"
                                    + "    JOIN plrs_ops.prerequisites p"
                                    + "      ON w.reached = p.content_id"
                                    + "   WHERE w.depth < 50"
                                    + ")"
                                    + " SELECT root, reached, depth"
                                    + "   FROM walk WHERE root = reached LIMIT 5");
            recordResult(
                    CHECK_DAG_ACYCLIC,
                    cycles.isEmpty() ? "OK" : "FAIL",
                    cycles.isEmpty() ? null : Map.of("cycles", cycles));
        } catch (Exception e) {
            recordFailure(CHECK_DAG_ACYCLIC, e);
        }
    }

    private void runOrphanInteractionsCheck() {
        try {
            // Interactions whose user_id or content_id no longer resolves.
            // ON DELETE CASCADE on both FKs means this should always be 0;
            // a non-zero count would mean a cascade was bypassed.
            Long count =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM plrs_ops.interactions i"
                                    + " WHERE NOT EXISTS (SELECT 1 FROM plrs_ops.users u WHERE u.id = i.user_id)"
                                    + "    OR NOT EXISTS (SELECT 1 FROM plrs_ops.content c WHERE c.content_id = i.content_id)",
                            Long.class);
            recordResult(
                    CHECK_NO_ORPHAN_INTERACTIONS,
                    count != null && count == 0 ? "OK" : "FAIL",
                    count != null && count > 0 ? Map.of("orphanCount", count) : null);
        } catch (Exception e) {
            recordFailure(CHECK_NO_ORPHAN_INTERACTIONS, e);
        }
    }

    private void runMasteryBoundsCheck() {
        try {
            // mastery_score must stay in [0, 1] per the domain VO. A row
            // outside that range means a bypass of the aggregate's
            // canonical constructor (rare; defence-in-depth).
            Long count =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM plrs_ops.user_skills"
                                    + " WHERE mastery_score < 0 OR mastery_score > 1",
                            Long.class);
            recordResult(
                    CHECK_MASTERY_BOUNDS,
                    count != null && count == 0 ? "OK" : "FAIL",
                    count != null && count > 0 ? Map.of("outOfBoundsCount", count) : null);
        } catch (Exception e) {
            recordFailure(CHECK_MASTERY_BOUNDS, e);
        }
    }

    private void runUserSkillsVersionCheck() {
        try {
            // user_skills_version is a monotonic counter (TRG-3); negative
            // values would mean the trigger was bypassed.
            Long count =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM plrs_ops.users WHERE user_skills_version < 0",
                            Long.class);
            recordResult(
                    CHECK_USER_SKILLS_VERSION,
                    count != null && count == 0 ? "OK" : "FAIL",
                    count != null && count > 0 ? Map.of("negativeVersionCount", count) : null);
        } catch (Exception e) {
            recordFailure(CHECK_USER_SKILLS_VERSION, e);
        }
    }

    private void recordResult(String checkName, String status, Object detail) {
        String detailJson = null;
        if (detail != null) {
            try {
                detailJson = objectMapper.writeValueAsString(detail);
            } catch (JsonProcessingException e) {
                log.warn("IntegrityChecksJob: failed to serialise detail for {}", checkName, e);
            }
        }
        jdbc.update(
                "INSERT INTO plrs_ops.integrity_checks (check_name, run_at, status, detail_json)"
                        + " VALUES (?, ?, ?, ?::jsonb)",
                checkName,
                java.sql.Timestamp.from(clock.instant()),
                status,
                detailJson);
    }

    private void recordFailure(String checkName, Exception e) {
        log.warn("IntegrityChecksJob: probe '{}' threw — recording FAIL", checkName, e);
        Map<String, String> err = new LinkedHashMap<>();
        err.put("error", e.getClass().getSimpleName());
        err.put("message", e.getMessage() == null ? "" : e.getMessage());
        recordResult(checkName, "FAIL", err);
    }
}
