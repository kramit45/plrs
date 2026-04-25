package com.plrs.web.dashboard;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plrs.application.dashboard.MasteryByTopic;
import com.plrs.application.dashboard.RecentAttempt;
import com.plrs.application.dashboard.RecentCompletion;
import com.plrs.application.dashboard.StudentDashboardService;
import com.plrs.application.dashboard.StudentDashboardView;
import com.plrs.application.security.TokenService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice tests for {@link DashboardController}. Uses {@code @WebMvcTest} +
 * {@code addFilters=false} so security filters don't run; method-level
 * security is wired in via the nested {@link MethodSecurityTestConfig},
 * which is the same pattern other controller slice tests in this module
 * follow. As a consequence "unauthenticated" appears as a Spring anonymous
 * principal whose {@code @PreAuthorize} evaluation yields 403 (rather than
 * a 302 redirect to {@code /login} which would require the full security
 * chain). The integration-level redirect behaviour is covered by the
 * web-chain configuration itself in step 96.
 */
@WebMvcTest(
        controllers = DashboardController.class,
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = com.plrs.web.common.GlobalExceptionHandler.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(DashboardControllerTest.MethodSecurityTestConfig.class)
class DashboardControllerTest {

    private static final String STUDENT_UUID = "11111111-2222-3333-4444-555555555555";

    @Autowired private MockMvc mockMvc;

    @MockBean private StudentDashboardService service;
    @MockBean private TokenService tokenService;

    private static StudentDashboardView populatedView() {
        return new StudentDashboardView(
                List.of(
                        new MasteryByTopic(1L, "Algebra", 0.85),
                        new MasteryByTopic(2L, "Calculus", 0.60)),
                List.of(
                        new RecentCompletion(
                                100L,
                                "Intro video",
                                Instant.parse("2026-04-25T09:30:00Z"))),
                List.of(
                        new RecentAttempt(
                                500L,
                                200L,
                                "Algebra Quiz",
                                new BigDecimal("80.00"),
                                Instant.parse("2026-04-25T09:45:00Z"))));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void getDashboardAsStudent200() throws Exception {
        when(service.load(org.mockito.ArgumentMatchers.any())).thenReturn(populatedView());

        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard/student"));
    }

    @Test
    void getDashboardUnauthenticatedDeniedByMethodSecurity() throws Exception {
        // Slice-level: no SecurityContext → @PreAuthorize raises an
        // AuthenticationException (not AccessDenied), which the global
        // exception handler maps to 401. The full web chain redirects
        // to /login at runtime — that integration is verified by the
        // SecurityConfig-level tests added in step 96.
        mockMvc.perform(get("/dashboard")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "INSTRUCTOR")
    void getDashboardAsInstructor403() throws Exception {
        mockMvc.perform(get("/dashboard")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void responseHtmlContainsMasteryRadarCanvas() throws Exception {
        when(service.load(org.mockito.ArgumentMatchers.any())).thenReturn(populatedView());

        mockMvc.perform(get("/dashboard"))
                .andExpect(content().string(containsString("id=\"masteryRadar\"")))
                .andExpect(content().string(containsString("data-labels=")))
                .andExpect(content().string(containsString("Algebra")))
                .andExpect(content().string(containsString("/js/dashboard-charts.js")));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void responseHtmlRendersRecentCompletesTable() throws Exception {
        when(service.load(org.mockito.ArgumentMatchers.any())).thenReturn(populatedView());

        mockMvc.perform(get("/dashboard"))
                .andExpect(content().string(containsString("id=\"recent-completes-card\"")))
                .andExpect(content().string(containsString("Intro video")));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void responseHtmlRendersRecentAttemptsTable() throws Exception {
        when(service.load(org.mockito.ArgumentMatchers.any())).thenReturn(populatedView());

        mockMvc.perform(get("/dashboard"))
                .andExpect(content().string(containsString("id=\"recent-attempts-card\"")))
                .andExpect(content().string(containsString("Algebra Quiz")))
                .andExpect(content().string(containsString("80.00")));
    }

    @Test
    @WithMockUser(username = STUDENT_UUID, roles = "STUDENT")
    void emptyDashboardRendersEmptyStateMessages() throws Exception {
        when(service.load(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new StudentDashboardView(List.of(), List.of(), List.of()));

        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No mastery data")))
                .andExpect(content().string(containsString("Nothing completed")))
                .andExpect(content().string(containsString("No quiz attempts")));
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
