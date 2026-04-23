package com.plrs.web.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.options.AriaRole;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * End-to-end smoke covering the browser-driven registration flow that
 * backs the Iter 1 web UI:
 *
 * <ol>
 *   <li>Navigate to the registration form.
 *   <li>Submit email + password; the use case persists the aggregate.
 *   <li>Redirect to {@code /login?registered} with the success banner.
 *   <li>Submit the same credentials through form login.
 *   <li>Land at {@code /} signed in — nav shows the user, body echoes them.
 *   <li>Click the logout button; land at {@code /login?logout}.
 * </ol>
 *
 * <p>Every click goes through the real stack — Spring Security's filter
 * chains, real Flyway-migrated Postgres, real Redis for the refresh
 * allow-list. The single test catches regressions that slice tests
 * can't: the layout's sec:authorize directives, the formLogin wiring,
 * CSRF token propagation, the logout filter, Thymeleaf rendering of
 * flash messages.
 */
@EnabledIfEnvironmentVariable(
        named = "E2E",
        matches = "true",
        disabledReason =
                "Playwright E2E is gated by E2E=true. On macOS the downloaded Chromium can be"
                        + " quarantined by Gatekeeper and fails to launch from Maven; set E2E=true"
                        + " on Linux CI runners (or after running `xattr -cr"
                        + " ~/Library/Caches/ms-playwright` locally).")
class RegisterLoginHomeIT extends PlaywrightTestBase {

    @Test
    void fullRegistrationLoginThenHomeShowsAuthenticatedNav() {
        String email = "e2e-" + UUID.randomUUID() + "@example.com";
        String password = "Password01";

        // 1. Register.
        page.navigate(baseUrl() + "/register");
        page.locator("input[name=email]").fill(email);
        page.locator("input[name=password]").fill(password);
        page.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Page.GetByRoleOptions()
                        .setName("Register"))
                .click();

        // 2. Lands on /login?registered.
        page.waitForURL(Pattern.compile(".*/login\\?registered.*"));
        assertThat(page.locator(".alert-success")).containsText("please log in");

        // 3. Log in with the same credentials.
        page.locator("input[name=username]").fill(email);
        page.locator("input[name=password]").fill(password);
        page.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Page.GetByRoleOptions()
                        .setName("Log in"))
                .click();

        // 4. Home page shows signed-in state.
        page.waitForURL(baseUrl() + "/");
        assertThat(page.locator("body")).containsText("Signed in as " + email);
        assertThat(page.locator("body")).containsText("ROLE_STUDENT");

        // 5. Log out via the inline nav form; land on /login?logout.
        page.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Page.GetByRoleOptions()
                        .setName("Log out"))
                .first()
                .click();
        page.waitForURL(Pattern.compile(".*/login\\?logout.*"));
        assertThat(page.locator(".alert-info")).containsText("logged out");
    }
}
