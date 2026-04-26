package com.plrs.web.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
import com.plrs.application.admin.RecomputeRecommender;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Browser-driven end-to-end proving that the version-bust cache
 * invalidation actually surfaces different recommendations after a
 * quiz changes the learner's mastery (FR-25 + §2.e.2.3.3).
 *
 * <p>Setup seeds 8 content items across 2 topics:
 *
 * <ul>
 *   <li>T1: 1 BEGINNER quiz + 2 INTERMEDIATE non-quiz items
 *   <li>T2: 5 BEGINNER non-quiz items
 * </ul>
 *
 * <p>Pre-quiz the learner has zero mastery, so the FR-27 feasibility
 * filter drops the two T1-INTERMEDIATE items — only the five T2
 * BEGINNER items can surface. Submitting the T1 quiz with a perfect
 * score lifts the learner's T1 mastery above zero, which unlocks
 * INTERMEDIATE on T1 (rank 2 ≤ ceil(0.01·3)+1). The post-quiz slate
 * therefore includes the two T1 INTERMEDIATE titles that were
 * previously unreachable.
 *
 * <p>The cache assertion threads through the same flow: after
 * registering and visiting the dashboard the {@code rec:topN:{uuid}}
 * key is populated by the dashboard's same-origin XHR; the TX-04
 * post-commit hook on quiz submit clears it; the next dashboard visit
 * repopulates it. The admin-recompute trigger between submit and the
 * second dashboard visit is wired via the autowired
 * {@link RecomputeRecommender} bean rather than an HTTP call — using
 * the bean directly avoids minting an ADMIN session purely for the
 * Newman-style endpoint that already has its own controller test.
 *
 * <p>Gated by {@code E2E=true} for the same Gatekeeper / headless-Linux
 * reasons as {@link StudentDemoFlowIT}.
 *
 * <p>Traces to: FR-24, FR-25, FR-27, §2.e.2.3.3 (version-bust),
 * TX-04 (post-commit cache invalidation).
 */
@EnabledIfEnvironmentVariable(
        named = "E2E",
        matches = "true",
        disabledReason =
                "Playwright E2E is gated by E2E=true. On macOS the downloaded Chromium can be"
                        + " quarantined by Gatekeeper and fails to launch from Maven; set E2E=true"
                        + " on Linux CI runners (or after running `xattr -cr"
                        + " ~/Library/Caches/ms-playwright` locally).")
class RecommendationsRefreshE2E extends PlaywrightTestBase {

    @Autowired private DataSource dataSource;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RecomputeRecommender recomputeRecommender;

    private long seededQuizId;
    private String t1Intermediate1Title;
    private String t1Intermediate2Title;

    @BeforeEach
    void seedCatalogue() {
        // Each test run seeds under fresh-named topics so the unique
        // (topic_id, title) constraint never collides across runs.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Long topic1Id =
                jdbc.queryForObject(
                        "INSERT INTO plrs_ops.topics (topic_name, description, created_by)"
                                + " VALUES (?, 'E2E refresh topic T1', 'e2e-seed')"
                                + " RETURNING topic_id",
                        Long.class,
                        "Refresh T1 " + suffix);

        Long topic2Id =
                jdbc.queryForObject(
                        "INSERT INTO plrs_ops.topics (topic_name, description, created_by)"
                                + " VALUES (?, 'E2E refresh topic T2', 'e2e-seed')"
                                + " RETURNING topic_id",
                        Long.class,
                        "Refresh T2 " + suffix);

        // T1 quiz (BEGINNER) — the one the student will attempt.
        seededQuizId =
                jdbc.queryForObject(
                        "INSERT INTO plrs_ops.content"
                                + " (topic_id, title, ctype, difficulty,"
                                + "  est_minutes, url, description, created_at, updated_at)"
                                + " VALUES (?, ?, 'QUIZ', 'BEGINNER', 5,"
                                + "         'https://example.com/refresh-t1-quiz',"
                                + "         'E2E refresh demo quiz', NOW(), NOW())"
                                + " RETURNING content_id",
                        Long.class,
                        topic1Id,
                        "Refresh T1 Quiz " + suffix);

        jdbc.update(
                "INSERT INTO plrs_ops.quiz_items"
                        + " (content_id, item_order, topic_id, stem, explanation)"
                        + " VALUES (?, 1, ?, 'Pick A', 'A is correct')",
                seededQuizId,
                topic1Id);

        jdbc.update(
                "INSERT INTO plrs_ops.quiz_item_options"
                        + " (content_id, item_order, option_order, option_text, is_correct)"
                        + " VALUES (?, 1, 1, 'Option A (correct)', TRUE),"
                        + "        (?, 1, 2, 'Option B (wrong)',   FALSE)",
                seededQuizId,
                seededQuizId);

        // T1 non-quiz items at INTERMEDIATE — blocked by feasibility
        // until the learner gains some T1 mastery.
        t1Intermediate1Title = "Refresh T1 Intermediate A " + suffix;
        t1Intermediate2Title = "Refresh T1 Intermediate B " + suffix;
        insertArticle(jdbc, topic1Id, t1Intermediate1Title, "INTERMEDIATE");
        insertArticle(jdbc, topic1Id, t1Intermediate2Title, "INTERMEDIATE");

        // T2 BEGINNER non-quiz items — the cold-start surface that
        // appears in the pre-quiz slate.
        for (int i = 1; i <= 5; i++) {
            insertArticle(jdbc, topic2Id, "Refresh T2 Beginner " + i + " " + suffix, "BEGINNER");
        }
    }

    private static void insertArticle(
            JdbcTemplate jdbc, Long topicId, String title, String difficulty) {
        jdbc.update(
                "INSERT INTO plrs_ops.content"
                        + " (topic_id, title, ctype, difficulty,"
                        + "  est_minutes, url, description, created_at, updated_at)"
                        + " VALUES (?, ?, 'ARTICLE', ?, 7,"
                        + "         'https://example.com/refresh-article',"
                        + "         'E2E refresh demo article', NOW(), NOW())",
                topicId,
                title,
                difficulty);
    }

    @Test
    void recommendations_refresh_after_quiz() {
        String email = "refresh-" + UUID.randomUUID() + "@example.com";
        String password = "DemoPass01";

        // 1. Register + form-login.
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

        UUID userId = lookupUserId(email);
        String cacheKey = "rec:topN:" + userId;

        // 2. First dashboard visit — capture the cold-start slate.
        page.navigate(baseUrl() + "/dashboard");
        waitForRecommendationsLoaded();
        List<String> preTitles = currentRecommendationTitles();
        assertThat(preTitles)
                .as("cold-start slate should not include T1 INTERMEDIATE items "
                        + "(feasibility filter blocks them at zero mastery)")
                .doesNotContain(t1Intermediate1Title, t1Intermediate2Title);
        assertThat(redis.hasKey(cacheKey))
                .as("dashboard XHR should have populated the top-N cache")
                .isTrue();

        // 3. Take the T1 quiz with a perfect score.
        page.navigate(baseUrl() + "/catalog/" + seededQuizId);
        page.getByRole(
                        AriaRole.LINK,
                        new com.microsoft.playwright.Page.GetByRoleOptions()
                                .setName("Attempt this quiz"))
                .click();
        page.waitForURL(Pattern.compile(".*/quiz/" + seededQuizId + "/attempt$"));
        page.locator("input[type=radio]").first().check();
        page.getByRole(
                        AriaRole.BUTTON,
                        new com.microsoft.playwright.Page.GetByRoleOptions().setName("Submit Quiz"))
                .click();
        PlaywrightAssertions.assertThat(page.locator("h2")).containsText("100.00");

        // 4. Cache must be cleared by the TX-04 post-commit hook before
        //    we re-fetch the dashboard.
        assertThat(redis.hasKey(cacheKey))
                .as("TX-04 post-commit hook should have invalidated %s", cacheKey)
                .isFalse();

        // 5. Drive the recompute jobs synchronously so the slate
        //    reflects the new mastery without waiting on the cron.
        recomputeRecommender.recomputeNow();

        // 6. Re-visit the dashboard — recommendations should now
        //    include the T1 INTERMEDIATE items that were previously
        //    feasibility-blocked, and the cache key is back.
        page.navigate(baseUrl() + "/dashboard");
        waitForRecommendationsLoaded();
        List<String> postTitles = currentRecommendationTitles();
        assertThat(postTitles)
                .as("post-quiz slate should differ from the cold-start slate")
                .isNotEqualTo(preTitles);
        assertThat(Set.copyOf(postTitles))
                .as("T1 INTERMEDIATE items should now be reachable post-mastery")
                .contains(t1Intermediate1Title)
                .contains(t1Intermediate2Title);
        assertThat(redis.hasKey(cacheKey))
                .as("dashboard re-fetch should have repopulated %s", cacheKey)
                .isTrue();
    }

    private UUID lookupUserId(String email) {
        return new JdbcTemplate(dataSource)
                .queryForObject(
                        "SELECT id FROM plrs_ops.users WHERE email = ?", UUID.class, email);
    }

    /**
     * The dashboard JS replaces the loading paragraph with at least
     * one {@code <li>} once the XHR resolves; wait until that flip
     * completes so the snapshot is deterministic.
     */
    private void waitForRecommendationsLoaded() {
        page.waitForFunction(
                "() => document.querySelectorAll('#recommendations li').length > 0");
    }

    private List<String> currentRecommendationTitles() {
        return page.locator("#recommendations li a").allTextContents();
    }
}
