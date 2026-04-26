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
 * Browser-driven Iter 4 path-planner flow:
 *
 * <ol>
 *   <li>Register + login as a fresh student.
 *   <li>Visit /path/generate, pick the seeded target topic, submit.
 *   <li>Land on /path/{id} — the step list is rendered.
 *   <li>Click "Mark done" on step 1.
 *   <li>Step 1's status badge flips to DONE.
 *   <li>Visit /dashboard — the active-path card shows progress 1/N.
 * </ol>
 *
 * <p>Drives the real {@link com.plrs.web.path.PathController},
 * {@link com.plrs.web.path.PathViewController}, GeneratePathUseCase
 * (TX-10), MarkPathStepDoneUseCase, the dashboard's active-path
 * loader, and the FR-31 path planner.
 *
 * <p>Gated by {@code E2E=true} for the same Gatekeeper / headless-Linux
 * reasons as the other Playwright tests in this module.
 *
 * <p>Traces to: FR-31..FR-35.
 */
@EnabledIfEnvironmentVariable(
        named = "E2E",
        matches = "true",
        disabledReason =
                "Playwright E2E is gated by E2E=true. macOS Chromium + Maven Gatekeeper"
                        + " issue applies; set E2E=true on Linux CI runners.")
class PathPlannerE2E extends PlaywrightTestBase {

    @Autowired private DataSource dataSource;

    private long seededTopicId;
    private String topicName;

    @BeforeEach
    void seedTargetTopic() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        topicName = "Path E2E Target " + suffix;

        seededTopicId =
                jdbc.queryForObject(
                        "INSERT INTO plrs_ops.topics (topic_name, description, created_by)"
                                + " VALUES (?, 'Path E2E target', 'e2e-seed')"
                                + " RETURNING topic_id",
                        Long.class,
                        topicName);

        // Two non-quiz items so the planner has something to plan over.
        for (int i = 1; i <= 2; i++) {
            jdbc.update(
                    "INSERT INTO plrs_ops.content"
                            + " (topic_id, title, ctype, difficulty, est_minutes, url)"
                            + " VALUES (?, ?, 'ARTICLE', 'BEGINNER', 5, ?)",
                    seededTopicId,
                    "Path E2E Item " + i + " " + suffix,
                    "https://example.com/path-e2e-" + i + "-" + suffix);
        }
    }

    @Test
    void studentGeneratesPathThenMarksFirstStepDone() {
        String email = "path-" + UUID.randomUUID() + "@example.com";
        String password = "DemoPass01";

        // 1. Register + log in.
        page.navigate(baseUrl() + "/register");
        page.locator("input[name=email]").fill(email);
        page.locator("input[name=password]").fill(password);
        page.getByRole(
                        AriaRole.BUTTON,
                        new com.microsoft.playwright.Page.GetByRoleOptions().setName("Register"))
                .click();
        page.waitForURL(Pattern.compile(".*/login\\?registered.*"));
        page.locator("input[name=username]").fill(email);
        page.locator("input[name=password]").fill(password);
        page.getByRole(
                        AriaRole.BUTTON,
                        new com.microsoft.playwright.Page.GetByRoleOptions().setName("Log in"))
                .click();
        page.waitForURL(baseUrl() + "/");

        // 2. Generate a path on our seeded topic.
        page.navigate(baseUrl() + "/path/generate");
        // The topic select is keyed by topic_id; pick our seeded id.
        page.locator("select[name=targetTopicId]")
                .selectOption(String.valueOf(seededTopicId));
        page.getByRole(
                        AriaRole.BUTTON,
                        new com.microsoft.playwright.Page.GetByRoleOptions().setName("Plan path"))
                .click();

        // 3. Land on /path/{id} — assert step list rendered.
        page.waitForURL(Pattern.compile(".*/path/\\d+$"));
        assertThat(page.locator("#path-steps")).isVisible();
        assertThat(page.locator(".badge").first()).containsText("PENDING");

        // 4. Click "Mark done" on the first PENDING step.
        page.getByRole(
                        AriaRole.BUTTON,
                        new com.microsoft.playwright.Page.GetByRoleOptions().setName("Mark done"))
                .first()
                .click();

        // 5. After redirect back, step 1's badge should be DONE.
        page.waitForURL(Pattern.compile(".*/path/\\d+$"));
        assertThat(page.locator(".badge").first()).containsText("DONE");

        // 6. Dashboard shows the active-path card with progress.
        page.navigate(baseUrl() + "/dashboard");
        assertThat(page.locator("#active-path-card")).isVisible();
        assertThat(page.locator("#active-path-card")).containsText("/ 2 done");
    }
}
