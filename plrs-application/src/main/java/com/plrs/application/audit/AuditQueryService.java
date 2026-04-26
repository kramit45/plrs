package com.plrs.application.audit;

import com.plrs.domain.user.UserId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * FR-42 admin audit viewer query service. Reads {@code plrs_ops.audit_log}
 * with optional filters (actor, action, time range) and returns a
 * paginated slice. Native query so we can compose the dynamic WHERE
 * without dragging in JPA criteria.
 *
 * <p>Pagination uses {@code (pageNumber * pageSize)} OFFSET +
 * {@code pageSize} LIMIT — fine for moderate audit volumes (the
 * write-side aspect produces ~1 row per state-changing call). A
 * keyset pagination upgrade is deferred to Iter 5 if the audit log
 * grows beyond a few million rows.
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class AuditQueryService {

    /** Result of a paginated query. */
    public record AuditPage(List<AuditEntry> entries, int pageNumber, int pageSize, long totalRows) {}

    private final JdbcTemplate jdbc;

    public AuditQueryService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public AuditPage search(
            Optional<UserId> actor,
            Optional<String> action,
            Optional<Instant> from,
            Optional<Instant> to,
            int pageSize,
            int pageNumber) {
        if (pageSize <= 0 || pageSize > 200) {
            pageSize = 50;
        }
        if (pageNumber < 0) {
            pageNumber = 0;
        }

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        actor.ifPresent(
                a -> {
                    where.append(" AND actor_user_id = ? ");
                    args.add(a.value());
                });
        action.filter(s -> !s.isBlank())
                .ifPresent(
                        s -> {
                            where.append(" AND action = ? ");
                            args.add(s);
                        });
        from.ifPresent(
                t -> {
                    where.append(" AND occurred_at >= ? ");
                    args.add(Timestamp.from(t));
                });
        to.ifPresent(
                t -> {
                    where.append(" AND occurred_at <= ? ");
                    args.add(Timestamp.from(t));
                });

        long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM plrs_ops.audit_log" + where, Long.class, args.toArray());

        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(pageSize);
        pagedArgs.add((long) pageNumber * pageSize);
        List<AuditEntry> rows =
                jdbc.query(
                        "SELECT audit_id, occurred_at, actor_user_id, action, entity_type,"
                                + "  entity_id, detail_json"
                                + " FROM plrs_ops.audit_log"
                                + where
                                + " ORDER BY occurred_at DESC"
                                + " LIMIT ? OFFSET ?",
                        pagedArgs.toArray(),
                        (rs, n) ->
                                new AuditEntry(
                                        rs.getLong("audit_id"),
                                        rs.getTimestamp("occurred_at").toInstant(),
                                        Optional.ofNullable((UUID) rs.getObject("actor_user_id")),
                                        rs.getString("action"),
                                        Optional.ofNullable(rs.getString("entity_type")),
                                        Optional.ofNullable(rs.getString("entity_id")),
                                        Optional.ofNullable(rs.getString("detail_json"))));

        return new AuditPage(rows, pageNumber, pageSize, total);
    }
}
