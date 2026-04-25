package com.plrs.web.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plrs.application.dashboard.MasteryByTopic;
import com.plrs.application.dashboard.StudentDashboardService;
import com.plrs.application.dashboard.StudentDashboardView;
import com.plrs.domain.user.UserId;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Server-rendered student dashboard at {@code GET /dashboard} (FR-35).
 * Resolves the {@link UserId} from {@code Authentication.getName()}
 * (the JWT/login flow stamps the UUID there in Iter 1), loads the
 * three-section projection from {@link StudentDashboardService}, and
 * surfaces both the full view and the two arrays the Chart.js radar
 * needs (labels + values) as separate model attributes so the template
 * can pass them via {@code data-*} attributes — Iter 1's CSP forbids
 * inline scripts, so the chart bootstrap lives in the static
 * {@code /js/dashboard-charts.js}.
 *
 * <p>Restricted to {@code ROLE_STUDENT} via {@code @PreAuthorize}
 * (instructors get 403; unauthenticated callers are redirected to the
 * login page by the web chain).
 *
 * <p>Gated by {@code @ConditionalOnProperty("spring.datasource.url")}
 * so the bean is not created when {@code PlrsApplicationTests} runs its
 * no-DB smoke test.
 */
@Controller
@ConditionalOnProperty(name = "spring.datasource.url")
public class DashboardController {

    private final StudentDashboardService service;
    private final ObjectMapper objectMapper;

    public DashboardController(StudentDashboardService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasRole('STUDENT')")
    public String dashboard(Model model) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserId userId = UserId.of(UUID.fromString(auth.getName()));
        StudentDashboardView view = service.load(userId);
        List<String> labels =
                view.top6Mastery().stream().map(MasteryByTopic::topicName).toList();
        List<Double> values =
                view.top6Mastery().stream().map(MasteryByTopic::masteryScore).toList();
        model.addAttribute("dashboard", view);
        model.addAttribute("masteryLabels", labels);
        model.addAttribute("masteryValues", values);
        // The radar canvas reads these as data-attributes; Thymeleaf has
        // no built-in JSON encoder, and the CSP forbids inline scripts,
        // so we serialise once on the server side.
        model.addAttribute("masteryLabelsJson", writeJsonOrEmpty(labels));
        model.addAttribute("masteryValuesJson", writeJsonOrEmpty(values));
        return "dashboard/student";
    }

    private String writeJsonOrEmpty(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
