package com.plrs.infrastructure.admin;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job refreshing the six FR-36 KPI materialised views (V19).
 *
 * <p>Uses {@code REFRESH MATERIALIZED VIEW CONCURRENTLY}, which is
 * non-blocking (admin dashboard reads keep working during the
 * refresh). Postgres requires a unique index on the MV for the
 * concurrent variant — V19 creates one per view.
 *
 * <p>Schedule is configurable via {@code plrs.admin.kpi-refresh.cron}
 * (defaults to top of every hour). Tests pin a never-firing
 * expression and call {@link #refreshNow()} directly.
 *
 * <p>Errors per view are logged and swallowed so one bad MV doesn't
 * starve the others.
 *
 * <p>Traces to: FR-36, §3.c.2.4.
 */
@Component
@ConditionalOnProperty(name = "spring.datasource.url")
public class RefreshKpiViewsJob {

    static final String[] VIEWS = {
        "mv_coverage_7d",
        "mv_ctr_7d",
        "mv_completion_rate_30d",
        "mv_cold_item_exposure_7d",
        "mv_avg_rating_weekly",
        "mv_precision_at_k_latest"
    };

    private static final Logger log = LoggerFactory.getLogger(RefreshKpiViewsJob.class);

    private final DataSource dataSource;

    public RefreshKpiViewsJob(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Scheduled(cron = "${plrs.admin.kpi-refresh.cron:0 0 * * * *}")
    public void refresh() {
        refreshNow();
    }

    /** Synchronous entry point used by tests + the admin recompute path. */
    public void refreshNow() {
        for (String view : VIEWS) {
            try (var conn = dataSource.getConnection();
                    var stmt = conn.createStatement()) {
                stmt.execute(
                        "REFRESH MATERIALIZED VIEW CONCURRENTLY plrs_dw." + view);
            } catch (Exception e) {
                log.warn("RefreshKpiViewsJob: failed to refresh plrs_dw.{}", view, e);
            }
        }
    }
}
