package com.plrs.infrastructure.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.plrs.application.admin.KpiService;
import com.plrs.infrastructure.testsupport.PostgresTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
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

/**
 * Integration test for {@link KpiService}: seeds tiny fixtures into the
 * warehouse fact tables, refreshes every MV, then asserts the service
 * returns the expected shape from each view.
 *
 * <p>Lives in plrs-infrastructure (where PostgresTestBase + the
 * migrations are) — plrs-application can't depend on plrs-infrastructure
 * without a reactor cycle. Same pattern as the other application-service
 * ITs.
 *
 * <p>Traces to: FR-36, §3.c.2.4.
 */
@SpringBootTest(
        classes = KpiServiceIT.KpiITApp.class,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.default-schema=plrs_ops",
            "spring.flyway.schemas=plrs_ops,plrs_dw",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false"
        })
class KpiServiceIT extends PostgresTestBase {

    @Autowired private DataSource dataSource;
    @Autowired private KpiService kpiService;

    @BeforeEach
    void cleanAndRefresh() throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement()) {
            s.execute("TRUNCATE plrs_dw.fact_recommendation");
            s.execute("TRUNCATE plrs_dw.fact_interaction");
            s.execute("DELETE FROM plrs_dw.fact_eval_run");
            s.execute("DELETE FROM plrs_dw.dim_content");
            s.execute("DELETE FROM plrs_dw.dim_user");
            s.execute("DELETE FROM plrs_dw.dim_topic");
        }
    }

    private void refreshAll() throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement()) {
            for (String mv :
                    new String[] {
                        "mv_coverage_7d",
                        "mv_ctr_7d",
                        "mv_completion_rate_30d",
                        "mv_cold_item_exposure_7d",
                        "mv_avg_rating_weekly",
                        "mv_precision_at_k_latest"
                    }) {
                s.execute("REFRESH MATERIALIZED VIEW plrs_dw." + mv);
            }
        }
    }

    @Test
    void emptyWarehouseProducesZeros() throws SQLException {
        refreshAll();

        assertThat(kpiService.getCoverage7d().value()).isEqualByComparingTo("0");
        assertThat(kpiService.getCtr7d().value()).isEqualByComparingTo("0");
        assertThat(kpiService.getColdItemExposure7d().value()).isEqualByComparingTo("0");
        assertThat(kpiService.getCompletionRateLast30Days()).isEmpty();
        assertThat(kpiService.getAvgRatingWeekly()).isEmpty();
        assertThat(kpiService.getLatestEvalMetrics()).isEmpty();
    }

    @Test
    void seededRecommendationsShowUpInCoverageAndCtr() throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement()) {
            s.execute(
                    "INSERT INTO plrs_dw.dim_user (user_id) VALUES"
                            + " ('11111111-1111-1111-1111-111111111111')");
            s.execute(
                    "INSERT INTO plrs_dw.dim_topic (topic_id, topic_name) VALUES (1, 'Algebra')");
            s.execute(
                    "INSERT INTO plrs_dw.dim_content (content_id, title, ctype, difficulty,"
                            + " est_minutes, topic_id) VALUES (101, 'A', 'VIDEO', 'BEGINNER', 5, 1)");
            s.execute(
                    "INSERT INTO plrs_dw.dim_content (content_id, title, ctype, difficulty,"
                            + " est_minutes, topic_id) VALUES (102, 'B', 'VIDEO', 'BEGINNER', 5, 1)");
            int dateSk =
                    Integer.parseInt(
                            java.time.LocalDate.now()
                                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")));
            s.execute(
                    "INSERT INTO plrs_dw.fact_recommendation"
                            + " (date_sk, user_sk, content_sk, topic_sk, created_at, score, rank_position,"
                            + "  was_clicked, was_completed)"
                            + " SELECT " + dateSk + ", u.user_sk, c.content_sk, t.topic_sk, NOW(),"
                            + " 0.9, 1, TRUE, FALSE FROM plrs_dw.dim_user u, plrs_dw.dim_content c,"
                            + " plrs_dw.dim_topic t WHERE c.content_id = 101 AND t.topic_id = 1");
            s.execute(
                    "INSERT INTO plrs_dw.fact_recommendation"
                            + " (date_sk, user_sk, content_sk, topic_sk, created_at, score, rank_position,"
                            + "  was_clicked, was_completed)"
                            + " SELECT " + dateSk + ", u.user_sk, c.content_sk, t.topic_sk, NOW(),"
                            + " 0.7, 2, FALSE, FALSE FROM plrs_dw.dim_user u, plrs_dw.dim_content c,"
                            + " plrs_dw.dim_topic t WHERE c.content_id = 102 AND t.topic_id = 1");
        }
        refreshAll();

        // 2 distinct served / 2 catalogue items = 1.0 coverage.
        assertThat(kpiService.getCoverage7d().value().doubleValue()).isEqualTo(1.0);
        // 1 of 2 clicked = 0.5 CTR.
        assertThat(kpiService.getCtr7d().value().doubleValue()).isEqualTo(0.5);
    }

    @Test
    void seededEvalRunShowsUpInLatestMetrics() throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement()) {
            s.execute(
                    "INSERT INTO plrs_dw.fact_eval_run"
                            + " (ran_at, variant_name, k, precision_at_k, ndcg_at_k, coverage)"
                            + " VALUES (NOW(), 'cf_v1', 10, 0.4500, 0.5500, 0.6500)");
        }
        refreshAll();

        var metrics = kpiService.getLatestEvalMetrics();
        assertThat(metrics).hasSize(1);
        assertThat(metrics.get(0).variantName()).isEqualTo("cf_v1");
        assertThat(metrics.get(0).precisionAtK().doubleValue()).isEqualTo(0.45);
        assertThat(metrics.get(0).ranAt()).isBefore(Instant.now().plusSeconds(60));
    }

    @SpringBootApplication(
            scanBasePackages = "com.plrs.application.admin",
            exclude = {
                HibernateJpaAutoConfiguration.class,
                JpaRepositoriesAutoConfiguration.class,
                RedisAutoConfiguration.class,
                RedisRepositoriesAutoConfiguration.class
            })
    static class KpiITApp {

        // KpiService is a @Service so component scan picks it up; no extra wiring needed.
    }
}
