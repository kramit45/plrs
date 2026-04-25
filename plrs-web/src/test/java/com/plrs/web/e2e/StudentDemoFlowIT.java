package com.plrs.web.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.options.AriaRole;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Browser-driven end-to-end exercising the full FR-35 student demo flow:
 *
 * <ol>
 *   <li>Register a fresh student.
 *   <li>Form-login.
 *   <li>Browse the catalogue, open the seeded demo quiz.
 *   <li>Click "Attempt this quiz".
 *   <li>Pick the correct option, submit.
 *   <li>Land on the result page, click "Go to dashboard".
 *   <li>Observe the mastery radar canvas and the recent-activity tables.
 * </ol>
 *
 * <p>Every click drives the real Spring stack: the form-login chain, the
 * catalogue + quiz controllers, the SubmitQuizAttemptUseCase TX-01 path
 * (mastery upsert + version bump + outbox), the Auditable aspect, the
 * Thymeleaf render, the DashboardController, and the
 * {@code /web-api/me/activity-weekly} JSON helper that the dashboard JS
 * calls back to. The seed for the demo quiz happens in-test via
 * {@link JdbcTemplate} so the test owns its fixture lifecycle.
 *
 * <p>Gated by {@code E2E=true} for the same Gatekeeper / headless-Linux
 * reasons as {@link RegisterLoginHomeIT}.
 */
@EnabledIfEnvironmentVariable(
        named = "E2E",
        matches = "true",
        disabledReason =
                "Playwright E2E is gated by E2E=true. On macOS the downloaded Chromium can be"
                        + " quarantined by Gatekeeper and fails to launch from Maven; set E2E=true"
                        + " on Linux CI runners (or after running `xattr -cr"
                        + " ~/Library/Caches/ms-playwright` locally).")
class StudentDemoFlowIT extends PlaywrightTestBase {

    @Autowired private DataSource dataSource;

    private long seededQuizId;

    @BeforeEach
    void seedDemoQuiz() {
        // Each test gets its own quiz under a fresh-named topic so the
        // unique (topic_id, title) constraint never collides across runs.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Long topicId =
                jdbc.queryForObject(
                        "INSERT INTO plrs_ops.topics (topic_name, description, created_by)"
                                + " VALUES (?, 'E2E demo topic', 'e2e-seed')"
                                + " RETURNING topic_id",
                        Long.class,
                        "E2E Demo Topic " + suffix);

        seededQuizId =
                jdbc.queryForObject(
                        "INSERT INTO plrs_ops.content"
                                + " (topic_id, title, ctype, difficulty,"
                                + "  est_minutes, url, description, created_at, updated_at)"
                                + " VALUES (?, ?, 'QUIZ', 'BEGINNER', 5,"
                                + "         'https://example.com/e2e-quiz',"
                                + "         'E2E demo quiz', NOW(), NOW())"
                                + " RETURNING content_id",
                        Long.class,
                        topicId,
                        "E2E Demo Quiz " + suffix);

        jdbc.update(
                "INSERT INTO plrs_ops.quiz_items"
                        + " (content_id, item_order, topic_id, stem, explanation)"
                        + " VALUES (?, 1, ?, 'E2E demo: pick option A',"
                        + "         'A is correct')",
                seededQuizId,
                topicId);

        jdbc.update(
                "INSERT INTO plrs_ops.quiz_item_options"
                        + " (content_id, item_order, option_order, option_text, is_correct)"
                        + " VALUES (?, 1, 1, 'Option A (correct)', TRUE),"
                        + "        (?, 1, 2, 'Option B (wrong)',   FALSE)",
                seededQuizId,
                seededQuizId);
    }

    @Test
    void fullStudentFlowFromRegistrationToDashboardMasteryRadar() {
        String email = "demo-" + UUID.randomUUID() + "@example.com";
        String password = "DemoPass01";

        // 1. Register.
        page.navigate(baseUrl() + "/register");
        page.locator("input[name=email]").fill(email);
        page.locator("input[name=password]").fill(password);
        page.getByRole(
                        AriaRole.BUTTON,
                        new com.microsoft.playwright.Page.GetByRoleOptions().setName("Register"))
                .click();
        page.waitForURL(Pattern.compile(".*/login\\?registered.*"));

        // 2. Login.
        page.locator("input[name=username]").fill(email);
        page.locator("input[name=password]").fill(password);
        page.getByRole(
                        AriaRole.BUTTON,
                        new com.microsoft.playwright.Page.GetByRoleOptions().setName("Log in"))
                .click();
        page.waitForURL(baseUrl() + "/");

        // 3. Open the seeded quiz directly via its detail page (the
        //    catalogue search index uses tsvector and may take a moment
        //    to refresh; bypassing the listing makes the test
        //    deterministic without weakening coverage of the rest of
        //    the flow).
        page.navigate(baseUrl() + "/catalog/" + seededQuizId);
        assertThat(page.locator("h1")).containsText("E2E Demo Quiz");

        // 4. Click "Attempt this quiz".
        page.getByRole(
                        AriaRole.LINK,
                        new com.microsoft.playwright.Page.GetByRoleOptions()
                                .setName("Attempt this quiz"))
                .click();
        page.waitForURL(Pattern.compile(".*/quiz/" + seededQuizId + "/attempt$"));

        // 5. Pick option A (correct), submit.
        page.locator("input[type=radio]").first().check();
        page.getByRole(
                        AriaRole.BUTTON,
                        new com.microsoft.playwright.Page.GetByRoleOptions()
                                .setName("Submit Quiz"))
                .click();

        // 6. Land on the result page, score = 100%.
        assertThat(page.locator("h2")).containsText("You scored");
        assertThat(page.locator("h2")).containsText("100.00");

        // 7. Click "Go to dashboard".
        page.getByRole(
                        AriaRole.LINK,
                        new com.microsoft.playwright.Page.GetByRoleOptions()
                                .setName("Go to dashboard"))
                .click();
        page.waitForURL(baseUrl() + "/dashboard");

        // 8. Mastery radar canvas is rendered and the recent-attempts
        //    table includes the demo quiz title.
        assertThat(page.locator("canvas#masteryRadar")).isVisible();
        assertThat(page.locator("table")).containsText("E2E Demo Quiz");
    }
}
