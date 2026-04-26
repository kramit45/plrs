package com.plrs.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.plrs.infrastructure.testsupport.PostgresTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
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
 * Verifies V19 creates the fact_recommendation table plus the six KPI
 * materialised views, every MV has a unique index so REFRESH
 * CONCURRENTLY succeeds, and the views are queryable on a freshly
 * migrated empty database.
 *
 * <p>Traces to: FR-36, §3.c.2.4.
 */
@SpringBootTest(
        classes = KpiViewsIT.KpiITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false"
        })
class KpiViewsIT extends PostgresTestBase {

    @Autowired private DataSource dataSource;

    @Test
    void factRecommendationTableExists() throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement();
                var rs = s.executeQuery(
                        "SELECT 1 FROM information_schema.tables"
                                + " WHERE table_schema = 'plrs_dw'"
                                + "   AND table_name   = 'fact_recommendation'")) {
            assertThat(rs.next()).isTrue();
        }
    }

    @Test
    void allSixKpiMvsExist() throws SQLException {
        Set<String> mvs = new HashSet<>();
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement();
                var rs = s.executeQuery(
                        "SELECT matviewname FROM pg_matviews"
                                + " WHERE schemaname = 'plrs_dw'")) {
            while (rs.next()) {
                mvs.add(rs.getString("matviewname"));
            }
        }
        assertThat(mvs)
                .contains(
                        "mv_coverage_7d",
                        "mv_ctr_7d",
                        "mv_completion_rate_30d",
                        "mv_cold_item_exposure_7d",
                        "mv_avg_rating_weekly",
                        "mv_precision_at_k_latest");
    }

    @Test
    void allSixMvsHaveUniqueIndexEnablingConcurrentRefresh() throws SQLException {
        // Each MV must have a unique index for REFRESH CONCURRENTLY to work.
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement();
                var rs = s.executeQuery(
                        "SELECT t.relname AS matview_name"
                                + " FROM pg_class t"
                                + " JOIN pg_namespace n ON n.oid = t.relnamespace"
                                + " WHERE n.nspname = 'plrs_dw'"
                                + "   AND t.relkind = 'm'"
                                + "   AND EXISTS ("
                                + "       SELECT 1 FROM pg_index i"
                                + "       WHERE i.indrelid = t.oid AND i.indisunique"
                                + "   )")) {
            Set<String> withUniqueIdx = new HashSet<>();
            while (rs.next()) {
                withUniqueIdx.add(rs.getString("matview_name"));
            }
            assertThat(withUniqueIdx)
                    .as("every MV must carry a unique index for CONCURRENT refresh")
                    .containsAll(
                            Set.of(
                                    "mv_coverage_7d",
                                    "mv_ctr_7d",
                                    "mv_completion_rate_30d",
                                    "mv_cold_item_exposure_7d",
                                    "mv_avg_rating_weekly",
                                    "mv_precision_at_k_latest"));
        }
    }

    @Test
    void initialRefreshOnEmptyTablesSucceeds() throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement()) {
            // First REFRESH cannot use CONCURRENTLY — pg_matviews populates
            // their data only after a regular REFRESH.
            for (String mv :
                    java.util.List.of(
                            "mv_coverage_7d",
                            "mv_ctr_7d",
                            "mv_completion_rate_30d",
                            "mv_cold_item_exposure_7d",
                            "mv_avg_rating_weekly",
                            "mv_precision_at_k_latest")) {
                s.execute("REFRESH MATERIALIZED VIEW plrs_dw." + mv);
            }
            // Now CONCURRENTLY works.
            for (String mv :
                    java.util.List.of(
                            "mv_coverage_7d",
                            "mv_ctr_7d",
                            "mv_completion_rate_30d",
                            "mv_cold_item_exposure_7d",
                            "mv_avg_rating_weekly",
                            "mv_precision_at_k_latest")) {
                s.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY plrs_dw." + mv);
            }
        }
    }

    @SpringBootApplication(
            exclude = {
                HibernateJpaAutoConfiguration.class,
                JpaRepositoriesAutoConfiguration.class,
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    static class KpiITApp {}
}
