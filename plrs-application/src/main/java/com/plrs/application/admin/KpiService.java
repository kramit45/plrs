package com.plrs.application.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads the six FR-36 KPI materialised views (V19) into typed
 * snapshots for the admin dashboard. JdbcTemplate over JPA on
 * purpose: these views have no entity lifecycle and the column shape
 * differs across views, so wiring six entity classes would be more
 * weight than value.
 *
 * <p>The MVs cache stale data between {@code RefreshKpiViewsJob}
 * runs; the {@code computed_at} column on the single-row views lets
 * callers display "as of …" timestamps.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}.
 *
 * <p>Traces to: FR-36, §3.c.2.4.
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class KpiService {

    private final JdbcTemplate jdbc;

    public KpiService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public ScalarKpi getCoverage7d() {
        return jdbc.queryForObject(
                "SELECT coverage AS value, computed_at FROM plrs_dw.mv_coverage_7d",
                (rs, n) -> new ScalarKpi(rs.getBigDecimal("value"), rs.getTimestamp("computed_at").toInstant()));
    }

    public ScalarKpi getCtr7d() {
        return jdbc.queryForObject(
                "SELECT ctr AS value, computed_at FROM plrs_dw.mv_ctr_7d",
                (rs, n) -> new ScalarKpi(rs.getBigDecimal("value"), rs.getTimestamp("computed_at").toInstant()));
    }

    public List<DailyCompletion> getCompletionRateLast30Days() {
        return jdbc.query(
                "SELECT date_actual, completion_rate"
                        + " FROM plrs_dw.mv_completion_rate_30d"
                        + " ORDER BY date_actual DESC",
                (rs, n) ->
                        new DailyCompletion(
                                rs.getDate("date_actual").toLocalDate(),
                                rs.getBigDecimal("completion_rate")));
    }

    public ScalarKpi getColdItemExposure7d() {
        return jdbc.queryForObject(
                "SELECT cold_share AS value, computed_at FROM plrs_dw.mv_cold_item_exposure_7d",
                (rs, n) -> new ScalarKpi(rs.getBigDecimal("value"), rs.getTimestamp("computed_at").toInstant()));
    }

    public List<WeeklyRating> getAvgRatingWeekly() {
        return jdbc.query(
                "SELECT iso_year_week, avg_rating, n_ratings"
                        + " FROM plrs_dw.mv_avg_rating_weekly"
                        + " ORDER BY iso_year_week DESC",
                (rs, n) ->
                        new WeeklyRating(
                                rs.getString("iso_year_week"),
                                rs.getBigDecimal("avg_rating"),
                                rs.getInt("n_ratings")));
    }

    public List<EvalMetric> getLatestEvalMetrics() {
        return jdbc.query(
                "SELECT variant_name, precision_at_k, ndcg_at_k, coverage, ran_at"
                        + " FROM plrs_dw.mv_precision_at_k_latest"
                        + " ORDER BY variant_name",
                (rs, n) ->
                        new EvalMetric(
                                rs.getString("variant_name"),
                                rs.getBigDecimal("precision_at_k"),
                                rs.getBigDecimal("ndcg_at_k"),
                                rs.getBigDecimal("coverage"),
                                rs.getTimestamp("ran_at").toInstant()));
    }

    /** Aggregate snapshot used by the admin dashboard JSON endpoint. */
    public KpiSnapshot snapshot() {
        return new KpiSnapshot(
                getCoverage7d(),
                getCtr7d(),
                getColdItemExposure7d(),
                getCompletionRateLast30Days(),
                getAvgRatingWeekly(),
                getLatestEvalMetrics());
    }

    /** Single-value MV result with the timestamp the snapshot was computed. */
    public record ScalarKpi(BigDecimal value, Instant computedAt) {}

    public record DailyCompletion(LocalDate date, BigDecimal completionRate) {}

    public record WeeklyRating(String isoYearWeek, BigDecimal avgRating, int nRatings) {}

    public record EvalMetric(
            String variantName,
            BigDecimal precisionAtK,
            BigDecimal ndcgAtK,
            BigDecimal coverage,
            Instant ranAt) {}

    public record KpiSnapshot(
            ScalarKpi coverage7d,
            ScalarKpi ctr7d,
            ScalarKpi coldItemExposure7d,
            List<DailyCompletion> completionRateLast30Days,
            List<WeeklyRating> avgRatingWeekly,
            List<EvalMetric> latestEvalMetrics) {}
}
