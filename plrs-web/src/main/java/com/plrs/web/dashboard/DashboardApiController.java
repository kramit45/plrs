package com.plrs.web.dashboard;

import com.plrs.application.dashboard.WeeklyActivityService;
import com.plrs.application.dashboard.WeeklyBucket;
import com.plrs.domain.user.UserId;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * JSON helpers consumed by the server-rendered student dashboard view.
 * Lives under {@code /web-api} per the dual-endpoint pattern from step
 * 72: {@code /api} is the JWT-authenticated public surface,
 * {@code /web-api} is for browser fetches that ride the same session
 * cookie / CSRF protection as the rest of the form-login chain.
 *
 * <p>Restricted to {@code ROLE_STUDENT}.
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}
 * to keep the bean out of the no-DB smoke test.
 */
@RestController
@RequestMapping("/web-api/me")
@ConditionalOnProperty(name = "spring.datasource.url")
public class DashboardApiController {

    private final WeeklyActivityService weeklyActivityService;

    public DashboardApiController(WeeklyActivityService weeklyActivityService) {
        this.weeklyActivityService = weeklyActivityService;
    }

    @GetMapping("/activity-weekly")
    @PreAuthorize("hasRole('STUDENT')")
    public List<WeeklyBucket> activityWeekly() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserId userId = UserId.of(UUID.fromString(auth.getName()));
        return weeklyActivityService.last8Weeks(userId);
    }
}
