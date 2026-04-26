package com.plrs.application.admin;

import com.plrs.application.audit.Auditable;
import com.plrs.domain.user.UserId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-40 runtime-tunable read/write service. Reads go through the
 * {@code config-params} cache so hot-path callers (HybridRanker,
 * MmrReranker, PathPlanner) don't pay JDBC latency on every score
 * computation. Updates evict the cache so the next read picks up the
 * new value.
 *
 * <p>Type-aware getters ({@link #getDouble}, {@link #getInt}) return
 * {@code OptionalDouble} / {@code OptionalInt} so callers can fall
 * back to a hardcoded default when the parameter is missing or the
 * value can't parse — keeps the recommender working through any
 * cache miss / DB hiccup.
 *
 * <p>Cache implementation: see {@code CachingConfig} — a process-local
 * {@code ConcurrentMapCacheManager} is enough for Iter 4 (single-node
 * deploy); switching to Redis is a configuration change in Iter 5.
 *
 * <p>Traces to: FR-40.
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class ConfigParamService {

    static final String CACHE_NAME = "config-params";

    private final JdbcTemplate jdbc;

    public ConfigParamService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Cacheable(CACHE_NAME)
    public Optional<String> getString(String name) {
        List<String> rows =
                jdbc.queryForList(
                        "SELECT param_value FROM plrs_ops.config_params WHERE param_name = ?",
                        String.class,
                        name);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Tolerates missing key or unparseable value — returns empty. */
    public OptionalDouble getDouble(String name) {
        return getString(name)
                .map(
                        v -> {
                            try {
                                return OptionalDouble.of(Double.parseDouble(v));
                            } catch (NumberFormatException e) {
                                return OptionalDouble.empty();
                            }
                        })
                .orElse(OptionalDouble.empty());
    }

    public OptionalInt getInt(String name) {
        return getString(name)
                .map(
                        v -> {
                            try {
                                return OptionalInt.of(Integer.parseInt(v));
                            } catch (NumberFormatException e) {
                                return OptionalInt.empty();
                            }
                        })
                .orElse(OptionalInt.empty());
    }

    public List<ConfigParam> findAll() {
        return jdbc.query(
                "SELECT param_name, param_value, value_type, description, updated_at, updated_by"
                        + " FROM plrs_ops.config_params ORDER BY param_name",
                (rs, n) ->
                        new ConfigParam(
                                rs.getString("param_name"),
                                rs.getString("param_value"),
                                rs.getString("value_type"),
                                Optional.ofNullable(rs.getString("description")),
                                rs.getTimestamp("updated_at").toInstant(),
                                Optional.ofNullable((UUID) rs.getObject("updated_by"))));
    }

    @Transactional
    @CacheEvict(value = CACHE_NAME, allEntries = true)
    @Auditable(action = "CONFIG_PARAM_UPDATED", entityType = "config_param")
    public void update(String name, String value, UserId updatedBy) {
        int updated =
                jdbc.update(
                        "UPDATE plrs_ops.config_params"
                                + " SET param_value = ?, updated_at = ?, updated_by = ?"
                                + " WHERE param_name = ?",
                        value,
                        Timestamp.from(Instant.now()),
                        updatedBy.value(),
                        name);
        if (updated == 0) {
            throw new IllegalArgumentException("Unknown config param: " + name);
        }
    }
}
