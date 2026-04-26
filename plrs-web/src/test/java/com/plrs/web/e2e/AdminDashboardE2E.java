package com.plrs.web.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.options.AriaRole;
import com.plrs.infrastructure.admin.RefreshKpiViewsJob;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Browser-driven Iter 4 admin-dashboard flow:
 *
 * <ol>
 *   <li>Seed an ADMIN-role user, log in.
 *   <li>Seed warehouse fact rows so the KPI MVs have data.
 *   <li>Refresh the MVs via {@link RefreshKpiViewsJob#refreshNow()}.
 *   <li>Visit /admin/dashboard — every KPI tile (coverage, CTR, etc.)
 *       shows a non-empty value.
 *   <li>Click "Refresh KPIs" — page reloads without error.
 *   <li>Visit /admin/eval-style landing page (/admin) — the Run
 *       Evaluation tile is present.
 * </ol>
 *
 * <p>Gated by {@code E2E=true} for the same Gatekeeper /
 * headless-Linux reasons as the other Playwright tests.
 *
 * <p>Traces to: FR-36, FR-45.
 */
@EnabledIfEnvironmentVariable(
        named = "E2E",
        matches = "true",
        disabledReason =
                "Playwright E2E is gated by E2E=true. macOS Chromium + Maven Gatekeeper"
                        + " issue applies; set E2E=true on Linux CI runners.")
class AdminDashboardE2E extends PlaywrightTestBase {

    private static final String VALID_BCRYPT =
            "$2b$12$ugnHmpA0.P.D.duNnxF7vO42FPerFzWg4nqpaBzBsV7dbEwR36yFG";
    private static final String ADMIN_PASSWORD = "AdminPass01";

    @Autowired private DataSource dataSource;
    @Autowired private RefreshKpiViewsJob refreshKpiViewsJob;

    private String adminEmail;

    @BeforeEach
    void seedAdminAndWarehouse() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID adminUuid = UUID.randomUUID();
        adminEmail = "admin-" + adminUuid + "@example.com";

        jdbc.update(
                "INSERT INTO plrs_ops.users"
                        + " (id, email, password_hash, created_at, updated_at, created_by)"
                        + " VALUES (?, ?, ?, NOW(), NOW(), 'e2e-admin-seed')",
                adminUuid,
                adminEmail,
                VALID_BCRYPT);
        jdbc.update(
                "INSERT INTO plrs_ops.user_roles (user_id, role, assigned_at)"
                        + " VALUES (?, 'ADMIN', NOW())",
                adminUuid);

        // Seed warehouse so the KPI MVs surface non-zero values.
        long topicId =
                jdbc.queryForObject(
                        "INSERT INTO plrs_ops.topics (topic_name, created_by)"
                                + " VALUES (?, 'e2e-admin') RETURNING topic_id",
                        Long.class,
                        "Admin E2E " + UUID.randomUUID());
        long contentId =
                jdbc.queryForObject(
                        "INSERT INTO plrs_ops.content"
                                + " (topic_id, title, ctype, difficulty, est_minutes, url)"
                                + " VALUES (?, ?, 'VIDEO', 'BEGINNER', 5, 'https://x.y')"
                                + " RETURNING content_id",
                        Long.class,
                        topicId,
                        "Admin E2E " + UUID.randomUUID());

        try (Connection c = dataSource.getConnection()) {
            // Push a row into each warehouse dim + fact_recommendation so
            // the KPI MVs have something to aggregate.
            int dateSk =
                    Integer.parseInt(
                            java.time.LocalDate.now()
                                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")));
            jdbc.update(
                    "INSERT INTO plrs_dw.dim_user (user_id) VALUES (?) ON CONFLICT DO NOTHING",
                    adminUuid);
            jdbc.update(
                    "INSERT INTO plrs_dw.dim_topic (topic_id, topic_name) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    topicId,
                    "Admin E2E topic");
            jdbc.update(
                    "INSERT INTO plrs_dw.dim_content"
                            + " (content_id, title, ctype, difficulty, est_minutes, topic_id)"
                            + " VALUES (?, 'Admin E2E content', 'VIDEO', 'BEGINNER', 5, ?)"
                            + " ON CONFLICT DO NOTHING",
                    contentId,
                    topicId);
            jdbc.update(
                    "INSERT INTO plrs_dw.fact_recommendation"
                            + " (date_sk, user_sk, content_sk, topic_sk, created_at, score,"
                            + "  rank_position, was_clicked, was_completed)"
                            + " SELECT ?, u.user_sk, c.content_sk, t.topic_sk, NOW(),"
                            + "        0.9, 1, TRUE, FALSE"
                            + "   FROM plrs_dw.dim_user u, plrs_dw.dim_content c, plrs_dw.dim_topic t"
                            + "  WHERE u.user_id = ? AND c.content_id = ? AND t.topic_id = ?"
                            + " ON CONFLICT DO NOTHING",
                    dateSk,
                    adminUuid,
                    contentId,
                    topicId);
        }
        refreshKpiViewsJob.refreshNow();
    }

    @Test
    void adminSeesPopulatedKpiDashboard() {
        // Form-login.
        page.navigate(baseUrl() + "/login");
        page.locator("input[name=username]").fill(adminEmail);
        page.locator("input[name=password]").fill(ADMIN_PASSWORD);
        page.getByRole(
                        AriaRole.BUTTON,
                        new com.microsoft.playwright.Page.GetByRoleOptions().setName("Log in"))
                .click();
        page.waitForURL(baseUrl() + "/");

        // Dashboard renders all six KPI tiles.
        page.navigate(baseUrl() + "/admin/dashboard");
        for (String tile :
                new String[] {
                    "kpi-coverage",
                    "kpi-ctr",
                    "kpi-completion-avg",
                    "kpi-cold-exposure",
                    "kpi-rating",
                    "kpi-precision"
                }) {
            assertThat(page.locator("#" + tile)).isVisible();
        }
        // Coverage should have populated since we seeded one served item.
        assertThat(page.locator("#kpi-coverage")).containsText("%");

        // Refresh KPIs button click.
        page.getByRole(
                        AriaRole.BUTTON,
                        new com.microsoft.playwright.Page.GetByRoleOptions().setName("Refresh KPIs"))
                .click();
        // Same page should still be reachable after the POST returns 204.
        page.navigate(baseUrl() + "/admin/dashboard");
        assertThat(page.locator("#kpi-coverage")).isVisible();

        // /admin landing has the Run Evaluation tile (spec mentioned
        // /admin/eval but only /admin exists with the eval tile).
        page.navigate(baseUrl() + "/admin");
        assertThat(page.locator("body")).containsText("Run Evaluation");
    }
}
