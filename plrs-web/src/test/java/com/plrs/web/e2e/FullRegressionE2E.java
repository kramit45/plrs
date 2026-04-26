package com.plrs.web.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.options.AriaRole;
import com.plrs.application.admin.RecomputeRecommender;
import com.plrs.infrastructure.admin.RefreshKpiViewsJob;
import java.sql.Connection;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.NestedTestConfiguration.EnclosingConfiguration;

/**
 * Single-class consolidated Playwright regression that walks the
 * student/admin flows from every iteration in declaration order:
 *
 * <ul>
 *   <li>Iter1 — register, login, signed-in home, logout
 *   <li>Iter2 — catalog browse, quiz attempt, dashboard radar
 *   <li>Iter3 — recommendations + admin recompute (cache-bust path)
 *   <li>Iter4 — path generate + step done + admin KPI dashboard
 * </ul>
 *
 * <p>Tests share state through the enclosing instance: a unique
 * {@code runId} prefix isolates fixtures per Maven invocation so
 * concurrent runs against the same DB don't collide. The student
 * registered in Iter1 is reused by Iter2/Iter3, so admin KPIs in Iter4
 * see the real activity that earlier nests produced.
 *
 * <p>This is the consolidated pre-release smoke. The per-iteration
 * Playwright tests ({@link RegisterLoginHomeIT}, {@link
 * StudentDemoFlowIT}, {@link RecommendationsRefreshE2E}, {@link
 * PathPlannerE2E}, {@link AdminDashboardE2E}) remain the canonical
 * narrow tests — they fail with a focused signal when one flow breaks.
 * This class fails with a single signal when something *between* the
 * flows breaks (shared state, role wiring, cache layering).
 *
 * <p>Gated by {@code E2E=true} for the same Gatekeeper / headless-Linux
 * reasons documented on the per-iter classes. CI runs it only on push
 * to {@code main}, not on PR — see {@code .github/workflows/build.yml}.
 *
 * <p>Traces to: §3.e (test strategy — Playwright as the highest-fidelity
 * E2E rung), all FRs covered by the underlying per-iter tests.
 */
@EnabledIfEnvironmentVariable(
        named = "E2E",
        matches = "true",
        disabledReason =
                "Playwright E2E is gated by E2E=true. macOS Chromium + Maven Gatekeeper"
                        + " issue applies; set E2E=true on Linux CI runners.")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@NestedTestConfiguration(EnclosingConfiguration.INHERIT)
@TestMethodOrder(OrderAnnotation.class)
class FullRegressionE2E extends PlaywrightTestBase {

    private static final String VALID_BCRYPT =
            "$2b$12$ugnHmpA0.P.D.duNnxF7vO42FPerFzWg4nqpaBzBsV7dbEwR36yFG";
    private static final String ADMIN_PASSWORD = "AdminPass01";
    private static final String STUDENT_PASSWORD = "StudentPass01";

    @Autowired private DataSource dataSource;
    @Autowired private RecomputeRecommender recomputeRecommender;
    @Autowired private RefreshKpiViewsJob refreshKpiViewsJob;

    /** Unique per-run prefix so concurrent regression runs don't collide. */
    private String runId;

    private String studentEmail;
    private String adminEmail;
    private UUID adminUuid;

    private long topicId;
    private long quizId;
    private long pathTopicId;

    @BeforeAll
    void seedSharedFixtures() {
        runId = "fr-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6);
        studentEmail = "student-" + runId + "@example.com";
        adminEmail = "admin-" + runId + "@example.com";
        adminUuid = UUID.randomUUID();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Admin user (seeded directly — the public /api/auth/register
        // path only assigns ROLE_STUDENT).
        jdbc.update(
                "INSERT INTO plrs_ops.users"
                        + " (id, email, password_hash, created_at, updated_at, created_by)"
                        + " VALUES (?, ?, ?, NOW(), NOW(), 'full-regression-seed')",
                adminUuid,
                adminEmail,
                VALID_BCRYPT);
        jdbc.update(
                "INSERT INTO plrs_ops.user_roles (user_id, role, assigned_at)"
                        + " VALUES (?, 'ADMIN', NOW())",
                adminUuid);

        // Topic + demo quiz the student will attempt in Iter2.
        topicId =
                jdbc.queryForObject(
                        "INSERT INTO plrs_ops.topics (topic_name, description, created_by)"
                                + " VALUES (?, 'Full-regression topic', 'full-regression-seed')"
                                + " RETURNING topic_id",
                        Long.class,
                        "FR Topic " + runId);

        quizId =
                jdbc.queryForObject(
                        "INSERT INTO plrs_ops.content"
                                + " (topic_id, title, ctype, difficulty,"
                                + "  est_minutes, url, description, created_at, updated_at)"
                                + " VALUES (?, ?, 'QUIZ', 'BEGINNER', 5,"
                                + "         'https://example.com/fr-quiz',"
                                + "         'Full-regression demo quiz', NOW(), NOW())"
                                + " RETURNING content_id",
                        Long.class,
                        topicId,
                        "FR Quiz " + runId);

        jdbc.update(
                "INSERT INTO plrs_ops.quiz_items"
                        + " (content_id, item_order, topic_id, stem, explanation)"
                        + " VALUES (?, 1, ?, 'Pick A', 'A is correct')",
                quizId,
                topicId);

        jdbc.update(
                "INSERT INTO plrs_ops.quiz_item_options"
                        + " (content_id, item_order, option_order, option_text, is_correct)"
                        + " VALUES (?, 1, 1, 'Option A (correct)', TRUE),"
                        + "        (?, 1, 2, 'Option B (wrong)',   FALSE)",
                quizId,
                quizId);

        // A second topic with two non-quiz items so the path planner has
        // something to plan over in Iter4.
        pathTopicId =
                jdbc.queryForObject(
                        "INSERT INTO plrs_ops.topics (topic_name, description, created_by)"
                                + " VALUES (?, 'Full-regression path target', 'full-regression-seed')"
                                + " RETURNING topic_id",
                        Long.class,
                        "FR Path Target " + runId);

        for (int i = 1; i <= 2; i++) {
            jdbc.update(
                    "INSERT INTO plrs_ops.content"
                            + " (topic_id, title, ctype, difficulty, est_minutes, url)"
                            + " VALUES (?, ?, 'ARTICLE', 'BEGINNER', 5, ?)",
                    pathTopicId,
                    "FR Path Item " + i + " " + runId,
                    "https://example.com/fr-path-" + i + "-" + runId);
        }
    }

    /** Iter 1 — register / login / authenticated home / logout. */
    @Nested
    @TestMethodOrder(OrderAnnotation.class)
    @Order(1)
    class Iter1 {

        @Test
        @Order(1)
        void registerLoginThenLogout() {
            // Register the shared studentEmail. Other nests will reuse
            // this same identity so quiz / mastery / path state
            // accumulates across the regression.
            page.navigate(baseUrl() + "/register");
            page.locator("input[name=email]").fill(studentEmail);
            page.locator("input[name=password]").fill(STUDENT_PASSWORD);
            page.getByRole(
                            AriaRole.BUTTON,
                            new com.microsoft.playwright.Page.GetByRoleOptions().setName("Register"))
                    .click();
            page.waitForURL(Pattern.compile(".*/login\\?registered.*"));

            page.locator("input[name=username]").fill(studentEmail);
            page.locator("input[name=password]").fill(STUDENT_PASSWORD);
            page.getByRole(
                            AriaRole.BUTTON,
                            new com.microsoft.playwright.Page.GetByRoleOptions().setName("Log in"))
                    .click();
            page.waitForURL(baseUrl() + "/");
            assertThat(page.locator("body")).containsText("Signed in as " + studentEmail);
            assertThat(page.locator("body")).containsText("ROLE_STUDENT");

            page.getByRole(
                            AriaRole.BUTTON,
                            new com.microsoft.playwright.Page.GetByRoleOptions().setName("Log out"))
                    .first()
                    .click();
            page.waitForURL(Pattern.compile(".*/login\\?logout.*"));
        }
    }

    /** Iter 2 — login, browse the catalog, attempt the seeded quiz, see the dashboard. */
    @Nested
    @TestMethodOrder(OrderAnnotation.class)
    @Order(2)
    class Iter2 {

        @Test
        @Order(1)
        void browseCatalogAttemptQuizThenDashboard() {
            loginAsStudent();

            page.navigate(baseUrl() + "/catalog/" + quizId);
            assertThat(page.locator("h1")).containsText("FR Quiz " + runId);

            page.getByRole(
                            AriaRole.LINK,
                            new com.microsoft.playwright.Page.GetByRoleOptions()
                                    .setName("Attempt this quiz"))
                    .click();
            page.waitForURL(Pattern.compile(".*/quiz/" + quizId + "/attempt$"));

            page.locator("input[type=radio]").first().check();
            page.getByRole(
                            AriaRole.BUTTON,
                            new com.microsoft.playwright.Page.GetByRoleOptions().setName("Submit Quiz"))
                    .click();
            assertThat(page.locator("h2")).containsText("100.00");

            page.getByRole(
                            AriaRole.LINK,
                            new com.microsoft.playwright.Page.GetByRoleOptions()
                                    .setName("Go to dashboard"))
                    .click();
            page.waitForURL(baseUrl() + "/dashboard");
            assertThat(page.locator("canvas#masteryRadar")).isVisible();
            assertThat(page.locator("table")).containsText("FR Quiz " + runId);
        }
    }

    /** Iter 3 — admin triggers a recompute, student sees a non-empty slate. */
    @Nested
    @TestMethodOrder(OrderAnnotation.class)
    @Order(3)
    class Iter3 {

        @Test
        @Order(1)
        void adminRecomputeThenStudentSeesRecommendations() {
            // Drive the recompute job synchronously rather than minting
            // an admin browser session purely for the trigger endpoint
            // (the per-iter test exercises the HTTP path; here the bean
            // shortcut keeps the regression fast).
            recomputeRecommender.recomputeNow();

            loginAsStudent();
            page.navigate(baseUrl() + "/dashboard");
            page.waitForFunction(
                    "() => document.querySelectorAll('#recommendations li').length > 0");
            Assertions.assertThat(page.locator("#recommendations li").count())
                    .as("post-quiz dashboard should surface at least one recommendation")
                    .isGreaterThan(0);
        }
    }

    /** Iter 4 — student plans a path, then admin sees populated KPIs. */
    @Nested
    @TestMethodOrder(OrderAnnotation.class)
    @Order(4)
    class Iter4 {

        @Test
        @Order(1)
        void studentGeneratesPathAndMarksFirstStepDone() {
            loginAsStudent();

            page.navigate(baseUrl() + "/path/generate");
            page.locator("select[name=targetTopicId]")
                    .selectOption(String.valueOf(pathTopicId));
            page.getByRole(
                            AriaRole.BUTTON,
                            new com.microsoft.playwright.Page.GetByRoleOptions().setName("Plan path"))
                    .click();
            page.waitForURL(Pattern.compile(".*/path/\\d+$"));
            assertThat(page.locator("#path-steps")).isVisible();

            page.getByRole(
                            AriaRole.BUTTON,
                            new com.microsoft.playwright.Page.GetByRoleOptions().setName("Mark done"))
                    .first()
                    .click();
            page.waitForURL(Pattern.compile(".*/path/\\d+$"));
            assertThat(page.locator(".badge").first()).containsText("DONE");
        }

        @Test
        @Order(2)
        void adminDashboardShowsPopulatedKpis() throws Exception {
            // Push a synthetic warehouse row + refresh MVs so the KPI
            // tiles have something to render. (The student flow above
            // produces operational rows; the warehouse pipeline that
            // mirrors them runs out-of-band, so we shortcut it here.)
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            int dateSk =
                    Integer.parseInt(
                            java.time.LocalDate.now()
                                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")));
            jdbc.update(
                    "INSERT INTO plrs_dw.dim_user (user_id) VALUES (?)"
                            + " ON CONFLICT DO NOTHING",
                    adminUuid);
            jdbc.update(
                    "INSERT INTO plrs_dw.dim_topic (topic_id, topic_name) VALUES (?, ?)"
                            + " ON CONFLICT DO NOTHING",
                    topicId,
                    "FR Topic " + runId);
            jdbc.update(
                    "INSERT INTO plrs_dw.dim_content"
                            + " (content_id, title, ctype, difficulty, est_minutes, topic_id)"
                            + " VALUES (?, ?, 'QUIZ', 'BEGINNER', 5, ?)"
                            + " ON CONFLICT DO NOTHING",
                    quizId,
                    "FR Quiz " + runId,
                    topicId);
            try (Connection ignored = dataSource.getConnection()) {
                jdbc.update(
                        "INSERT INTO plrs_dw.fact_recommendation"
                                + " (date_sk, user_sk, content_sk, topic_sk, created_at, score,"
                                + "  rank_position, was_clicked, was_completed)"
                                + " SELECT ?, u.user_sk, c.content_sk, t.topic_sk, NOW(),"
                                + "        0.9, 1, TRUE, TRUE"
                                + "   FROM plrs_dw.dim_user u, plrs_dw.dim_content c, plrs_dw.dim_topic t"
                                + "  WHERE u.user_id = ? AND c.content_id = ? AND t.topic_id = ?"
                                + " ON CONFLICT DO NOTHING",
                        dateSk,
                        adminUuid,
                        quizId,
                        topicId);
            }
            refreshKpiViewsJob.refreshNow();

            // Form-login as admin in this fresh browser context.
            page.navigate(baseUrl() + "/login");
            page.locator("input[name=username]").fill(adminEmail);
            page.locator("input[name=password]").fill(ADMIN_PASSWORD);
            page.getByRole(
                            AriaRole.BUTTON,
                            new com.microsoft.playwright.Page.GetByRoleOptions().setName("Log in"))
                    .click();
            page.waitForURL(baseUrl() + "/");

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
            assertThat(page.locator("#kpi-coverage")).containsText("%");

            page.navigate(baseUrl() + "/admin");
            assertThat(page.locator("body")).containsText("Run Evaluation");
        }
    }

    /** Shared helper: form-login as the regression-wide student. */
    private void loginAsStudent() {
        page.navigate(baseUrl() + "/login");
        page.locator("input[name=username]").fill(studentEmail);
        page.locator("input[name=password]").fill(STUDENT_PASSWORD);
        page.getByRole(
                        AriaRole.BUTTON,
                        new com.microsoft.playwright.Page.GetByRoleOptions().setName("Log in"))
                .click();
        page.waitForURL(baseUrl() + "/");
    }
}
